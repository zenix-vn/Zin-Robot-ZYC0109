#pragma once
#include <stdint.h>

#define RGB_COUNT 40

void rgb_init(void);
void rgb_fill(uint8_t r, uint8_t g, uint8_t b);   // all LEDs one colour, ~1.5 ms
void rgb_show(const uint8_t *rgb);                // RGB_COUNT x {r, g, b}
