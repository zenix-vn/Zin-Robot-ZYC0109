// Shared between main.c (camera, Wi-Fi, flash LED, STM32 UART) and pad.c (Bluetooth gamepad).
#pragma once
#include <stdbool.h>
#include <stdint.h>

// ---- main.c
uint32_t millis(void);
void flash_set(int level, int fx);
int flash_level_get(void);

// STM32 link on UART0 (9600). Our TX only reaches the STM32 with switch S4 on, and it is the same line the
// BT05 drives with S3 on, so it is only driven while a gamepad is connected.
void stm_tx_enable(bool on);
void stm_send(const char *frame);              // "#D50,0" -> adds '\n'; dropped while TX is off
int stm_read(uint8_t *buf, int max);           // non-blocking

// motor calibration from the phone app (http /config), stored in NVS
typedef struct { int trim_l, trim_r, steer_trim; } calib_t;
calib_t calib_get(void);

// ---- pad.c (all called on the BTstack thread except pad_status)
void pad_start(void);                          // never returns: runs the Bluetooth loop
void pad_status(char *out, int max);           // human-readable state for http /status
void pad_forget(void);                         // drop stored pairings, scan again
void pad_calib_changed(void);
