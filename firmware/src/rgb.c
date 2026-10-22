#include "rgb.h"
#include "board.h"

#define T_BIT 90   // 1.25 us @ 72 MHz
#define T_0H  25   // 0.35 us
#define T_1H  58   // 0.8 us

void rgb_init(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPBEN;
    gpio_cfg(GPIOB, 0, CFG_OUT_PP);
    gpio_write(GPIOB, 0, 0);
}

// Interrupts are held off for one LED (30 us) at a time only: between LEDs the line idles low for
// a few us, far below the 50 us latch time, so a frame survives while the UART never loses a byte.
static void send(uint32_t grb)
{
    __disable_irq();
    for (int i = 23; i >= 0; i--) {
        uint32_t hi = (grb >> i) & 1 ? T_1H : T_0H;
        uint32_t t = DWT->CYCCNT;
        GPIOB->BSRR = 1u << 0;
        while (DWT->CYCCNT - t < hi);
        GPIOB->BSRR = 1u << 16;
        while (DWT->CYCCNT - t < T_BIT);
    }
    __enable_irq();
}

void rgb_fill(uint8_t r, uint8_t g, uint8_t b)
{
    uint32_t grb = ((uint32_t)g << 16) | ((uint32_t)r << 8) | b;
    for (int i = 0; i < RGB_COUNT; i++) send(grb);
    delay_us(300);   // latch (newer WS2812B need 280 us)
}

void rgb_show(const uint8_t *rgb)
{
    for (int i = 0; i < RGB_COUNT; i++, rgb += 3)
        send(((uint32_t)rgb[1] << 16) | ((uint32_t)rgb[0] << 8) | rgb[2]);
    delay_us(300);
}
