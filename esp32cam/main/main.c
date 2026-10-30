// ESP32-CAM (AI-Thinker pinout) for the ZYC0109 robot.
// Runs its own Wi-Fi access point, so no home network or IP lookup is needed:
//   join Wi-Fi AP_SSID, then the stream is at http://192.168.4.1/  (same URL format as the vendor sketch)
//   http://192.168.4.1/led?level=0..100&fx=0..3 sets the flash LED (?on=1 / ?on=0 still works).
//   http://192.168.4.1/status   gamepad / link state      /forget   forget paired gamepads
//   http://192.168.4.1/config?tl=..&tr=..&st=..   motor trims, sent by the app so gamepad driving matches it
// The flash can also be driven over Bluetooth without Wi-Fi: the STM32 repeats the app's
// "#F level,fx" frame on its TX line, which reaches our RX (GPIO3) at 9600 baud.
//   fx: 0 steady, 1 blink, 2 breathe, 3 strobe
// A PS5 DualSense can pair with us directly (pad.c): then we drive the STM32 over our TX instead of the
// phone. That needs switch S4 on and S3 (BT05) off; TX is released whenever no gamepad is connected.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "driver/uart.h"
#include "esp_camera.h"
#include "esp_event.h"
#include "esp_http_server.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "robot.h"

#define AP_SSID "ZinRobot-CAM"
#define AP_PASS "zinrobot2026"   // at least 8 characters
#define AP_CHANNEL 6

// AI-Thinker ESP32-CAM
#define PWDN_GPIO_NUM 32
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM 0
#define SIOD_GPIO_NUM 26
#define SIOC_GPIO_NUM 27
#define Y9_GPIO_NUM 35
#define Y8_GPIO_NUM 34
#define Y7_GPIO_NUM 39
#define Y6_GPIO_NUM 36
#define Y5_GPIO_NUM 21
#define Y4_GPIO_NUM 19
#define Y3_GPIO_NUM 18
#define Y2_GPIO_NUM 5
#define VSYNC_GPIO_NUM 25
#define HREF_GPIO_NUM 23
#define PCLK_GPIO_NUM 22
#define FLASH_LED_PIN 4
#define FLASH_LEDC_CH LEDC_CHANNEL_2      // channel 0 / timer 0 drive the camera clock
#define FLASH_LEDC_TIMER LEDC_TIMER_1
#define FLASH_BITS 10
#define STM_UART UART_NUM_0
#define STM_TX_PIN 1
#define STM_RX_PIN 3
#define MAX_STREAMS 2

enum { FX_STEADY, FX_BLINK, FX_BREATHE, FX_STROBE, FX_COUNT };
static volatile int flash_level, flash_fx;   // set by HTTP / UART / gamepad, rendered by flash_task
static volatile bool tx_on;
static volatile int streams;
static calib_t calib;

uint32_t millis(void) { return (uint32_t)(esp_timer_get_time() / 1000); }

static int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

// ------------------------------------------------------------------ flash LED

void flash_set(int level, int fx)
{
    flash_level = clampi(level, 0, 100);
    flash_fx = fx >= 0 && fx < FX_COUNT ? fx : FX_STEADY;
}

int flash_level_get(void) { return flash_level; }

/** Brightness 0..100 % for the current effect, perceptually scaled to the PWM duty. */
static void flash_task(void *arg)
{
    for (;;) {
        uint32_t t = millis();
        int level = flash_level;
        switch (flash_fx) {
        case FX_BLINK:   if (t % 1000 >= 500) level = 0; break;
        case FX_BREATHE: {
            int ph = t % 2000;
            level = level * (ph < 1000 ? ph : 2000 - ph) / 1000;
            break;
        }
        case FX_STROBE:  if (t % 120 >= 30) level = 0; break;
        }
        ledc_set_duty(LEDC_LOW_SPEED_MODE, FLASH_LEDC_CH, (uint32_t)level * level * ((1 << FLASH_BITS) - 1) / 10000);
        ledc_update_duty(LEDC_LOW_SPEED_MODE, FLASH_LEDC_CH);
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

static void flash_init(void)
{
    ledc_timer_config_t t = {
        .speed_mode = LEDC_LOW_SPEED_MODE, .duty_resolution = FLASH_BITS, .timer_num = FLASH_LEDC_TIMER,
        .freq_hz = 5000, .clk_cfg = LEDC_AUTO_CLK,
    };
    ledc_timer_config(&t);
    ledc_channel_config_t c = {
        .gpio_num = FLASH_LED_PIN, .speed_mode = LEDC_LOW_SPEED_MODE, .channel = FLASH_LEDC_CH,
        .timer_sel = FLASH_LEDC_TIMER, .duty = 0,
    };
    ledc_channel_config(&c);
}

// ------------------------------------------------------------------ STM32 UART

static void stm_init(void)
{
    uart_config_t u = {
        .baud_rate = 9600, .data_bits = UART_DATA_8_BITS, .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1, .flow_ctrl = UART_HW_FLOWCTRL_DISABLE, .source_clk = UART_SCLK_DEFAULT,
    };
    uart_driver_install(STM_UART, 1024, 512, 0, NULL, 0);
    uart_param_config(STM_UART, &u);
    uart_set_pin(STM_UART, STM_TX_PIN, STM_RX_PIN, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
    stm_tx_enable(false);
}

void stm_tx_enable(bool on)
{
    if (on == tx_on) return;
    if (on) {
        uart_set_pin(STM_UART, STM_TX_PIN, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
        tx_on = true;
    } else {
        tx_on = false;
        uart_wait_tx_done(STM_UART, pdMS_TO_TICKS(100));
        // released: with S4 on it must not hold the STM32 RX line the BT05 is using
        gpio_reset_pin(STM_TX_PIN);
        gpio_set_direction(STM_TX_PIN, GPIO_MODE_INPUT);
    }
}

void stm_send(const char *frame)
{
    if (!tx_on) return;
    uart_write_bytes(STM_UART, frame, strlen(frame));
    uart_write_bytes(STM_UART, "\n", 1);
}

int stm_read(uint8_t *buf, int max) { return uart_read_bytes(STM_UART, buf, max, 0); }

// ------------------------------------------------------------------ calibration (NVS)

calib_t calib_get(void) { return calib; }

static void calib_load(void)
{
    nvs_handle_t h;
    if (nvs_open("zin", NVS_READONLY, &h) != ESP_OK) return;
    int8_t v;
    if (nvs_get_i8(h, "tl", &v) == ESP_OK) calib.trim_l = v;
    if (nvs_get_i8(h, "tr", &v) == ESP_OK) calib.trim_r = v;
    if (nvs_get_i8(h, "st", &v) == ESP_OK) calib.steer_trim = v;
    nvs_close(h);
}

static void calib_save(void)
{
    nvs_handle_t h;
    if (nvs_open("zin", NVS_READWRITE, &h) != ESP_OK) return;
    nvs_set_i8(h, "tl", calib.trim_l);
    nvs_set_i8(h, "tr", calib.trim_r);
    nvs_set_i8(h, "st", calib.steer_trim);
    nvs_commit(h);
    nvs_close(h);
}

// ------------------------------------------------------------------ HTTP

#define PART_BOUNDARY "123456789000000000000987654321"
static const char *STREAM_TYPE = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char *STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char *STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

/** Streams in its own task, so the server stays free for /led, /config and /status meanwhile. */
static void stream_task(void *arg)
{
    httpd_req_t *req = arg;
    char part[64];
    esp_err_t res = httpd_resp_set_type(req, STREAM_TYPE);
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");

    while (res == ESP_OK) {
        camera_fb_t *fb = esp_camera_fb_get();
        if (!fb) { res = ESP_FAIL; break; }

        uint8_t *jpg = fb->buf;
        size_t len = fb->len;
        bool converted = false;
        if (fb->format != PIXFORMAT_JPEG) {
            converted = frame2jpg(fb, 80, &jpg, &len);
            esp_camera_fb_return(fb);
            fb = NULL;
            if (!converted) { res = ESP_FAIL; break; }
        }
        size_t hlen = snprintf(part, sizeof part, STREAM_PART, (unsigned)len);
        res = httpd_resp_send_chunk(req, part, hlen);
        if (res == ESP_OK) res = httpd_resp_send_chunk(req, (const char *)jpg, len);
        if (res == ESP_OK) res = httpd_resp_send_chunk(req, STREAM_BOUNDARY, strlen(STREAM_BOUNDARY));

        if (fb) esp_camera_fb_return(fb);
        else if (converted) free(jpg);
    }
    httpd_req_async_handler_complete(req);
    streams--;
    vTaskDelete(NULL);
}

static esp_err_t stream_handler(httpd_req_t *req)
{
    if (streams >= MAX_STREAMS) {
        httpd_resp_set_status(req, "503 Busy");
        return httpd_resp_sendstr(req, "too many viewers");
    }
    httpd_req_t *copy;
    if (httpd_req_async_handler_begin(req, &copy) != ESP_OK) return ESP_FAIL;
    streams++;
    if (xTaskCreatePinnedToCore(stream_task, "stream", 4096, copy, 5, NULL, 1) != pdPASS) {
        streams--;
        httpd_req_async_handler_complete(copy);
        return ESP_FAIL;
    }
    return ESP_OK;
}

static esp_err_t led_handler(httpd_req_t *req)
{
    char query[48], val[8];
    int level = flash_level, fx = flash_fx;
    if (httpd_req_get_url_query_str(req, query, sizeof query) == ESP_OK) {
        if (httpd_query_key_value(query, "on", val, sizeof val) == ESP_OK) level = val[0] == '1' ? 100 : 0;
        if (httpd_query_key_value(query, "level", val, sizeof val) == ESP_OK) level = atoi(val);
        if (httpd_query_key_value(query, "fx", val, sizeof val) == ESP_OK) fx = atoi(val);
    }
    flash_set(level, fx);
    char reply[32];
    snprintf(reply, sizeof reply, "led %d,%d", flash_level, flash_fx);
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    return httpd_resp_sendstr(req, reply);
}

static esp_err_t config_handler(httpd_req_t *req)
{
    char query[64], val[8];
    if (httpd_req_get_url_query_str(req, query, sizeof query) == ESP_OK) {
        if (httpd_query_key_value(query, "tl", val, sizeof val) == ESP_OK) calib.trim_l = clampi(atoi(val), -30, 30);
        if (httpd_query_key_value(query, "tr", val, sizeof val) == ESP_OK) calib.trim_r = clampi(atoi(val), -30, 30);
        if (httpd_query_key_value(query, "st", val, sizeof val) == ESP_OK) calib.steer_trim = clampi(atoi(val), -15, 15);
        calib_save();
        pad_calib_changed();
    }
    char reply[48];
    snprintf(reply, sizeof reply, "calib %d,%d,%d", calib.trim_l, calib.trim_r, calib.steer_trim);
    return httpd_resp_sendstr(req, reply);
}

static esp_err_t status_handler(httpd_req_t *req)
{
    char s[512];
    pad_status(s, sizeof s);
    httpd_resp_set_type(req, "text/plain; charset=utf-8");
    return httpd_resp_sendstr(req, s);
}

static esp_err_t forget_handler(httpd_req_t *req)
{
    pad_forget();
    return httpd_resp_sendstr(req, "paired gamepads forgotten, pairing mode on");
}

static void start_server(void)
{
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = 80;
    config.lru_purge_enable = true;
    httpd_handle_t server = NULL;
    if (httpd_start(&server, &config) != ESP_OK) return;

    httpd_uri_t uris[] = {
        { "/led", HTTP_GET, led_handler, NULL },
        { "/config", HTTP_GET, config_handler, NULL },
        { "/status", HTTP_GET, status_handler, NULL },
        { "/forget", HTTP_GET, forget_handler, NULL },
        { "/", HTTP_GET, stream_handler, NULL },
        { "/stream", HTTP_GET, stream_handler, NULL },
    };
    for (int i = 0; i < sizeof uris / sizeof uris[0]; i++) httpd_register_uri_handler(server, &uris[i]);
}

// ------------------------------------------------------------------ camera, Wi-Fi

static bool start_camera(void)
{
    camera_config_t c = {
        .ledc_channel = LEDC_CHANNEL_0, .ledc_timer = LEDC_TIMER_0,
        .pin_d0 = Y2_GPIO_NUM, .pin_d1 = Y3_GPIO_NUM, .pin_d2 = Y4_GPIO_NUM, .pin_d3 = Y5_GPIO_NUM,
        .pin_d4 = Y6_GPIO_NUM, .pin_d5 = Y7_GPIO_NUM, .pin_d6 = Y8_GPIO_NUM, .pin_d7 = Y9_GPIO_NUM,
        .pin_xclk = XCLK_GPIO_NUM, .pin_pclk = PCLK_GPIO_NUM, .pin_vsync = VSYNC_GPIO_NUM,
        .pin_href = HREF_GPIO_NUM, .pin_sccb_sda = SIOD_GPIO_NUM, .pin_sccb_scl = SIOC_GPIO_NUM,
        .pin_pwdn = PWDN_GPIO_NUM, .pin_reset = RESET_GPIO_NUM,
        .xclk_freq_hz = 20000000,
        .pixel_format = PIXFORMAT_JPEG,
        .grab_mode = CAMERA_GRAB_LATEST,   // always send the newest frame: low latency for driving
        .frame_size = FRAMESIZE_VGA,       // 640x480
        .jpeg_quality = 14,
        .fb_count = 2,
        .fb_location = CAMERA_FB_IN_PSRAM,
    };
    return esp_camera_init(&c) == ESP_OK;
}

static void start_wifi(void)
{
    esp_netif_init();
    esp_event_loop_create_default();
    esp_netif_create_default_wifi_ap();
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    esp_wifi_init(&init);
    wifi_config_t ap = {
        .ap = {
            .ssid = AP_SSID, .ssid_len = sizeof AP_SSID - 1, .password = AP_PASS,
            .channel = AP_CHANNEL, .max_connection = 4, .authmode = WIFI_AUTH_WPA2_PSK,
        },
    };
    esp_wifi_set_mode(WIFI_MODE_AP);
    esp_wifi_set_config(WIFI_IF_AP, &ap);
    esp_wifi_start();
}

void app_main(void)
{
    if (nvs_flash_init() != ESP_OK) { nvs_flash_erase(); nvs_flash_init(); }
    calib_load();
    flash_init();
    xTaskCreatePinnedToCore(flash_task, "flash", 2048, NULL, 3, NULL, 1);
    stm_init();
    start_camera();
    start_wifi();
    start_server();
    pad_start();   // Bluetooth gamepad; does not return
}
