#include "servo.h"
#include "board.h"

#define STEER_CENTER 90
#define PAN_CENTER   95
#define STEER_MIN    60    // mechanical linkage limits from the vendor code
#define STEER_MAX    130
#define PAN_MIN      15
#define PAN_MAX      175

static int cur[2] = { STEER_CENTER, PAN_CENTER };
static int tgt[2] = { STEER_CENTER, PAN_CENTER };
static int slew[2] = { 3, 3 };

static uint16_t pulse_us(int angle) { return 500 + angle * 2000 / 180; }

static void write(int ch)
{
    if (ch == SERVO_STEER) TIM3->CCR1 = pulse_us(cur[0]);
    else                   TIM3->CCR2 = pulse_us(cur[1]);
}

void servo_init(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPAEN;
    RCC->APB1ENR |= RCC_APB1ENR_TIM3EN;

    TIM3->PSC = 72 - 1;
    TIM3->ARR = 20000 - 1;    // 50 Hz
    TIM3->CCMR1 = (6 << TIM_CCMR1_OC1M_Pos) | TIM_CCMR1_OC1PE |
                  (6 << TIM_CCMR1_OC2M_Pos) | TIM_CCMR1_OC2PE;
    write(SERVO_STEER);
    write(SERVO_PAN);
    TIM3->CCER = TIM_CCER_CC1E | TIM_CCER_CC2E;
    TIM3->CR1 = TIM_CR1_ARPE | TIM_CR1_CEN;
    TIM3->EGR = TIM_EGR_UG;

    gpio_cfg(GPIOA, 6, CFG_AF_PP);
    gpio_cfg(GPIOA, 7, CFG_AF_PP);
}

void servo_set(int ch, int angle, int s)
{
    int lo = ch == SERVO_STEER ? STEER_MIN : PAN_MIN;
    int hi = ch == SERVO_STEER ? STEER_MAX : PAN_MAX;
    if (angle < lo) angle = lo;
    if (angle > hi) angle = hi;
    tgt[ch] = angle;
    slew[ch] = s < 1 ? 1 : s;
}

int servo_angle(int ch) { return cur[ch]; }

void servo_tick(void)
{
    for (int ch = 0; ch < 2; ch++) {
        if (cur[ch] == tgt[ch]) continue;
        int d = tgt[ch] - cur[ch];
        if (d > slew[ch]) d = slew[ch];
        if (d < -slew[ch]) d = -slew[ch];
        cur[ch] += d;
        write(ch);
    }
}
