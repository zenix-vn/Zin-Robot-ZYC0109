#include "rgb.h"
#include "board.h"

#define LED_COUNT 40
#define T_BIT 90   // 1.25 us @ 72 MHz
#define T_0H  25   // 0.35 us
#define T_1H  58   // 0.8 us

void rgb_init(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPBEN;
    gpio_cfg(GPIOB, 0, CFG_OUT_PP);
    gpio_write(GPIOB, 0, 0);
}

static void send(uint32_t grb)
{
    for (int i = 23; i >= 0; i--) {
        uint32_t hi = (grb >> i) & 1 ? T_1H : T_0H;
        uint32_t t = DWT->CYCCNT;
        GPIOB->BSRR = 1u << 0;
        while (DWT->CYCCNT - t < hi);
        GPIOB->BSRR = 1u << 16;
        while (DWT->CYCCNT - t < T_BIT);
    }
}

void rgb_fill(uint8_t r, uint8_t g, uint8_t b)
{
    uint32_t grb = ((uint32_t)g << 16) | ((uint32_t)r << 8) | b;
    __disable_irq();
    for (int i = 0; i < LED_COUNT; i++) send(grb);
    __enable_irq();
    delay_us(80);   // latch
}
