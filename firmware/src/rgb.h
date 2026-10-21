#pragma once
#include <stdint.h>

void rgb_init(void);
void rgb_fill(uint8_t r, uint8_t g, uint8_t b);   // all 40 LEDs, blocks ~1.3 ms
