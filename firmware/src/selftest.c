// Bench self-test (build env "selftest"): exercises each peripheral without Bluetooth.
// Progress and sensor results are plain globals, read over SWD.
#ifndef SELFTEST_MOTORS
#define SELFTEST_MOTORS 0
#endif
#include "board.h"
#include "motor.h"
#include "servo.h"
#include "ultrasonic.h"
#include "line.h"
#include "rgb.h"

volatile uint8_t  st_step, st_sub;   // st_sub: motor sub-step 1..8
volatile uint16_t st_us_min = 999, st_us_max;
volatile uint32_t st_us_readings;
volatile uint8_t  st_line_or, st_line_and = 7, st_line_now;

static void wait_ms(uint32_t ms)
{
    uint32_t end = millis() + ms, next = millis();
    uint32_t seq = us_seq;
    while ((int32_t)(end - millis()) > 0) {
        IWDG->KR = 0xAAAA;
        ultrasonic_service();
        if (us_seq != seq) {
            seq = us_seq;
            st_us_readings++;
            if (us_cm > 0 && us_cm < st_us_min) st_us_min = us_cm;
            if (us_cm > st_us_max) st_us_max = us_cm;
        }
        if ((int32_t)(millis() - next) >= 0) {
            next += 10;
            line_tick();
            servo_tick();
            motor_tick();
            st_line_now = line_state();
            st_line_or |= st_line_now;
            st_line_and &= st_line_now;
        }
    }
}

static void buzzer(int on) { gpio_write(GPIOB, 1, !on); }

#if SELFTEST_MOTORS
static void motor_step(const char *unused, int l, int r)
{
    (void)unused;
    st_sub++;
    motor_set(l, r);
    wait_ms(1000);
    motor_stop();
    wait_ms(1000);
}
#endif

void selftest_run(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPBEN;
    gpio_cfg(GPIOB, 1, CFG_OUT_PP);
    buzzer(0);
    wait_ms(1500);

    st_step = 1;   // buzzer: two beeps
    for (int i = 0; i < 2; i++) { buzzer(1); wait_ms(300); buzzer(0); wait_ms(300); }

    st_step = 2;   // RGB: red, green, blue, white
    rgb_fill(40, 0, 0);  wait_ms(900);
    rgb_fill(0, 40, 0);  wait_ms(900);
    rgb_fill(0, 0, 40);  wait_ms(900);
    rgb_fill(30, 30, 30); wait_ms(900);

    st_step = 3;   // steering servo: centre -> left -> right -> centre
    servo_set(SERVO_STEER, 130, 3); wait_ms(900);
    servo_set(SERVO_STEER, 60, 3);  wait_ms(1200);
    servo_set(SERVO_STEER, 90, 3);  wait_ms(900);

    st_step = 4;   // ultrasonic pan servo: centre -> left -> right -> centre
    servo_set(SERVO_PAN, 165, 3); wait_ms(1000);
    servo_set(SERVO_PAN, 25, 3);  wait_ms(1500);
    servo_set(SERVO_PAN, 95, 3);  wait_ms(1000);

    st_step = 5;   // sensors: wave a hand in front of the ultrasonic, cover/uncover the line sensors
    st_us_min = 999; st_us_max = 0; st_line_or = 0; st_line_and = 7;
    rgb_fill(30, 0, 30);
    wait_ms(12000);

#if SELFTEST_MOTORS
    st_step = 6;   // motors, 50 % for 1 s each, 1 s pause
    rgb_fill(40, 20, 0);
    motor_step("L fwd", 50, 0);
    motor_step("L back", -50, 0);
    motor_step("R fwd", 0, 50);
    motor_step("R back", 0, -50);
    motor_step("both fwd", 50, 50);
    motor_step("both back", -50, -50);
    motor_step("spin left", -50, 50);
    motor_step("spin right", 50, -50);
#endif

    st_step = 99;  // done
    rgb_fill(0, 30, 0);
}
