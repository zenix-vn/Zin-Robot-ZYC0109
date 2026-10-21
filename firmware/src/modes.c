#include "modes.h"
#include "board.h"
#include "motor.h"
#include "servo.h"
#include "ultrasonic.h"
#include "line.h"
#include "rgb.h"
#include "comm.h"
#include <stdlib.h>

#define FAILSAFE_MS     600    // manual: stop if no driving command for this long
#define LINK_ALIVE_MS   1500   // telemetry only while the app is talking to us

#define STEER_CENTER    90
#define STEER_LEFT_DEG  40     // linkage allows 60..130 deg: 40 to the left of centre,
#define STEER_RIGHT_DEG 30     // 30 to the right
#define PAN_CENTER      95
#define MIN_PWM         22     // TT motors stall below this duty: map 1..100 % onto MIN_PWM..100

#define GUARD_STOP_CM   12     // manual forward: full stop below this
#define GUARD_SLOW_CM   40     // ... and proportional slow-down below this
#define LINE_LOST_MS    1500
#define FOLLOW_CM       20

// obstacle avoidance
#define AV_BLOCK_CM     25     // straight ahead closer than this: stop and look around
#define AV_SIDE_MIN_CM  14     // diagonal closer than this: also blocked (would scrape)
#define AV_REACT_CM     90     // start steering away when anything is nearer than this
#define AV_FULL_CM      120    // full cruise speed when everything is farther than this
#define AV_CLEAR_CM     55     // a turn ends once the way ahead is at least this free
#define AV_SWEEP_DEG    35     // pan swing each side of centre while cruising
#define AV_STUCK_MS     3000   // no progress toward the obstacle ahead for this long = stuck

enum { MODE_MANUAL, MODE_AVOID, MODE_LINE, MODE_FOLLOW };
enum { AV_CRUISE, AV_SCAN, AV_TURN, AV_BACK };

#define FLAG_GUARD     0x08
#define FLAG_LINE_LOST 0x10
#define FLAG_THINKING  0x20    // avoidance is scanning / turning to find a way
#define FLAG_RADAR     0x40    // radar sweep in progress

// radar sweep (#Q): pan angles reported as offsets from straight ahead, + = left
#define RADAR_FROM     170
#define RADAR_TO       20
#define RADAR_STEP     10

static uint8_t mode;
static uint32_t last_rx_ms, last_drive_ms;
static uint8_t tank;                       // last manual command was #M
static int drv_t, drv_s, tank_l, tank_r;
static int max_speed = 70, trim_l, trim_r;
static int steer_trim;                     // degrees: mechanical centre of the steering linkage
static uint8_t head_r = 8, head_g = 8, head_b = 8;
static uint8_t flags;
static uint8_t resting;
static uint8_t radar_on;
static int radar_angle, radar_return_pan;

static uint32_t line_lost_since;
static int line_last_steer;

static void radar_start(void);
static void radar_cancel(void);

static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
static int mini(int a, int b) { return a < b ? a : b; }
static int dist_ok(void) { return us_cm > 0; }
static void buzzer(int on) { gpio_write(GPIOB, 1, !on); }   // low = sounding

// -------------------------------------------------------------- motor shaping and mixing

static int shape(int v, int trim)
{
    if (v == 0) return 0;
    int a = abs(v) * (100 + trim) / 100;
    a = MIN_PWM + a * (100 - MIN_PWM) / 100;
    if (a > 100) a = 100;
    return v < 0 ? -a : a;
}

static void set_motors(int l, int r)
{
    l = clampi(l, -100, 100);
    r = clampi(r, -100, 100);
    if (l == 0 && r == 0) motor_set(0, 0);
    else motor_set(shape(l, trim_l), shape(r, trim_r));
}

// Throttle/steer -> side speeds. The inner side slows as the steering opens and, near full lock,
// turns slightly backwards so the car pivots in a tighter circle than the steering alone allows.
static void mix(int t, int s, int limit, int *l, int *r)
{
    s = clampi(s, -100, 100);
    int v = clampi(t, -100, 100) * limit / 100;
    int inner = v * (100 - abs(s) * 13 / 10) / 100;   // full lock: inner side at -30 %
    if (s > 0) { *l = inner; *r = v; }   // steering left: left side is inside
    else       { *l = v; *r = inner; }
}

static void steer_to(int s, int slew)
{
    s = clampi(s, -100, 100);
    servo_set(SERVO_STEER, STEER_CENTER + steer_trim + s * (s > 0 ? STEER_LEFT_DEG : STEER_RIGHT_DEG) / 100, slew);
}

static void stop_all(void)
{
    drv_t = drv_s = tank_l = tank_r = 0;
    motor_stop();
}

/** Resting pose: wheels coast to a stop, steering eases back to straight, horn off. */
static void go_rest(void)
{
    motor_set(0, 0);
    steer_to(0, 2);
    buzzer(0);
    resting = 1;
}

// -------------------------------------------------------------- pan + ultrasonic sampling

static int pm_target = -1;
static uint8_t pm_marked;
static uint32_t pm_t, pm_mark;

/**
 * Points the ultrasonic at `angle` and returns a distance measured entirely after the servo
 * settled there, or -1 while still moving / waiting. Call every tick with the same angle.
 */
static int pan_measure(int angle, uint32_t settle_ms)
{
    if (pm_target != angle || servo_angle(SERVO_PAN) != angle) {
        if (pm_target != angle) servo_set(SERVO_PAN, angle, 6);
        pm_target = angle;
        pm_t = millis();
        pm_marked = 0;
        return -1;
    }
    if (!pm_marked) {
        if (millis() - pm_t >= settle_ms) { pm_marked = 1; pm_mark = us_seq; }
        return -1;
    }
    // the first completion may have started before settling; the second one cannot
    if ((uint32_t)(us_seq - pm_mark) < 2) return -1;
    pm_marked = 0;
    pm_t = millis();
    return us_cm > 0 ? us_cm : US_NO_ECHO;
}

// -------------------------------------------------------------- obstacle avoidance

static const int8_t sweep_off[4] = { 0, AV_SWEEP_DEG, 0, -AV_SWEEP_DEG };   // C, L, C, R
static const uint8_t scan_angles[5] = { 165, 130, PAN_CENTER, 60, 25 };     // left .. right

static uint8_t av_state, sw_idx, scan_i, scan_fails;
static uint32_t av_t, stuck_t;
static int sw_dist[3];          // latest left / centre / right distance while cruising
static int scan_d[5];
static int stuck_d, turn_dir, back_steer;

static void av_enter(uint8_t s)
{
    av_state = s;
    av_t = millis();
    pm_target = -1;
    if (s == AV_SCAN) scan_i = 0;
    if (s == AV_CRUISE) {
        sw_idx = 0;
        stuck_t = millis();
        stuck_d = sw_dist[1];
    }
}

static void avoid_start(void)
{
    sw_dist[0] = sw_dist[1] = sw_dist[2] = US_NO_ECHO;
    scan_fails = 0;
    av_enter(AV_SCAN);   // look around before moving at all
}

static void avoid_tick(void)
{
    uint32_t now = millis(), el = now - av_t;
    int cruise = clampi(max_speed * 8 / 10, 45, 100);
    int turn_sp = clampi(max_speed * 6 / 10, 45, 100);
    flags &= ~FLAG_THINKING;

    switch (av_state) {
    case AV_CRUISE: {
        // keep sweeping centre / left / centre / right
        int off = sweep_off[sw_idx];
        int d = pan_measure(PAN_CENTER + off, 80);
        if (d >= 0) {
            int slot = off > 0 ? 0 : (off < 0 ? 2 : 1);
            sw_dist[slot] = d;
            sw_idx = (sw_idx + 1) & 3;
            if (slot == 1) {   // progress check against the obstacle ahead
                if (d >= 150 || abs(d - stuck_d) > 5) { stuck_d = d; stuck_t = now; }
            }
        }
        int l = sw_dist[0], f = sw_dist[1], r = sw_dist[2];

        if (f <= AV_BLOCK_CM || l <= AV_SIDE_MIN_CM || r <= AV_SIDE_MIN_CM) {
            motor_stop();
            av_enter(AV_SCAN);
            break;
        }
        if (now - stuck_t > AV_STUCK_MS) {   // pushing against something the sensor cannot see
            back_steer = l >= r ? -100 : 100;
            av_enter(AV_BACK);
            break;
        }

        // speed follows the nearest obstacle ahead (sides count a little less)
        int nearest = mini(f, mini(l, r) + 15);
        int sp = nearest >= AV_FULL_CM ? cruise
               : cruise * (35 + (nearest - AV_BLOCK_CM) * 65 / (AV_FULL_CM - AV_BLOCK_CM)) / 100;

        // steer toward the more open side, harder when something is close
        int steer = 0;
        if (mini(f, mini(l, r)) < AV_REACT_CM) {
            steer = clampi((clampi(l, 0, 150) - clampi(r, 0, 150)) * 3 / 2, -100, 100);
            if (f < 60 && abs(steer) < 60) steer = l >= r ? 60 : -60;
        }
        int ml, mr;
        steer_to(steer, 3);
        mix(sp, steer, 100, &ml, &mr);
        set_motors(ml, mr);
        break;
    }

    case AV_SCAN: {
        flags |= FLAG_THINKING;
        motor_set(0, 0);
        steer_to(0, 4);
        int d = pan_measure(scan_angles[scan_i], 150);
        if (d < 0) break;
        scan_d[scan_i++] = d;
        if (scan_i < 5) break;

        // pick the most open direction; on near-ties prefer the smaller turn
        int best = 2;
        for (int i = 0; i < 5; i++) {
            int clearly_more = scan_d[i] > scan_d[best] + 10;
            int smaller_turn = scan_d[i] >= scan_d[best] - 10 && abs(i - 2) < abs(best - 2);
            if (clearly_more || smaller_turn) best = i;
        }
        sw_dist[0] = scan_d[1];
        sw_dist[1] = scan_d[2];
        sw_dist[2] = scan_d[3];
        if (scan_d[best] < AV_BLOCK_CM + 10) {
            if (scan_fails < 3) {                      // boxed in: back out, then look again
                scan_fails++;
                back_steer = scan_d[0] + scan_d[1] >= scan_d[3] + scan_d[4] ? -100 : 100;
                av_enter(AV_BACK);
            } else {
                av_enter(AV_SCAN);                     // still boxed in: stay put, keep looking
            }
        } else if (best == 2) {
            scan_fails = 0;
            av_enter(AV_CRUISE);
        } else {
            scan_fails = 0;
            turn_dir = best < 2 ? 1 : -1;   // +1 left
            av_enter(AV_TURN);
        }
        break;
    }

    case AV_TURN: {
        // spin toward the chosen side until the sensor (facing forward) sees open space
        flags |= FLAG_THINKING;
        steer_to(0, 6);
        set_motors(-turn_dir * turn_sp, turn_dir * turn_sp);
        int d = pan_measure(PAN_CENTER, 40);
        if (d >= 0 && el > 250 && d >= AV_CLEAR_CM) {
            motor_stop();
            sw_dist[1] = d;
            sw_dist[0] = sw_dist[2] = US_NO_ECHO;
            av_enter(AV_CRUISE);
        } else if (el > 2500) {   // went round without finding a gap: decide again
            motor_stop();
            av_enter(AV_SCAN);
        }
        break;
    }

    case AV_BACK:
        // reverse with the wheels turned so the nose swings toward the open side
        flags |= FLAG_THINKING;
        servo_set(SERVO_PAN, PAN_CENTER, 6);
        steer_to(back_steer, 6);
        if (el < 700) {
            int ml, mr;
            mix(-turn_sp, back_steer, 100, &ml, &mr);
            set_motors(ml, mr);
        } else {
            motor_stop();
            av_enter(AV_SCAN);
        }
        break;
    }
}

// -------------------------------------------------------------- other modes

static void show_mode(void)
{
    switch (mode) {
    case MODE_AVOID:  rgb_fill(0, 24, 24); break;   // cyan
    case MODE_LINE:   rgb_fill(32, 10, 0); break;   // orange
    case MODE_FOLLOW: rgb_fill(32, 4, 16); break;   // pink
    default:          rgb_fill(head_r, head_g, head_b); break;
    }
}

static void set_mode(uint8_t m)
{
    mode = m;
    stop_all();
    steer_to(0, 2);
    servo_set(SERVO_PAN, PAN_CENTER, 2);
    pm_target = -1;
    line_lost_since = 0;
    line_last_steer = 0;
    flags = 0;
    resting = 1;
    radar_on = 0;
    if (m == MODE_AVOID) avoid_start();
    show_mode();
}

static void manual_tick(void)
{
    uint32_t now = millis();
    flags &= ~FLAG_GUARD;
    int idle = (now - last_drive_ms > FAILSAFE_MS)   // app stopped streaming: never drive blind
            || (tank ? (tank_l == 0 && tank_r == 0) : (drv_t == 0 && drv_s == 0));
    if (idle) {
        if (!resting) { drv_t = drv_s = tank_l = tank_r = 0; go_rest(); }
        return;
    }
    resting = 0;

    int l, r;
    if (tank) {
        steer_to(0, 4);
        l = tank_l * max_speed / 100;
        r = tank_r * max_speed / 100;
    } else if (abs(drv_t) < 20 && abs(drv_s) > 35) {
        // stick pushed sideways: rotate on the spot that way (positive steer = left)
        steer_to(0, 4);
        int v = drv_s * max_speed / 100 * 7 / 10;
        l = -v;
        r = v;
    } else {
        steer_to(drv_s, 4);
        mix(drv_t, drv_s, max_speed, &l, &r);
    }

    // collision guard: ultrasonic must face forward to be trusted
    int pan = servo_angle(SERVO_PAN);
    if (l + r > 0 && pan > PAN_CENTER - 12 && pan < PAN_CENTER + 12 && dist_ok()
        && us_cm < GUARD_SLOW_CM) {   // moving forward overall (a pivot turn has one side < 0)
        int scale = us_cm <= GUARD_STOP_CM ? 0
                  : (us_cm - GUARD_STOP_CM) * 100 / (GUARD_SLOW_CM - GUARD_STOP_CM);
        if (l > 0) l = l * scale / 100;
        if (r > 0) r = r * scale / 100;
        flags |= FLAG_GUARD;
    }
    set_motors(l, r);
}

static void line_follow_tick(void)
{
    const int base = 45;
    int steer, spd;
    uint8_t s = line_state();   // bit0 L, bit1 M, bit2 R; 1 = black
    flags &= ~FLAG_LINE_LOST;

    switch (s) {
    case LINE_M:                   steer = 0;    spd = base;            break;
    case LINE_L | LINE_M:          steer = 50;   spd = base * 8 / 10;   break;
    case LINE_L:                   steer = 100;  spd = base * 6 / 10;   break;
    case LINE_M | LINE_R:          steer = -50;  spd = base * 8 / 10;   break;
    case LINE_R:                   steer = -100; spd = base * 6 / 10;   break;
    case LINE_L | LINE_M | LINE_R: motor_set(0, 0); steer_to(0, 3); return;   // stop marker / crossing
    case 0:                                                                   // lost: search toward the last side
        if (!line_lost_since) line_lost_since = millis();
        if (millis() - line_lost_since > LINE_LOST_MS) {
            motor_set(0, 0);
            steer_to(0, 3);
            flags |= FLAG_LINE_LOST;
            return;
        }
        steer = line_last_steer > 0 ? 100 : (line_last_steer < 0 ? -100 : 0);
        spd = base / 2;
        break;
    default:                       steer = 0;    spd = base * 6 / 10;   break;   // L+R without M
    }
    if (s) { line_lost_since = 0; if (steer) line_last_steer = steer; }

    int l, r;
    steer_to(steer, 5);
    mix(spd, steer, 100, &l, &r);
    set_motors(l, r);
}

static void follow_tick(void)
{
    steer_to(0, 4);
    if (!dist_ok() || us_cm > 70) { motor_set(0, 0); return; }   // nothing to follow
    int err = us_cm - FOLLOW_CM;
    if (abs(err) <= 3) { motor_set(0, 0); return; }
    int v = clampi(err * 4, -60, 60) * max_speed / 100;
    set_motors(v, v);
}

// -------------------------------------------------------------- protocol

static int parse_ints(const char *s, int *v, int max)
{
    int n = 0;
    while (*s && n < max) {
        while (*s == ' ') s++;
        int neg = 0;
        if (*s == '-') { neg = 1; s++; }
        if (*s < '0' || *s > '9') break;
        int x = 0;
        while (*s >= '0' && *s <= '9') { x = x * 10 + (*s - '0'); if (x > 10000) x = 10000; s++; }
        v[n++] = neg ? -x : x;
        if (*s != ',') break;
        s++;
    }
    return n;
}

static void handle(const char *ln)
{
    int v[4];
    int n = parse_ints(ln + 1, v, 4);
    uint32_t now = millis();
    last_rx_ms = now;

    switch (ln[0]) {
    case 'D':
        if (n < 2) return;
        radar_cancel();
        if (mode != MODE_MANUAL) set_mode(MODE_MANUAL);
        tank = 0;
        drv_t = clampi(v[0], -100, 100);
        drv_s = clampi(v[1], -100, 100);
        last_drive_ms = now;
        break;
    case 'M':
        if (n < 2) return;
        radar_cancel();
        if (mode != MODE_MANUAL) set_mode(MODE_MANUAL);
        tank = 1;
        tank_l = clampi(v[0], -100, 100);
        tank_r = clampi(v[1], -100, 100);
        last_drive_ms = now;
        break;
    case 'S':
        radar_cancel();
        if (mode != MODE_MANUAL) set_mode(MODE_MANUAL);
        else { stop_all(); steer_to(0, 2); resting = 1; }
        break;
    case 'R':   // app's STOP button: rest pose, everything straight
        set_mode(MODE_MANUAL);
        buzzer(0);
        break;
    case 'Q':   // radar sweep around the robot (stops it first)
        radar_start();
        break;
    case 'A':
        if (n >= 1 && v[0] >= MODE_MANUAL && v[0] <= MODE_FOLLOW) set_mode((uint8_t)v[0]);
        break;
    case 'P':
        if (n >= 1 && mode == MODE_MANUAL && !radar_on) servo_set(SERVO_PAN, v[0], 3);
        break;
    case 'B':
        if (n >= 1) buzzer(v[0] != 0);
        break;
    case 'G':
        if (n >= 3) {
            head_r = (uint8_t)clampi(v[0], 0, 255);
            head_g = (uint8_t)clampi(v[1], 0, 255);
            head_b = (uint8_t)clampi(v[2], 0, 255);
            if (mode == MODE_MANUAL) show_mode();
        }
        break;
    case 'C':
        if (n >= 3) {
            max_speed = clampi(v[0], 20, 100);
            trim_l = clampi(v[1], -30, 30);
            trim_r = clampi(v[2], -30, 30);
        }
        if (n >= 4) {
            steer_trim = clampi(v[3], -15, 15);
            if (resting || mode != MODE_MANUAL) steer_to(0, 3);   // show the new centre right away
        }
        break;
    default:   // 'H' heartbeat and anything unknown: only refreshes the link
        break;
    }
}

static char rx_line[32];
static uint8_t rx_len, rx_in_frame;

void modes_rx(uint8_t b)
{
    if (b == '#') { rx_in_frame = 1; rx_len = 0; return; }
    if (!rx_in_frame) return;   // bytes outside a frame (module chatter, noise) are ignored
    if (b == '\n' || b == '\r') {
        rx_line[rx_len] = 0;
        rx_in_frame = 0;
        if (rx_len) handle(rx_line);
        return;
    }
    if (rx_len < sizeof rx_line - 1) rx_line[rx_len++] = (char)b;
    else rx_in_frame = 0;   // too long: not ours
}

// -------------------------------------------------------------- telemetry

static char *put_int(char *p, int v)
{
    char tmp[8];
    int i = 0;
    if (v < 0) { *p++ = '-'; v = -v; }
    do { tmp[i++] = (char)('0' + v % 10); v /= 10; } while (v && i < 7);
    while (i) *p++ = tmp[--i];
    return p;
}

void modes_telemetry(void)
{
    if (millis() - last_rx_ms > LINK_ALIVE_MS) return;
    int vals[7] = {
        mode, us_cm, flags | line_state(), motor_current(0), motor_current(1),
        servo_angle(SERVO_STEER), servo_angle(SERVO_PAN),
    };
    char buf[48], *p = buf;
    *p++ = '#';
    *p++ = 'T';
    for (int i = 0; i < 7; i++) {
        if (i) *p++ = ',';
        p = put_int(p, vals[i]);
    }
    *p++ = '\n';
    *p = 0;
    comm_puts(buf);
}

// -------------------------------------------------------------- radar sweep

static void radar_start(void)
{
    if (mode != MODE_MANUAL) set_mode(MODE_MANUAL);
    stop_all();
    steer_to(0, 2);
    resting = 1;
    radar_return_pan = servo_angle(SERVO_PAN);
    radar_angle = RADAR_FROM;
    pm_target = -1;
    radar_on = 1;
}

static void radar_cancel(void)
{
    if (!radar_on) return;
    radar_on = 0;
    pm_target = -1;
    servo_set(SERVO_PAN, radar_return_pan, 4);
}

/** One reading per angle, sent as "#R<offset>,<cm>"; "#E" marks the end of the sweep. */
static void radar_tick(void)
{
    flags |= FLAG_RADAR;
    int d = pan_measure(radar_angle, radar_angle == RADAR_FROM ? 350 : 120);
    if (d < 0) return;
    char buf[20], *p = buf;
    *p++ = '#';
    *p++ = 'R';
    p = put_int(p, radar_angle - PAN_CENTER);
    *p++ = ',';
    p = put_int(p, d);
    *p++ = '\n';
    *p = 0;
    comm_puts(buf);
    radar_angle -= RADAR_STEP;
    if (radar_angle < RADAR_TO) {
        radar_cancel();   // also turns the camera back to where it was
        comm_puts("#E\n");
    }
}

// -------------------------------------------------------------- entry points

void modes_init(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPBEN;
    gpio_cfg(GPIOB, 1, CFG_OUT_PP);
    buzzer(0);
    set_mode(MODE_MANUAL);
}

void modes_tick(void)
{
    if (millis() - last_rx_ms > LINK_ALIVE_MS) buzzer(0);   // never leave the horn on after a disconnect
    flags &= ~FLAG_RADAR;
    if (radar_on) { radar_tick(); return; }
    switch (mode) {
    case MODE_MANUAL: manual_tick(); break;
    case MODE_AVOID:  avoid_tick(); break;
    case MODE_LINE:   line_follow_tick(); break;
    case MODE_FOLLOW: follow_tick(); break;
    }
}
