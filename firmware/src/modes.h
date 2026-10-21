#pragma once
#include <stdint.h>

// Protocol (app -> robot), one command per line: '#' <letter> [int[,int...]] '\n'
//   #D t,s     drive: throttle -100..100, steer -100..100 (+ = left); servo + differential mix
//   #M l,r     tank: left / right side -100..100, independent, steering centred
//   #S         stop moving: wheels straight (also leaves automatic modes)
//   #R         rest pose (app STOP button): manual mode, stopped, steering and camera centred
//   #H         heartbeat (keeps telemetry flowing while idle)
//   #A n       mode: 0 manual, 1 obstacle avoidance, 2 line following, 3 follow
//   #P a       camera / ultrasonic pan angle 15..175 (manual mode)
//   #B 0|1     horn
//   #G r,g,b   headlight colour (manual mode RGB matrix)
//   #Q         radar sweep: stops, pans the ultrasonic 170..20 deg and reports every 10 deg
//   #C max,tl,tr[,st]  max speed 20..100 %, left / right trim -30..30 %, steering centre -15..15 deg
// Telemetry (robot -> app), every 200 ms while the link is alive:
//   #T mode,dist_cm,flags,motor_l,motor_r,steer_deg,pan_deg
//   flags: bit0-2 line sensors L/M/R (1 = black), bit3 collision guard active, bit4 line lost,
//          bit5 obstacle avoidance is scanning / turning to find a way
//          bit6 radar sweep in progress
//   #R offset,cm   one radar reading (offset from straight ahead, + = left; 400 = nothing)
//   #E             radar sweep finished
// Driving commands must keep coming (app sends them at 10 Hz): after 600 ms of silence the robot stops.

void modes_init(void);
void modes_rx(uint8_t b);        // feed every received byte
void modes_tick(void);           // call every 10 ms
void modes_telemetry(void);      // call every 200 ms
