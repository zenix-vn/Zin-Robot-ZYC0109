#include "line.h"
#include "board.h"

static uint8_t stable, last, count;

void line_init(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPCEN;
    for (int p = 13; p <= 15; p++) {
        gpio_cfg(GPIOC, p, CFG_IN_PULL);
        GPIOC->BSRR = 1u << p;   // pull-up
    }
}

static uint8_t sample(void)
{
    uint32_t in = GPIOC->IDR;
    return ((in >> 13) & 1) | (((in >> 14) & 1) << 1) | (((in >> 15) & 1) << 2);
}

void line_tick(void)
{
    uint8_t s = sample();
    if (s == last) { if (count < 3) count++; } else { last = s; count = 0; }
    if (count >= 2) stable = s;   // same reading for 3 ticks
}

uint8_t line_state(void) { return stable; }
