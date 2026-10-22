#pragma once
#include <stdint.h>

// Horn on the PB1 buzzer: instead of a flat beep, TIM1 gates it with two tones a car horn uses
// (about 420 + 500 Hz, the classic low/high horn pair), which gives the rough "hoonk" timbre.
void horn_init(void);
void horn_set(int on);   // driver's horn; a tap still sounds for at least HORN_MIN_MS
void horn_off(void);     // immediately, patterns included (rest pose)
void horn_play(const uint16_t *pattern);   // signal pattern, ignored while the driver's horn is held
void horn_tick(void);    // call every 10 ms
int  horn_active(void);  // driver's horn sounding
int  horn_busy(void);    // driver's horn or a pattern sounding

extern const uint16_t HORN_CHIRP[], HORN_DOUBLE[], HORN_TRIPLE[], HORN_LONG[], HORN_REVERSE[],
                      HORN_SIREN[];
