// PS5 DualSense (or any Bluepad32 gamepad) paired straight to the robot: Bluepad32 runs the Bluetooth side,
// we turn the pad into the same "#X..." frames the phone app sends, on UART0 to the STM32 (switch S4 on, S3 off).
//
//   R2 / L2         forward / reverse (analog)       left stick     steer (up / down also drives)
//   L3              car <-> tank (tank: left / right stick = left / right side)
//   right stick     camera pan                        R3            camera straight
//   Cross           horn while held                   Circle        STOP, rest pose
//   Square          headlight                         Triangle      camera flash
//   L1 / R1         spin left / right                 D-pad up/down speed slow / medium / fast
//   D-pad L / R     camera pan steps                  Options       next mode    Create   light show
// Light bar = mode colour (dim red: the STM32 is not answering, check S4 / S3). Rumbles on connect, when the
// collision guard brakes, when stuck and on a patrol alarm.
//
// Everything here runs on the BTstack thread (callbacks + a 100 ms timer), except pad_status() / the flags.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "btstack_port_esp32.h"
#include "btstack_run_loop.h"
#include "uni.h"
#include "robot.h"

#define TICK_MS 100
#define STALE_MS 400        // no report for this long: treat the pad as released
#define LINK_LOST_MS 1500   // no telemetry from the STM32 for this long: it does not hear us
#define DEAD 70             // stick dead zone, of 512
#define SPIN 70

static const int SPEEDS[] = { 40, 70, 100 };
static const uint32_t MODE_COLORS[] = { 0x2F7BFF, 0x14B8D4, 0xF08A24, 0xE0559A, 0x8B5CF6 };
#define MODES 5

static uni_hid_device_t *pad;
static uni_gamepad_t gp;            // latest report
static uint16_t prev_buttons;
static uint8_t prev_misc, prev_dpad;
static uint32_t last_report, last_telemetry, last_sent;
static volatile int reports_per_s, battery;
static int report_count;
static uint32_t report_window;

static bool tank, headlight = true, driving;
static int speed = 1, spin, pan = 95, stop_burst;
static volatile int robot_mode = -1;
static int flags, shown_color = -1;
static volatile bool want_forget, want_calib;
static char pad_name[24];
static btstack_timer_source_t tick_timer;

static int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

/** Stick -512..511 to -100..100 with a dead zone around the centre. */
static int stick(int32_t v)
{
    int a = abs((int)v);
    if (a < DEAD) return 0;
    return (v > 0 ? 1 : -1) * clampi((a - DEAD) * 100 / (512 - DEAD), 0, 100);
}

/** Trigger 0..1023 to 0..100. */
static int trigger(int32_t v) { return v < 40 ? 0 : clampi((int)(v - 40) * 100 / (1023 - 40), 0, 100); }

static void sendf(const char *fmt, int a, int b)
{
    char s[24];
    snprintf(s, sizeof s, fmt, a, b);
    stm_send(s);
    last_sent = millis();
}

// ------------------------------------------------------------------ feedback

static void rumble(int ms, uint8_t strength)
{
    if (pad && pad->report_parser.play_dual_rumble) pad->report_parser.play_dual_rumble(pad, 0, ms, strength, strength);
}

static void update_color(void)
{
    if (!pad || !pad->report_parser.set_lightbar_color) return;
    bool heard = millis() - last_telemetry < LINK_LOST_MS;
    int c = !heard ? 0x300000 : robot_mode >= 0 && robot_mode < MODES ? (int)MODE_COLORS[robot_mode] : 0x101830;
    if (c == shown_color) return;
    shown_color = c;
    pad->report_parser.set_lightbar_color(pad, c >> 16, (c >> 8) & 0xFF, c & 0xFF);
}

// ------------------------------------------------------------------ commands

static void send_config(void)
{
    calib_t k = calib_get();
    char s[32];
    snprintf(s, sizeof s, "#C%d,%d,%d,%d", SPEEDS[speed], k.trim_l, k.trim_r, k.steer_trim);
    stm_send(s);
}

static void send_pan(void) { sendf("#P%d", pan, 0); }

static void send_stop(void)
{
    stm_send("#S");
    stop_burst = 3;
}

static void hello_robot(void)
{
    send_config();
    stm_send(headlight ? "#G40,40,40" : "#G0,0,0");
    send_pan();
    stm_send("#H");
}

static void on_press(uint16_t buttons, uint8_t misc, uint8_t dpad)
{
    uint16_t down = buttons & ~prev_buttons, up = prev_buttons & ~buttons;
    uint8_t mdown = misc & ~prev_misc, ddown = dpad & ~prev_dpad;
    prev_buttons = buttons;
    prev_misc = misc;
    prev_dpad = dpad;

    if (down & BUTTON_A) stm_send("#B1");                        // Cross: horn while held
    if (up & BUTTON_A) stm_send("#B0");
    if (down & BUTTON_B) {                                       // Circle: STOP, rest pose
        spin = 0;
        pan = 95;
        stm_send("#R");
        stm_send("#R");
        rumble(60, 200);
    }
    if (down & BUTTON_X) {                                       // Square: headlight
        headlight = !headlight;
        stm_send(headlight ? "#G40,40,40" : "#G0,0,0");
    }
    if (down & BUTTON_Y) flash_set(flash_level_get() ? 0 : 100, 0);   // Triangle: camera flash
    if (down & BUTTON_SHOULDER_L) spin = 1;                      // L1 / R1: spin on the spot
    if (down & BUTTON_SHOULDER_R) spin = -1;
    if (((up & BUTTON_SHOULDER_L) && spin == 1) || ((up & BUTTON_SHOULDER_R) && spin == -1)) {
        spin = 0;
        send_stop();
    }
    if (down & BUTTON_THUMB_L) { tank = !tank; rumble(80, 120); }    // L3: car <-> tank
    if (down & BUTTON_THUMB_R) { pan = 95; send_pan(); }             // R3: camera straight

    if (ddown & (DPAD_UP | DPAD_DOWN)) {                         // speed, one buzz per level
        speed = clampi(speed + ((ddown & DPAD_UP) ? 1 : -1), 0, 2);
        send_config();
        rumble(60 + 80 * speed, 150);
    }
    if (ddown & DPAD_LEFT) { pan = clampi(pan + 20, 15, 175); send_pan(); }
    if (ddown & DPAD_RIGHT) { pan = clampi(pan - 20, 15, 175); send_pan(); }

    if (mdown & MISC_BUTTON_START) sendf("#A%d", robot_mode < 0 ? 0 : (robot_mode + 1) % MODES, 0);   // Options
    if (mdown & MISC_BUTTON_SELECT) stm_send("#Y6");             // Create: light show (3 s)
}

/** 10 Hz stream, like the app: the STM32 stops by itself 600 ms after it ends. */
static void control_tick(void)
{
    uint32_t now = millis();
    bool fresh = now - last_report < STALE_MS;
    int lx = fresh ? stick(gp.axis_x) : 0, ly = fresh ? -stick(gp.axis_y) : 0;
    int rx = fresh ? stick(gp.axis_rx) : 0, ry = fresh ? -stick(gp.axis_ry) : 0;
    int l2 = fresh ? trigger(gp.brake) : 0, r2 = fresh ? trigger(gp.throttle) : 0;
    if (!fresh) spin = 0;

    bool active = lx || ly || rx || ry || l2 || r2;
    if (active && tank && (ly || ry)) {
        sendf("#M%d,%d", ly, ry);
    } else if (active && !tank && (lx || ly || l2 || r2)) {
        int throttle = l2 || r2 ? r2 - l2 : ly;
        sendf("#D%d,%d", throttle, -lx);                         // stick right = steer right (negative)
    } else if (spin) {
        sendf("#M%d,%d", -spin * SPIN, spin * SPIN);
    } else if (driving) {
        send_stop();                                             // just released: stop at once
    } else if (stop_burst > 0) {
        stm_send("#S");
        stop_burst--;
        last_sent = now;
    } else if (now - last_sent > 400) {
        stm_send("#H");                                          // keeps telemetry coming
        last_sent = now;
    }
    driving = active && (tank ? ly || ry : lx || ly || l2 || r2);

    if (!tank && rx) {                                           // camera pan, faster the further pushed
        int p = clampi(pan - rx * 8 / 100, 15, 175);
        if (p != pan) { pan = p; send_pan(); }
    }
}

// ------------------------------------------------------------------ telemetry from the STM32

static void on_line(char *line)
{
    int v[3];
    if (line[0] == 'F') {                                        // flash from the app, repeated by the STM32
        int level, fx = 0;
        if (sscanf(line + 1, "%d,%d", &level, &fx) >= 1) flash_set(level, fx);
    } else if (line[0] == 'T' && sscanf(line + 1, "%d,%d,%d", &v[0], &v[1], &v[2]) == 3) {
        last_telemetry = millis();
        robot_mode = v[0];
        int fresh = v[2] & ~flags;
        flags = v[2];
        if ((fresh & 0x08) && v[0] == 0) rumble(150, 200);      // collision guard braked while driving by hand
        if (fresh & 0x80) rumble(300, 255);                      // stuck
    } else if (line[0] == 'W') {
        rumble(1200, 255);                                       // patrol alarm
    }
}

static void uart_poll(void)
{
    static char line[48];
    static int len = -1;   // -1 = outside a frame
    uint8_t buf[128];
    int n;
    while ((n = stm_read(buf, sizeof buf)) > 0) {
        for (int i = 0; i < n; i++) {
            char b = (char)buf[i];
            if (b == '#') { len = 0; continue; }
            if (len < 0) continue;
            if (b == '\n' || b == '\r') {
                line[len] = 0;
                on_line(line);
                len = -1;
            } else if (len < (int)sizeof line - 1) {
                line[len++] = b;
            } else {
                len = -1;
            }
        }
    }
}

static void on_tick(btstack_timer_source_t *t)
{
    uart_poll();
    if (want_forget) {
        want_forget = false;
        if (pad) uni_hid_device_disconnect(pad);
        uni_bt_del_keys_unsafe();
        uni_bt_start_scanning_and_autoconnect_unsafe();
    }
    if (pad) {
        if (want_calib) { want_calib = false; send_config(); }
        control_tick();
        update_color();
    }
    uint32_t now = millis();
    if (now - report_window >= 1000) {
        reports_per_s = report_count;
        report_count = 0;
        report_window = now;
    }
    btstack_run_loop_set_timer(t, TICK_MS);
    btstack_run_loop_add_timer(t);
}

// ------------------------------------------------------------------ Bluepad32 platform

static void p_init(int argc, const char **argv) { }

static void p_on_init_complete(void)
{
    uni_bt_allow_incoming_connections(true);       // paired pads reconnect with the PS button
    uni_bt_start_scanning_and_autoconnect_unsafe();  // and new ones pair with PS + Create
    btstack_run_loop_set_timer_handler(&tick_timer, on_tick);
    btstack_run_loop_set_timer(&tick_timer, TICK_MS);
    btstack_run_loop_add_timer(&tick_timer);
}

static uni_error_t p_on_device_discovered(bd_addr_t addr, const char *name, uint16_t cod, uint8_t rssi)
{
    if ((cod & UNI_BT_COD_MINOR_MASK) & UNI_BT_COD_MINOR_KEYBOARD_AND_MICE) return UNI_ERROR_IGNORE_DEVICE;
    return UNI_ERROR_SUCCESS;
}

static void p_on_device_connected(uni_hid_device_t *d) { }

static void p_on_device_disconnected(uni_hid_device_t *d)
{
    if (d != pad) return;
    stm_send("#B0");
    stm_send("#S");
    stm_send("#S");
    stm_tx_enable(false);   // give the line back (phone + BT05 when S3 is on again)
    pad = NULL;
    spin = 0;
    driving = false;
    pad_name[0] = 0;
    uni_bt_start_scanning_and_autoconnect_unsafe();
}

static uni_error_t p_on_device_ready(uni_hid_device_t *d)
{
    if (pad) return UNI_ERROR_NO_SLOTS;   // one driver at a time
    pad = d;
    memset(&gp, 0, sizeof gp);
    prev_buttons = prev_misc = prev_dpad = 0;
    last_report = millis();
    last_telemetry = 0;
    shown_color = -1;
    strlcpy(pad_name, d->name[0] ? d->name : "gamepad", sizeof pad_name);
    uni_bt_stop_scanning_unsafe();   // inquiry scans eat into the Wi-Fi camera's airtime
    if (d->report_parser.set_player_leds) d->report_parser.set_player_leds(d, 1);
    stm_tx_enable(true);
    hello_robot();
    rumble(150, 160);
    update_color();
    return UNI_ERROR_SUCCESS;
}

static void p_on_controller_data(uni_hid_device_t *d, uni_controller_t *ctl)
{
    if (d != pad || ctl->klass != UNI_CONTROLLER_CLASS_GAMEPAD) return;
    gp = ctl->gamepad;
    battery = ctl->battery;
    last_report = millis();
    report_count++;
    on_press(gp.buttons, gp.misc_buttons, gp.dpad);
}

static const uni_property_t *p_get_property(uni_property_idx_t idx) { return NULL; }
static void p_on_oob_event(uni_platform_oob_event_t event, void *data) { }
static void p_device_dump(uni_hid_device_t *d) { }

static struct uni_platform platform = {
    .name = "zin-robot",
    .init = p_init,
    .on_init_complete = p_on_init_complete,
    .on_device_discovered = p_on_device_discovered,
    .on_device_connected = p_on_device_connected,
    .on_device_disconnected = p_on_device_disconnected,
    .on_device_ready = p_on_device_ready,
    .on_controller_data = p_on_controller_data,
    .get_property = p_get_property,
    .on_oob_event = p_on_oob_event,
    .device_dump = p_device_dump,
};

void pad_start(void)
{
    btstack_init();
    uni_platform_set_custom(&platform);
    uni_init(0, NULL);
    btstack_run_loop_execute();
}

// ------------------------------------------------------------------ outside the BTstack thread

void pad_forget(void) { want_forget = true; }
void pad_calib_changed(void) { want_calib = true; }

void pad_status(char *out, int max)
{
    static const char *modes[] = { "manual", "avoid", "line", "follow", "patrol" };
    bool heard = millis() - last_telemetry < LINK_LOST_MS;
    calib_t k = calib_get();
    snprintf(out, max,
             "gamepad: %s\n"
             "reports/s: %d   battery: %d%%\n"
             "drive: %s, speed %d %%, pan %d\n"
             "robot: %s, mode %s\n"
             "calib: trim L %d, R %d, steering %d\n",
             pad ? pad_name : "none (pair: hold PS + Create)",
             reports_per_s, battery * 100 / 255,
             tank ? "tank" : "car", SPEEDS[speed], pan,
             !pad ? "-" : heard ? "answering" : "NOT answering (switch S4 on, S3 off?)",
             robot_mode >= 0 && robot_mode < MODES ? modes[robot_mode] : "?",
             k.trim_l, k.trim_r, k.steer_trim);
}
