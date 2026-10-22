#pragma once
#include <stdint.h>

// Car lights on the 40-LED RGB matrix: a base colour (headlight / mode colour) with turn signals,
// hazard, brake and reversing lights, or police flashing, drawn over it.
// The physical arrangement of the LEDs is not known for sure, so it is configurable (#L):
//   layout 0: 8 columns x 5 rows, 1: 5 columns x 8 rows, 2: plain strip (first half = left)
//   flags bit0 mirror left/right, bit1 flip top/bottom, bit2 serpentine (every other row reversed)

enum {
    LT_LEFT    = 0x01,   // left half blinks amber
    LT_RIGHT   = 0x02,   // right half blinks amber (both = hazard)
    LT_BRAKE   = 0x04,   // bottom row red
    LT_REVERSE = 0x08,   // bottom row white
    LT_POLICE  = 0x10,   // alarm: halves flash red / blue
};

void lights_base(uint8_t r, uint8_t g, uint8_t b);
void lights_layout(int layout, int flags);
void lights_render(uint8_t sig);   // call every 10 ms; writes the LEDs only when the picture changes
