#include "motor.h"
#include "board.h"

#define RAMP_STEP 20   // % per 10 ms tick

static int tgt[2], cur[2];   // index 0 = left, 1 = right

static int clamp(int v) { return v > 100 ? 100 : (v < -100 ? -100 : v); }

static void apply(int side, int v)
{
    int duty = v < 0 ? -v : v;
    if (side == 0) {
        TIM4->CCR2 = duty;
        gpio_write(GPIOB, 8, v > 0);
        gpio_write(GPIOB, 9, v < 0);
    } else {
        TIM4->CCR1 = duty;
        gpio_write(GPIOA, 4, v < 0);
        gpio_write(GPIOB, 5, v > 0);
    }
}

void motor_init(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPAEN | RCC_APB2ENR_IOPBEN;
    RCC->APB1ENR |= RCC_APB1ENR_TIM4EN;

    gpio_cfg(GPIOB, 8, CFG_OUT_PP);
    gpio_cfg(GPIOB, 9, CFG_OUT_PP);
    gpio_cfg(GPIOB, 5, CFG_OUT_PP);
    gpio_cfg(GPIOA, 4, CFG_OUT_PP);
    apply(0, 0);
    apply(1, 0);

    TIM4->PSC = 72 - 1;       // 1 MHz tick
    TIM4->ARR = 100 - 1;      // 10 kHz PWM, 1 % resolution
    TIM4->CCMR1 = (6 << TIM_CCMR1_OC1M_Pos) | TIM_CCMR1_OC1PE |
                  (6 << TIM_CCMR1_OC2M_Pos) | TIM_CCMR1_OC2PE;
    TIM4->CCR1 = 0;
    TIM4->CCR2 = 0;
    TIM4->CCER = TIM_CCER_CC1E | TIM_CCER_CC2E;
    TIM4->CR1 = TIM_CR1_ARPE | TIM_CR1_CEN;
    TIM4->EGR = TIM_EGR_UG;

    gpio_cfg(GPIOB, 6, CFG_AF_PP);
    gpio_cfg(GPIOB, 7, CFG_AF_PP);
}

void motor_set(int left, int right)
{
    tgt[0] = clamp(left);
    tgt[1] = clamp(right);
}

void motor_stop(void)
{
    tgt[0] = tgt[1] = cur[0] = cur[1] = 0;
    apply(0, 0);
    apply(1, 0);
}

int motor_current(int side) { return cur[side ? 1 : 0]; }

void motor_tick(void)
{
    for (int i = 0; i < 2; i++) {
        int goal = tgt[i];
        if ((cur[i] > 0 && goal < 0) || (cur[i] < 0 && goal > 0))
            goal = 0;   // slow to a stop before reversing
        if (cur[i] < goal)      cur[i] = (goal - cur[i] > RAMP_STEP) ? cur[i] + RAMP_STEP : goal;
        else if (cur[i] > goal) cur[i] = (cur[i] - goal > RAMP_STEP) ? cur[i] - RAMP_STEP : goal;
        apply(i, cur[i]);
    }
}
