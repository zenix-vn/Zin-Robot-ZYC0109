#pragma once
#include <stdint.h>

#define LINE_L 1
#define LINE_M 2
#define LINE_R 4

void line_init(void);
void line_tick(void);      // call every 10 ms, debounces the 3 sensors
uint8_t line_state(void);  // bit set = black line under that sensor
