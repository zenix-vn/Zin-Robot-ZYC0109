#include "modes.h"
#include "board.h"
#include "motor.h"
#include "servo.h"
#include "ultrasonic.h"
#include "line.h"
#include "rgb.h"
#include "comm.h"
#include "horn.h"
#include "lights.h"
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
#define FOLLOW_RANGE_CM 70
#define START_HOLD_MS   1500   // autonomous modes: signal, then wait this long before moving

// obstacle avoidance
#define AV_ESCAPE_CM    12     // this close ahead: back off at once, steering toward the open side
#define AV_BLOCK_CM     25     // closer than this with no open side: stop and look around
#define AV_OPEN_CM      50     // a side this free counts as a way out
#define AV_SIDE_MIN_CM  14     // diagonal closer than this: also blocked (would scrape)
#define AV_REACT_CM     90     // start steering away when anything is nearer than this
#define AV_FULL_CM      120    // full cruise speed when everything is farther than this
#define AV_CLEAR_CM     55     // a turn ends once the way ahead is at least this free
#define AV_SWEEP_DEG    35     // pan swing each side of centre while cruising

// stuck detection and rescue (see "stuck detection" below)
#define MON_MOVE_CM     8      // same pan angle, reading changed more than this = the car moved
#define MON_FAR_CM      300    // readings beyond this are too vague to judge movement
#define MON_FRESH_MS    1500   // driving time only counts while a usable reading is this recent
#define STUCK_ESCAPE_MS 2000   // cruising without visible progress this long: quick back-and-turn
#define STUCK_RESCUE_MS 5000   // any manoeuvre without visible progress this long: rescue sequence
#define RESCUE_PAUSE_MS 1000   // rest between rescue rounds
#define RESCUE_ROUNDS   3      // failed rounds before giving up
#define STRANDED_CALL_MS 15000 // given up: horn call for help this often (hazard lights all along)
#define STRANDED_RETRY_MS 20000  // ... and one more rescue round after this long,
#define STRANDED_RETRIES 3       // ... this many times
#define AV_HIDDEN_BACK_MS 900  // stuck on something below the sensor: reverse at least this long ...
#define AV_HIDDEN_TURN_MS 700  // ... then turn at least this long, even though "ahead" looks clear
#define AV_BACK_MS      1000   // longest reverse of one escape
#define AV_BACK_CLEAR_CM 30    // ... ends early once this much room is ahead
#define AV_LOOP_MS      10000  // 3 escapes within this: stop and scan properly instead

enum { MODE_MANUAL, MODE_AVOID, MODE_LINE, MODE_FOLLOW, MODE_PATROL };
enum { AV_CRUISE, AV_SCAN, AV_TURN, AV_BACK, AV_RESCUE, AV_STRANDED };
enum { SIG_START, SIG_NEAR, SIG_ESCAPE, SIG_STUCK, SIG_LINE_LOST, SIG_LINE_FOUND, SIG_STOP_MARK, SIG_TARGET,
       SIG_RESCUE, SIG_STRANDED };
enum { FX_STEADY, FX_BLINK, FX_BREATHE, FX_STROBE };   // camera flash effects (ESP32-CAM firmware)

#define FLAG_GUARD     0x08
#define FLAG_LINE_LOST 0x10
#define FLAG_THINKING  0x20    // avoidance is scanning / turning to find a way
#define FLAG_RADAR     0x40    // radar sweep in progress
#define FLAG_STUCK     0x80    // avoidance is stuck: rescuing (with FLAG_THINKING) or gave up (without)
#define FLAG_ALARM     0x100   // patrol: intruder alarm sounding (FLAG_THINKING while learning the room)

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
static uint16_t flags;
static uint8_t resting;
static uint8_t radar_on;
static int radar_angle, radar_return_pan;
static uint8_t flash_auto, flash_level = 100;   // #F ...,1: the robot drives the camera flash itself
static uint8_t flash_man_level, flash_man_fx;    // last manual flash setting, restored after alarms / dancing
static uint8_t sig_lights = 1;                   // #J: turn / brake / reversing lights on the RGB matrix
static uint8_t test_sig;                         // #Y: show one light for a moment (layout check)
static uint32_t test_until;
static uint8_t horn_auto;                        // #K 1: autonomous modes honk like a self-driving car
static uint32_t mode_ms;                         // when the current mode started
static uint8_t ov_fx;                            // flash effect of the latest signal ...
static uint32_t ov_until;                        // ... shown until then (automatic flash only)

static uint32_t line_lost_since;
static int line_last_steer;

static void radar_start(void);
static void radar_cancel(void);
static char *put_int(char *p, int v);
static void flash_out(int level, int fx, int force);
static void mon_feed(int angle, int d);
static void patrol_start(void);
static void dance_stop(void);
static void sig(uint8_t s);

static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
static int mini(int a, int b) { return a < b ? a : b; }
static int maxi(int a, int b) { return a > b ? a : b; }
static int dist_ok(void) { return us_cm > 0; }

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
    horn_off();
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
    int d = us_cm > 0 ? us_cm : US_NO_ECHO;
    mon_feed(angle, d);
    return d;
}

// -------------------------------------------------------------- stuck detection
//
// There are no wheel encoders, so the only proof that the car really moves is the ultrasonic:
// every settled reading is compared with the last one taken at the same pan angle. Cases where
// the car is held although nothing is "seen" in its way:
//   - obstacle below the sensor (door sill, cable, chair crossbar), thin legs, soft or slanted
//     surfaces that swallow or deflect the echo
//   - a corner caught while spinning on the spot, or something behind while reversing (no rear sensor)
//   - chassis sitting on something with the wheels spinning freely
// In all of them the motors run but no distance changes. Something ahead in range must change when
// the car moves, so that counts at full rate; when only the sides are in range (a parallel wall
// also stays constant while driving past it) it counts at half rate. With nothing in range at all
// (open space beyond MON_FAR_CM) movement cannot be judged and nothing is counted.

#define MON_BINS 36                     // pan angle / 5

static int16_t mon_d[MON_BINS];         // last usable reading per pan angle, -1 none yet
static uint32_t mon_front_ms, mon_any_ms, mon_moved_ms;
static uint32_t stuck_ms;               // weighted driving time without visible progress

static void mon_reset(void)
{
    for (int i = 0; i < MON_BINS; i++) mon_d[i] = -1;
    mon_moved_ms = millis();
    mon_front_ms = mon_any_ms = 0;
    stuck_ms = 0;
}

/** A settled reading from pan_measure(). */
static void mon_feed(int angle, int d)
{
    if (d >= MON_FAR_CM) return;
    uint32_t now = millis();
    int b = clampi(angle / 5, 0, MON_BINS - 1);
    if (mon_d[b] >= 0 && abs(d - mon_d[b]) > MON_MOVE_CM) { mon_moved_ms = now; stuck_ms = 0; }
    mon_d[b] = (int16_t)d;
    mon_any_ms = now;
    if (abs(angle - PAN_CENTER) <= 15) mon_front_ms = now;
}

static void mon_tick(void)
{
    uint32_t now = millis();
    if (!motor_current(0) && !motor_current(1)) return;
    if (now - mon_front_ms < MON_FRESH_MS) stuck_ms += 10;
    else if (now - mon_any_ms < MON_FRESH_MS) stuck_ms += 5;
}

// -------------------------------------------------------------- obstacle avoidance

static const int8_t sweep_off[4] = { 0, AV_SWEEP_DEG, 0, -AV_SWEEP_DEG };   // C, L, C, R
static const uint8_t scan_angles[5] = { 165, 130, PAN_CENTER, 60, 25 };     // left .. right

static uint8_t av_state, sw_idx, scan_i, scan_fails;
static uint32_t av_t;
static int sw_dist[3];          // latest left / centre / right distance while cruising
static int scan_d[5];
static int turn_dir, back_steer;
static uint16_t back_min_ms, turn_min_ms;
static uint8_t back_next;       // state after AV_BACK: AV_TURN (escape) or AV_SCAN
static uint8_t esc_count;
static uint32_t esc_t;

// rescue: steer is relative to the stuck side (+ = toward it). Reversing with the wheels turned
// toward it swings the nose away from it; driving forward steers away from it directly.
static const struct { int8_t thr, steer; uint16_t ms; uint8_t spin; } rescue_steps[] = {
    { -100,  100, 1000, 0 },   // back out, nose swinging away from the stuck side
    {   80, -100,  700, 0 },   // forward, steering away from it
    { -100,    0,  800, 0 },   // straight back, full power
    {  100, -100,  300, 0 },   // rock forward / back to shake loose
    { -100,  100,  300, 0 },
    {  100, -100,  300, 0 },
    { -100,  100,  300, 0 },
    {    0, -100,  700, 1 },   // spin on the spot away from it
    { -100, -100, 1000, 0 },   // in case the side was guessed wrong: the same toward the other side
    {   80,  100,  700, 0 },
    {    0,  100,  700, 1 },
};
#define RESCUE_STEPS (int)(sizeof rescue_steps / sizeof rescue_steps[0])

static int rs_side;             // +1 stuck on the left, -1 on the right
static uint8_t rs_first, rs_n, rs_round, rs_seen, rs_retries;
static uint32_t rs_step_t, rs_call_t;

static void av_enter(uint8_t s)
{
    av_state = s;
    av_t = millis();
    pm_target = -1;
    if (s == AV_SCAN) scan_i = 0;
    if (s == AV_CRUISE) sw_idx = 0;
    if (s == AV_RESCUE) { rs_n = 0; rs_seen = 0; rs_step_t = av_t; }
    if (s == AV_STRANDED) rs_call_t = av_t - STRANDED_CALL_MS;   // call for help right away
}

/**
 * Reverse with the wheels turned so the nose swings to the more open side, then spin that way.
 * hidden: blocked by something the ultrasonic cannot see (too low), so "clear ahead" proves nothing:
 * back off and turn for fixed minimum times instead.
 */
static void av_escape(int l, int r, int hidden)
{
    back_min_ms = hidden ? AV_HIDDEN_BACK_MS : 450;
    turn_min_ms = hidden ? AV_HIDDEN_TURN_MS : 250;
    uint32_t now = millis();
    if (now - esc_t > AV_LOOP_MS) esc_count = 0;
    if (!esc_count) esc_t = now;
    esc_count++;
    turn_dir = l >= r ? 1 : -1;   // +1 left
    back_steer = -100 * turn_dir;
    back_next = (mini(l, 400) < AV_OPEN_CM && mini(r, 400) < AV_OPEN_CM) || esc_count >= 3 ? AV_SCAN : AV_TURN;
    if (back_next == AV_SCAN) esc_count = 0;
    motor_stop();
    av_enter(AV_BACK);
}

/** Held for STUCK_RESCUE_MS whatever the car tried: work it loose with a fixed manoeuvre sequence. */
static void rescue_start(void)
{
    // the stuck side: where the car was turning / steering, else the nearer side
    int st = servo_angle(SERVO_STEER) - (STEER_CENTER + steer_trim);
    if (av_state == AV_TURN) rs_side = turn_dir;
    else if (abs(st) > 8) rs_side = st > 0 ? 1 : -1;
    else rs_side = sw_dist[0] <= sw_dist[2] ? 1 : -1;
    rs_first = av_state == AV_BACK ? 1 : 0;   // held while reversing: start by driving forward
    stuck_ms = 0;
    motor_stop();
    av_enter(AV_RESCUE);
    sig(SIG_RESCUE);
}

static void avoid_start(void)
{
    sw_dist[0] = sw_dist[1] = sw_dist[2] = US_NO_ECHO;
    scan_fails = 0;
    rs_round = rs_retries = 0;
    mon_reset();
    av_enter(AV_SCAN);   // look around before moving at all
}

static void avoid_tick(void)
{
    uint32_t now = millis(), el = now - av_t;
    int cruise = clampi(max_speed * 8 / 10, 45, 100);
    int turn_sp = clampi(max_speed * 6 / 10, 45, 100);
    flags &= ~(FLAG_THINKING | FLAG_STUCK);

    mon_tick();
    if (av_state != AV_RESCUE && av_state != AV_STRANDED && stuck_ms >= STUCK_RESCUE_MS) rescue_start();

    switch (av_state) {
    case AV_CRUISE: {
        // keep sweeping centre / left / centre / right
        int off = sweep_off[sw_idx];
        int d = pan_measure(PAN_CENTER + off, 80);
        if (d >= 0) {
            sw_dist[off > 0 ? 0 : (off < 0 ? 2 : 1)] = d;
            sw_idx = (sw_idx + 1) & 3;
        }
        int l = sw_dist[0], f = sw_dist[1], r = sw_dist[2];
        if (abs(servo_angle(SERVO_PAN) - PAN_CENTER) <= 8 && dist_ok() && us_cm < f)
            f = us_cm;   // facing forward right now: the live reading is fresher than the sweep's

        if (f <= AV_ESCAPE_CM || l <= AV_SIDE_MIN_CM || r <= AV_SIDE_MIN_CM) {
            av_escape(l - (l <= AV_SIDE_MIN_CM ? 400 : 0), r - (r <= AV_SIDE_MIN_CM ? 400 : 0), 0);
            break;
        }
        int open = mini(l, 400) >= AV_OPEN_CM || mini(r, 400) >= AV_OPEN_CM;
        if (f <= AV_BLOCK_CM && !open) {   // wall ahead and no gap on either side: look around
            motor_stop();
            av_enter(AV_SCAN);
            break;
        }
        if (stuck_ms >= STUCK_ESCAPE_MS) {   // pushing against something the sensor cannot see
            av_escape(l, r, 1);
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
            if (f <= AV_BLOCK_CM) steer = l >= r ? 100 : -100;   // close: full lock into the gap
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
                back_next = AV_SCAN;
                back_min_ms = 450;
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
        if (d >= 0 && el > turn_min_ms && d >= AV_CLEAR_CM) {
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

    case AV_BACK: {
        // reverse with the wheels turned so the nose swings toward the open side
        flags |= FLAG_THINKING;
        steer_to(back_steer, 6);
        int d = pan_measure(PAN_CENTER, 40);
        if (el < AV_BACK_MS && !(d >= AV_BACK_CLEAR_CM && el > back_min_ms)) {
            int ml, mr;
            mix(-turn_sp, back_steer, 100, &ml, &mr);
            set_motors(ml, mr);
        } else {
            motor_stop();
            av_enter(back_next);
        }
        break;
    }

    case AV_RESCUE: {
        flags |= FLAG_THINKING | FLAG_STUCK;
        int d = pan_measure(PAN_CENTER, 40);   // keeps the movement monitor fed
        if (d >= 0 && d < MON_FAR_CM) rs_seen = 1;
        if ((int32_t)(mon_moved_ms - rs_step_t) > 150) {   // moved: free again, look around afresh
            motor_stop();
            rs_round = rs_retries = 0;
            esc_count = 0;
            av_enter(AV_SCAN);
            break;
        }
        if (rs_n >= RESCUE_STEPS) {   // pause between rounds
            motor_set(0, 0);
            steer_to(0, 6);
            if (now - rs_step_t < RESCUE_PAUSE_MS) break;
            if (!rs_seen) { av_enter(AV_SCAN); break; }   // nothing in range: cannot tell, carry on
            if (++rs_round >= RESCUE_ROUNDS) { av_enter(AV_STRANDED); break; }
            rs_side = -rs_side;   // start the next round from the other side
            av_enter(AV_RESCUE);
            break;
        }
        int i = (rs_first + rs_n) % RESCUE_STEPS;
        if (now - rs_step_t >= rescue_steps[i].ms) {
            rs_n++;
            rs_step_t = now;
            if (rs_n >= RESCUE_STEPS) motor_stop();
            break;
        }
        int s = rescue_steps[i].steer * rs_side;
        if (rescue_steps[i].spin) {
            steer_to(0, 8);
            int v = s > 0 ? 100 : -100;   // + = spin left
            set_motors(-v, v);
        } else {
            int ml, mr;
            steer_to(s, 8);
            mix(rescue_steps[i].thr, s, 100, &ml, &mr);
            set_motors(ml, mr);
        }
        break;
    }

    case AV_STRANDED:
        // gave up: hazard lights, call for help now and then, try again later; carry on if moved
        flags |= FLAG_STUCK;
        motor_set(0, 0);
        steer_to(0, 4);
        pan_measure(PAN_CENTER, 40);
        if ((int32_t)(mon_moved_ms - av_t) > 500) { rs_round = rs_retries = 0; av_enter(AV_SCAN); break; }
        if (now - rs_call_t >= STRANDED_CALL_MS) { rs_call_t = now; sig(SIG_STRANDED); }
        if (el >= STRANDED_RETRY_MS && rs_retries < STRANDED_RETRIES) {
            rs_retries++;
            rs_round = RESCUE_ROUNDS - 1;   // one more round
            av_enter(AV_RESCUE);
            sig(SIG_RESCUE);
        }
        break;
    }
}

// -------------------------------------------------------------- other modes

static void show_mode(void)
{
    switch (mode) {
    case MODE_AVOID:  lights_base(0, 24, 24); break;   // cyan
    case MODE_LINE:   lights_base(32, 10, 0); break;   // orange
    case MODE_FOLLOW: lights_base(32, 4, 16); break;   // pink
    case MODE_PATROL: lights_base(14, 0, 24); break;   // violet
    default:          lights_base(head_r, head_g, head_b); break;
    }
}

static void set_mode(uint8_t m)
{
    mode = m;
    mode_ms = millis();
    stop_all();
    steer_to(0, 2);
    servo_set(SERVO_PAN, PAN_CENTER, 2);
    pm_target = -1;
    line_lost_since = 0;
    line_last_steer = 0;
    flags = 0;
    resting = 1;
    radar_on = 0;
    dance_stop();
    if (m == MODE_AVOID) avoid_start();
    if (m == MODE_PATROL) patrol_start();
    show_mode();
}

// -------------------------------------------------------------- dance (#X, beats from the phone's microphone)

#define DANCE_MOVE_MS   220    // one move per beat
#define DANCE_FLASH_MS  120    // flash pulse on the beat
#define DANCE_END_MS    2500   // no beat for this long: the dance is over

static uint8_t dancing, dance_move, dance_beats, dance_flash;
static uint32_t dance_t;
static const uint8_t dance_colors[6][3] = {
    { 60, 0, 30 }, { 0, 40, 60 }, { 60, 30, 0 }, { 20, 60, 0 }, { 50, 0, 60 }, { 0, 60, 30 },
};

/** Moves: 0 / 1 wiggle left / right on the spot, 2 / 3 hop forward / back, 4 / 5 look left / right. */
static void dance_beat(int move)
{
    if (mode != MODE_MANUAL || radar_on) return;
    dancing = 1;
    dance_move = (uint8_t)(abs(move) % 6);
    dance_t = millis();
    dance_beats++;
    const uint8_t *c = dance_colors[dance_beats % 6];
    lights_base(c[0], c[1], c[2]);
    flash_out(100, FX_STEADY, 1);
    dance_flash = 1;
}

static void dance_stop(void)
{
    if (!dancing) return;
    dancing = 0;
    motor_set(0, 0);
    steer_to(0, 4);
    servo_set(SERVO_PAN, PAN_CENTER, 4);
    resting = 1;
    show_mode();
    if (!flash_auto) flash_out(flash_man_level, flash_man_fx, 1);
}

static void dance_tick(void)
{
    uint32_t el = millis() - dance_t;
    if (dance_flash && el > DANCE_FLASH_MS) { flash_out(0, FX_STEADY, 1); dance_flash = 0; }
    if (el > DANCE_END_MS) { dance_stop(); return; }
    if (el >= DANCE_MOVE_MS) { motor_set(0, 0); return; }
    switch (dance_move) {
    case 0: steer_to(0, 8); set_motors(-70, 70); break;
    case 1: steer_to(0, 8); set_motors(70, -70); break;
    case 2: steer_to(0, 8); set_motors(55, 55); break;
    case 3: steer_to(0, 8); set_motors(-55, -55); break;
    case 4: motor_set(0, 0); steer_to(100, 10); servo_set(SERVO_PAN, PAN_CENTER + 30, 10); break;
    case 5: motor_set(0, 0); steer_to(-100, 10); servo_set(SERVO_PAN, PAN_CENTER - 30, 10); break;
    }
}

// -------------------------------------------------------------- patrol (guard the room)
//
// Stands still and sweeps the ultrasonic (and so the camera) across the front, learning the distance
// at each angle. A clear change at one angle, confirmed by a second reading, raises the alarm: the
// camera stays pointed there, siren, strobing flash, police lights, and "#W offset,cm" to the phone.

#define PT_FROM      25
#define PT_STEP      20
#define PT_N         8         // 25 .. 165 deg
#define PT_NEAR_CM   250       // only changes nearer than this count: long echoes are unreliable
#define PT_ALARM_MS  6000

static int16_t pt_base[PT_N];
static uint8_t pt_i, pt_learn, pt_suspect, pt_alarm;
static int8_t pt_dir;
static uint32_t pt_alarm_t;

static void patrol_start(void)
{
    for (int i = 0; i < PT_N; i++) pt_base[i] = -1;
    pt_i = 0;
    pt_dir = 1;
    pt_learn = 2;   // sweeps spent learning the room
    pt_suspect = pt_alarm = 0;
}

static void patrol_tick(void)
{
    uint32_t now = millis();
    motor_set(0, 0);
    steer_to(0, 3);
    flags &= ~(FLAG_ALARM | FLAG_THINKING);
    if (pt_alarm) {
        flags |= FLAG_ALARM;
        if (!horn_busy()) horn_play(HORN_SIREN);
        if (!flash_auto) flash_out(100, FX_STROBE, 0);
        if (now - pt_alarm_t < PT_ALARM_MS) return;
        pt_alarm = 0;
        horn_off();
        if (!flash_auto) flash_out(flash_man_level, flash_man_fx, 1);
        pm_target = -1;
    }
    if (pt_learn) flags |= FLAG_THINKING;
    int a = PT_FROM + pt_i * PT_STEP;
    int d = pan_measure(a, 120);
    if (d < 0) return;
    int b = pt_base[pt_i];
    if (b < 0 || pt_learn) {
        pt_base[pt_i] = (int16_t)(b < 0 ? d : (b + d) / 2);
    } else if (abs(d - b) > maxi(15, b / 6) && mini(d, b) < PT_NEAR_CM) {
        if (!pt_suspect) { pt_suspect = 1; return; }   // read this angle again before raising the alarm
        pt_suspect = 0;
        pt_alarm = 1;
        pt_alarm_t = now;
        pt_base[pt_i] = (int16_t)d;   // whatever is there now becomes the new normal
        char buf[20], *p = buf;
        *p++ = '#';
        *p++ = 'W';
        p = put_int(p, a - PAN_CENTER);
        *p++ = ',';
        p = put_int(p, d);
        *p++ = '\n';
        *p = 0;
        comm_puts(buf);
        return;
    } else {
        pt_suspect = 0;
        pt_base[pt_i] = (int16_t)((b * 3 + d) / 4);   // follow slow drift
    }
    pt_i += pt_dir;
    if (pt_i >= PT_N) {   // end of a sweep (also catches 0 - 1 wrapping round)
        pt_dir = -pt_dir;
        pt_i += 2 * pt_dir;
        if (pt_learn) pt_learn--;
    }
}

static void manual_tick(void)
{
    uint32_t now = millis();
    flags &= ~FLAG_GUARD;
    if (dancing) { dance_tick(); return; }
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
    if (!dist_ok() || us_cm > FOLLOW_RANGE_CM) { motor_set(0, 0); return; }   // nothing to follow
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
        dance_stop();
        if (mode != MODE_MANUAL) set_mode(MODE_MANUAL);
        tank = 0;
        drv_t = clampi(v[0], -100, 100);
        drv_s = clampi(v[1], -100, 100);
        last_drive_ms = now;
        break;
    case 'M':
        if (n < 2) return;
        radar_cancel();
        dance_stop();
        if (mode != MODE_MANUAL) set_mode(MODE_MANUAL);
        tank = 1;
        tank_l = clampi(v[0], -100, 100);
        tank_r = clampi(v[1], -100, 100);
        last_drive_ms = now;
        break;
    case 'S':
        radar_cancel();
        if (mode != MODE_MANUAL) set_mode(MODE_MANUAL);
        else { dance_stop(); stop_all(); steer_to(0, 2); resting = 1; }
        break;
    case 'R':   // app's STOP button: rest pose, everything straight
        set_mode(MODE_MANUAL);
        horn_off();
        break;
    case 'Q':   // radar sweep around the robot (stops it first)
        radar_start();
        break;
    case 'A':
        if (n >= 1 && v[0] >= MODE_MANUAL && v[0] <= MODE_PATROL) set_mode((uint8_t)v[0]);
        break;
    case 'P':
        if (n >= 1 && mode == MODE_MANUAL && !radar_on) servo_set(SERVO_PAN, v[0], 3);
        break;
    case 'B':
        if (n >= 1) horn_set(v[0] != 0);
        break;
    case 'G':
        if (n >= 3) {
            head_r = (uint8_t)clampi(v[0], 0, 255);
            head_g = (uint8_t)clampi(v[1], 0, 255);
            head_b = (uint8_t)clampi(v[2], 0, 255);
            if (mode == MODE_MANUAL) show_mode();
        }
        break;
    case 'F':   // camera flash: manual setting, or automatic (third value 1) at the given brightness
        if (n >= 1) {
            flash_level = (uint8_t)clampi(v[0], 0, 100);
            flash_auto = n >= 3 && v[2] != 0;
            if (!flash_auto) {
                flash_man_level = flash_level;
                flash_man_fx = (uint8_t)clampi(n >= 2 ? v[1] : FX_STEADY, 0, 9);
                flash_out(flash_man_level, flash_man_fx, 1);
            }
        }
        break;
    case 'K':
        if (n >= 1) horn_auto = v[0] != 0;
        break;
    case 'X':   // dance move on a beat
        if (n >= 1) dance_beat(v[0]);
        break;
    case 'J':
        if (n >= 1) sig_lights = v[0] != 0;
        break;
    case 'L':
        if (n >= 2) lights_layout(v[0], v[1]);
        break;
    case 'Y':   // light test: 1 left, 2 right, 3 brake, 4 hazard, 5 reversing, 6 police
        if (n >= 1) {
            static const uint8_t t[] = { 0, LT_LEFT, LT_RIGHT, LT_BRAKE, LT_LEFT | LT_RIGHT, LT_REVERSE, LT_POLICE };
            test_sig = t[clampi(v[0], 0, 6)];
            test_until = now + 3000;
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
        if (horn_auto) horn_play(HORN_CHIRP);
    }
}

// -------------------------------------------------------------- camera flash

/**
 * Sends "#F level,fx" on TX, where the ESP32-CAM listens (the phone gets a copy and ignores it).
 * Repeated now and then so a camera that rebooted picks the state up again.
 */
static void flash_out(int level, int fx, int force)
{
    static int last_level = -1, last_fx = -1;
    static uint32_t last_ms;
    uint32_t now = millis();
    if (!force && level == last_level && fx == last_fx && now - last_ms < 2000) return;
    last_level = level;
    last_fx = fx;
    last_ms = now;
    char buf[20], *p = buf;
    *p++ = '#';
    *p++ = 'F';
    p = put_int(p, clampi(level, 0, 100));
    *p++ = ',';
    p = put_int(p, clampi(fx, 0, 9));
    *p++ = '\n';
    *p = 0;
    comm_puts(buf);
}

/** Automatic flash: light for the camera while working, signals while manoeuvring or in trouble. */
static void flash_auto_tick(void)
{
    static uint32_t moved_ms;
    uint32_t now = millis();
    int ml = motor_current(0), mr = motor_current(1);
    if (ml || mr) moved_ms = now;
    int fx = FX_STEADY, on = 1;
    if (dancing) return;   // the dance pulses the flash itself

    if (horn_active()) fx = FX_STROBE;   // "flash the high beams" with the horn
    else if (radar_on) fx = FX_STEADY;
    else switch (mode) {
    case MODE_AVOID:
        fx = av_state == AV_BACK || av_state == AV_RESCUE ? FX_STROBE
           : av_state == AV_CRUISE ? FX_STEADY : FX_BLINK;   // stranded: hazard blinking
        break;
    case MODE_LINE:
        fx = flags & FLAG_LINE_LOST ? FX_BLINK : FX_STEADY;
        break;
    case MODE_FOLLOW:   // tracking: steady; nothing in range: breathe while waiting
        fx = dist_ok() && us_cm <= FOLLOW_RANGE_CM ? FX_STEADY : FX_BREATHE;
        break;
    case MODE_PATROL:   // dark while guarding, strobe on alarm
        on = pt_alarm;
        fx = FX_STROBE;
        break;
    default:            // driving: headlight, blinking while reversing or held back by the guard
        on = now - moved_ms < 3000;
        fx = ml + mr < 0 || (flags & FLAG_GUARD) ? FX_BLINK : FX_STEADY;
        break;
    }
    if ((int32_t)(ov_until - now) > 0 && !horn_active()) { fx = ov_fx; on = 1; }
    flash_out(on ? flash_level : 0, fx, 0);
}

// -------------------------------------------------------------- self-driving signals
//
// Autonomous modes signal like a self-driving car / warehouse robot (flash with #F ...,1, horn with #K 1):
//   start of a mode  flash blinks + "beep beep", waits START_HOLD_MS, then moves
//   obstacle ahead   (avoid, < 40 cm) double flash, like flashing the high beams
//   emergency back   (avoid, 12 cm) "beep beep", strobe and reversing beeper while backing off
//   boxed in         (avoid, 3 failed scans) hazard blinking + three beeps, asking for help
//   stuck 5 s        (avoid, held by something unseen) one long beep, strobe while working loose
//   gave up          (avoid, rescue failed) hazard blinking + three beeps every 15 s
//   line lost        (line) hazard blinking + one long beep; line found again: short beep
//   stop marker      (line, all sensors black) short beep
//   target acquired  (follow) short beep + flash; target lost: flash breathes while waiting
//   any reversing    reversing beeper (also when following backs off)
//   radar sweep done short beep

#define FX_NONE 0xFF   // keep the effect the situation already shows

static const struct { uint8_t fx; uint16_t ms; const uint16_t *horn; } sig_table[] = {
    [SIG_START]      = { FX_BLINK,  1200, HORN_DOUBLE },
    [SIG_NEAR]       = { FX_STROBE, 500,  0 },
    [SIG_ESCAPE]     = { FX_NONE,   0,    HORN_DOUBLE },
    [SIG_STUCK]      = { FX_NONE,   0,    HORN_TRIPLE },
    [SIG_LINE_LOST]  = { FX_NONE,   0,    HORN_LONG },
    [SIG_LINE_FOUND] = { FX_STROBE, 300,  HORN_CHIRP },
    [SIG_STOP_MARK]  = { FX_NONE,   0,    HORN_CHIRP },
    [SIG_TARGET]     = { FX_STROBE, 400,  HORN_CHIRP },
    [SIG_RESCUE]     = { FX_NONE,   0,    HORN_LONG },
    [SIG_STRANDED]   = { FX_NONE,   0,    HORN_TRIPLE },
};

static void sig(uint8_t s)
{
    if (sig_table[s].fx != FX_NONE) {
        ov_fx = sig_table[s].fx;
        ov_until = millis() + sig_table[s].ms;
    }
    if (horn_auto && sig_table[s].horn) horn_play(sig_table[s].horn);
}

/** Watches the autonomous modes for the moments worth signalling (edges of their states). */
static void signals_tick(void)
{
    static uint8_t last_mode = MODE_MANUAL, last_av, last_fails, last_lost, last_stop, last_target, near_armed;
    if (mode != last_mode) {
        last_mode = mode;
        last_av = AV_SCAN;
        last_fails = last_lost = last_stop = last_target = 0;
        near_armed = 1;
        if (mode != MODE_MANUAL) sig(SIG_START);
    }
    if (mode == MODE_MANUAL || radar_on) return;

    switch (mode) {
    case MODE_AVOID:
        if (av_state == AV_BACK && last_av != AV_BACK) sig(SIG_ESCAPE);
        last_av = av_state;
        if (scan_fails >= 3 && last_fails < 3) sig(SIG_STUCK);
        last_fails = scan_fails;
        if (av_state == AV_CRUISE && near_armed && sw_dist[1] <= 40) { sig(SIG_NEAR); near_armed = 0; }
        if (sw_dist[1] > 70) near_armed = 1;
        break;
    case MODE_LINE: {
        uint8_t lost = (flags & FLAG_LINE_LOST) != 0, stop = line_state() == (LINE_L | LINE_M | LINE_R);
        if (lost && !last_lost) sig(SIG_LINE_LOST);
        if (!lost && last_lost) sig(SIG_LINE_FOUND);
        if (stop && !last_stop) sig(SIG_STOP_MARK);
        last_lost = lost;
        last_stop = stop;
        break;
    }
    case MODE_FOLLOW: {
        uint8_t target = dist_ok() && us_cm <= FOLLOW_RANGE_CM;
        if (target && !last_target && millis() - mode_ms > START_HOLD_MS) sig(SIG_TARGET);
        last_target = target;
        break;
    }
    }
    // reversing beeper, like a truck or a warehouse robot backing up
    if (horn_auto && !horn_busy() && motor_current(0) + motor_current(1) < 0) horn_play(HORN_REVERSE);
}

// -------------------------------------------------------------- car lights on the RGB matrix

/** Turn signals from steering / spinning, hazard when in trouble, brake on slowing down, reversing light. */
static void lights_update(void)
{
    static uint32_t turn_until, brake_until, mag_t;
    static uint8_t turn_side;
    static int mag_prev;
    uint32_t now = millis();
    int ml = motor_current(0), mr = motor_current(1), mag = abs(ml) + abs(mr);
    uint8_t s = 0;

    if ((int32_t)(test_until - now) > 0) s = test_sig;
    else if (mode == MODE_PATROL && pt_alarm) s = LT_POLICE;
    else if (sig_lights && !dancing) {
        int st = servo_angle(SERVO_STEER) - (STEER_CENTER + steer_trim);
        uint8_t side = 0;
        if (ml && mr && (ml > 0) != (mr > 0)) side = mr > ml ? LT_LEFT : LT_RIGHT;   // spinning on the spot
        else if (mag && st > 12) side = LT_LEFT;
        else if (mag && st < -12) side = LT_RIGHT;
        if (side) { turn_side = side; turn_until = now + 500; }
        if ((int32_t)(turn_until - now) > 0) s |= turn_side;

        if ((flags & (FLAG_LINE_LOST | FLAG_STUCK)) || (mode == MODE_AVOID && (av_state == AV_BACK || scan_fails >= 3)))
            s |= LT_LEFT | LT_RIGHT;   // hazard

        if (now - mag_t >= 100) {
            if (mag_prev - mag >= 40 || (mag_prev > 20 && mag == 0)) brake_until = now + 800;
            mag_prev = mag;
            mag_t = now;
        }
        if ((int32_t)(brake_until - now) > 0 || (mag == 0 && mode != MODE_MANUAL && mode != MODE_PATROL))
            s |= LT_BRAKE;   // also "holding the brake" while an autonomous mode stands still
        if (ml + mr < 0) s |= LT_REVERSE;
    }
    lights_render(s);
}

// -------------------------------------------------------------- entry points

void modes_init(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPBEN;
    horn_init();
    set_mode(MODE_MANUAL);
}

void modes_tick(void)
{
    if (millis() - last_rx_ms > LINK_ALIVE_MS) horn_set(0);   // never leave the driver's horn on after a disconnect
    horn_tick();
    signals_tick();
    if (flash_auto) flash_auto_tick();
    lights_update();
    flags &= ~FLAG_RADAR;
    if (radar_on) { radar_tick(); return; }
    if (mode != MODE_MANUAL && millis() - mode_ms < START_HOLD_MS) return;   // signalling the start
    switch (mode) {
    case MODE_MANUAL: manual_tick(); break;
    case MODE_AVOID:  avoid_tick(); break;
    case MODE_LINE:   line_follow_tick(); break;
    case MODE_FOLLOW: follow_tick(); break;
    case MODE_PATROL: patrol_tick(); break;
    }
}
