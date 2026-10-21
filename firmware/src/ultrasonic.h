#pragma once
#include <stdint.h>

#define US_NO_ECHO 400   // reported when nothing echoes back in time

void ultrasonic_init(void);
void ultrasonic_service(void);       // call from the main loop; starts/finishes measurements
extern volatile uint16_t us_cm;      // latest distance, cm
extern volatile uint32_t us_seq;     // increments on every completed measurement
