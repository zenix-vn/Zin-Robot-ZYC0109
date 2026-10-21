#include "ultrasonic.h"
#include "board.h"

#define PERIOD_MS  60
#define TIMEOUT_MS 30

enum { IDLE, WAIT_RISE, WAIT_FALL, DONE };

volatile uint16_t us_cm = US_NO_ECHO;
volatile uint32_t us_seq;
static volatile uint8_t state = IDLE;
static volatile uint16_t t_rise, width;
static uint32_t t_start;

void ultrasonic_init(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPAEN;
    RCC->APB1ENR |= RCC_APB1ENR_TIM2EN;

    gpio_cfg(GPIOA, 0, CFG_OUT_PP);
    gpio_write(GPIOA, 0, 0);
    gpio_cfg(GPIOA, 1, CFG_IN_PULL);
    GPIOA->BRR = 1u << 1;   // pull-down

    TIM2->PSC = 72 - 1;     // 1 us per count
    TIM2->ARR = 0xFFFF;
    TIM2->CCMR1 = TIM_CCMR1_CC2S_0;   // CH2 captures from TI2
    TIM2->CCER = TIM_CCER_CC2E;       // rising edge first
    TIM2->DIER = TIM_DIER_CC2IE;
    TIM2->CR1 = TIM_CR1_CEN;
    NVIC_SetPriority(TIM2_IRQn, 1);
    NVIC_EnableIRQ(TIM2_IRQn);
}

void TIM2_IRQHandler(void)
{
    if (!(TIM2->SR & TIM_SR_CC2IF)) { TIM2->SR = 0; return; }
    uint16_t v = TIM2->CCR2;   // reading clears CC2IF
    TIM2->SR = 0;
    if (state == WAIT_RISE) {
        t_rise = v;
        TIM2->CCER |= TIM_CCER_CC2P;      // now catch the falling edge
        state = WAIT_FALL;
    } else if (state == WAIT_FALL) {
        width = (uint16_t)(v - t_rise);
        TIM2->CCER &= ~TIM_CCER_CC2P;
        state = DONE;
    }
}

void ultrasonic_service(void)
{
    uint32_t now = millis();
    if (state == DONE) {
        uint32_t cm = (uint32_t)width * 1715u / 100000u;   // 343 m/s, round trip
        us_cm = cm > US_NO_ECHO ? US_NO_ECHO : (uint16_t)cm;
        us_seq++;
        state = IDLE;
    } else if ((state == WAIT_RISE || state == WAIT_FALL) && now - t_start >= TIMEOUT_MS) {
        __disable_irq();
        TIM2->CCER &= ~TIM_CCER_CC2P;
        state = IDLE;
        __enable_irq();
        us_cm = US_NO_ECHO;
        us_seq++;
    }
    if (state == IDLE && now - t_start >= PERIOD_MS) {
        t_start = now;
        TIM2->CCER &= ~TIM_CCER_CC2P;
        state = WAIT_RISE;
        gpio_write(GPIOA, 0, 1);
        delay_us(12);
        gpio_write(GPIOA, 0, 0);
    }
}
