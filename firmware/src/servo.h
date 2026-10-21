#pragma once
#include <stdint.h>

enum { SERVO_STEER = 0, SERVO_PAN = 1 };

void servo_init(void);
void servo_set(int ch, int angle, int slew);   // target angle (deg), slew in deg per 10 ms tick
int  servo_angle(int ch);                      // current angle
void servo_tick(void);                         // call every 10 ms
