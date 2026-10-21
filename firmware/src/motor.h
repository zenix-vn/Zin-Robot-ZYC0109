#pragma once
#include <stdint.h>

void motor_init(void);
void motor_set(int left, int right);   // -100..100, ramped; direction reversal passes through 0
void motor_stop(void);                 // immediate stop
void motor_tick(void);                 // call every 10 ms
int  motor_current(int side);          // 0 = left, 1 = right: duty actually applied, -100..100
