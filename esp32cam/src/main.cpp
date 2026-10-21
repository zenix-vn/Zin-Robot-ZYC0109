// ESP32-CAM (AI-Thinker pinout, as in the vendor sketch) for the ZYC0109 robot.
// Runs its own Wi-Fi access point, so no home network or IP lookup is needed:
//   join Wi-Fi AP_SSID, then the stream is at http://192.168.4.1/  (same URL format as the vendor sketch)
//   http://192.168.4.1/led?on=1 / ?on=0 switches the flash LED.
// After boot the UART TX pin is released, so even with the S4 switch on it cannot block the
// STM32 <-> BT05 Bluetooth link.
#include <Arduino.h>
#include <WiFi.h>
#include "esp_camera.h"
#include "esp_http_server.h"
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

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

#define PART_BOUNDARY "123456789000000000000987654321"
static const char *STREAM_TYPE = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char *STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char *STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static esp_err_t stream_handler(httpd_req_t *req)
{
    char part[64];
    esp_err_t res = httpd_resp_set_type(req, STREAM_TYPE);
    if (res != ESP_OK) return res;
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
    return res;
}

static esp_err_t led_handler(httpd_req_t *req)
{
    char query[32], val[4] = "0";
    if (httpd_req_get_url_query_str(req, query, sizeof query) == ESP_OK)
        httpd_query_key_value(query, "on", val, sizeof val);
    bool on = val[0] == '1';
    digitalWrite(FLASH_LED_PIN, on ? HIGH : LOW);
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    return httpd_resp_sendstr(req, on ? "led on" : "led off");
}

static void start_server()
{
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = 80;
    httpd_handle_t server = NULL;
    if (httpd_start(&server, &config) != ESP_OK) return;

    httpd_uri_t led = { "/led", HTTP_GET, led_handler, NULL };
    httpd_uri_t stream = { "/", HTTP_GET, stream_handler, NULL };
    httpd_uri_t stream2 = { "/stream", HTTP_GET, stream_handler, NULL };
    httpd_register_uri_handler(server, &led);
    httpd_register_uri_handler(server, &stream);
    httpd_register_uri_handler(server, &stream2);
}

static bool start_camera()
{
    camera_config_t c = {};
    c.ledc_channel = LEDC_CHANNEL_0;
    c.ledc_timer = LEDC_TIMER_0;
    c.pin_d0 = Y2_GPIO_NUM;  c.pin_d1 = Y3_GPIO_NUM;  c.pin_d2 = Y4_GPIO_NUM;  c.pin_d3 = Y5_GPIO_NUM;
    c.pin_d4 = Y6_GPIO_NUM;  c.pin_d5 = Y7_GPIO_NUM;  c.pin_d6 = Y8_GPIO_NUM;  c.pin_d7 = Y9_GPIO_NUM;
    c.pin_xclk = XCLK_GPIO_NUM;
    c.pin_pclk = PCLK_GPIO_NUM;
    c.pin_vsync = VSYNC_GPIO_NUM;
    c.pin_href = HREF_GPIO_NUM;
    c.pin_sccb_sda = SIOD_GPIO_NUM;
    c.pin_sccb_scl = SIOC_GPIO_NUM;
    c.pin_pwdn = PWDN_GPIO_NUM;
    c.pin_reset = RESET_GPIO_NUM;
    c.xclk_freq_hz = 20000000;
    c.pixel_format = PIXFORMAT_JPEG;
    c.grab_mode = CAMERA_GRAB_LATEST;   // always send the newest frame: low latency for driving
    if (psramFound()) {
        c.frame_size = FRAMESIZE_VGA;    // 640x480
        c.jpeg_quality = 14;
        c.fb_count = 2;
        c.fb_location = CAMERA_FB_IN_PSRAM;
    } else {
        c.frame_size = FRAMESIZE_QVGA;   // 320x240
        c.jpeg_quality = 14;
        c.fb_count = 1;
        c.fb_location = CAMERA_FB_IN_DRAM;
    }
    return esp_camera_init(&c) == ESP_OK;
}

void setup()
{
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);   // motors starting can dip the supply
    pinMode(FLASH_LED_PIN, OUTPUT);
    digitalWrite(FLASH_LED_PIN, LOW);

    Serial.begin(115200);
    bool cam_ok = start_camera();

    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASS, AP_CHANNEL);
    WiFi.setSleep(false);
    start_server();

    Serial.printf("\ncamera %s, Wi-Fi \"%s\", stream http://%s/\n",
                  cam_ok ? "ok" : "FAILED", AP_SSID, WiFi.softAPIP().toString().c_str());
    Serial.flush();
    // Release UART0 TX: it shares the STM32 RX line with the BT05 when switch S4 is on.
    Serial.end();
    pinMode(1, INPUT);
}

void loop()
{
    delay(1000);
}
