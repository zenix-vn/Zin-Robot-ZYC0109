#pragma once
#include <stdint.h>

// Protocol (app -> robot), one command per line: '#' <letter> [int[,int...]] '\n'
//   #D t,s     drive: throttle -100..100, steer -100..100 (+ = left); servo + differential mix
//   #M l,r     tank: left / right side -100..100, independent, steering centred
//   #S         stop moving: wheels straight (also leaves automatic modes)
//   #R         rest pose (app STOP button): manual mode, stopped, steering and camera centred
//   #H         heartbeat (keeps telemetry flowing while idle)
//   #A n       mode: 0 manual, 1 obstacle avoidance, 2 line following, 3 follow, 4 patrol (guard)
//   #P a       camera / ultrasonic pan angle 15..175 (manual mode)
//   #B 0|1     horn (dual-tone; a short tap still honks for 180 ms)
//   #G r,g,b   headlight colour (manual mode RGB matrix)
//   #Q         radar sweep: stops, pans the ultrasonic 170..20 deg and reports every 10 deg
//   #F lvl,fx[,auto]  camera flash: brightness 0..100 %, effect 0 steady, 1 blink, 2 breathe, 3 strobe.
//              auto = 1: the robot picks the effect itself from mode / situation at that brightness.
//              Sent on as "#F lvl,fx" on TX, where the ESP32-CAM is listening (works without Wi-Fi)
//   #K 0|1     automatic horn signals in the autonomous modes (see "self-driving signals" in modes.c)
//   #X n       dance move on a music beat (manual mode): 0/1 wiggle L/R, 2/3 hop fwd/back, 4/5 look L/R
//   #J 0|1     turn / brake / reversing lights on the RGB matrix
//   #L lay,fl  RGB matrix layout: 0 8x5, 1 5x8, 2 strip; flags 1 mirror, 2 flip, 4 serpentine
//   #Y n       light test for 3 s: 1 left, 2 right, 3 brake, 4 hazard, 5 reversing, 6 police
//   #C max,tl,tr[,st]  max speed 20..100 %, left / right trim -30..30 %, steering centre -15..15 deg
// Telemetry (robot -> app), every 200 ms while the link is alive:
//   #T mode,dist_cm,flags,motor_l,motor_r,steer_deg,pan_deg
//   flags: bit0-2 line sensors L/M/R (1 = black), bit3 collision guard active, bit4 line lost,
//          bit5 obstacle avoidance is scanning / turning to find a way
//          bit6 radar sweep in progress, bit7 stuck (rescuing with bit5, gave up without)
//          bit8 patrol alarm (bit5 while patrol is still learning the room)
//   #R offset,cm   one radar reading (offset from straight ahead, + = left; 400 = nothing)
//   #E             radar sweep finished
//   #W offset,cm   patrol alarm: something changed at that pan offset (+ = left), now at cm
// Driving commands must keep coming (app sends them at 10 Hz): after 600 ms of silence the robot stops.

void modes_init(void);
void modes_rx(uint8_t b);        // feed every received byte
void modes_tick(void);           // call every 10 ms
void modes_telemetry(void);      // call every 200 ms
