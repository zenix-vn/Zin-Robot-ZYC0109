// Board support: pin map of the TSCINBUNY ZYC0109 car (STM32F103 Blue Pill + expansion board)
#pragma once
#include "stm32f1xx.h"
#include <stdint.h>

// Pin map (from the vendor reference code)
//  Motor L : PWM PB7 (TIM4_CH2), dir PB8/PB9      Motor R : PWM PB6 (TIM4_CH1), dir PA4/PB5
//  Servos  : steering PA6 (TIM3_CH1), ultrasonic pan PA7 (TIM3_CH2)
//  HC-SR04 : Trig PA0, Echo PA1 (TIM2_CH2)         Line    : PC13 left, PC14 middle, PC15 right
//  UART1   : PA9 TX, PA10 RX @9600 (Bluetooth / ESP32-CAM, shared by a switch)
//  Buzzer  : PB1 (low = sounding)                  RGB     : WS2812B x40 on PB0

#define CFG_IN_FLOAT   0x4u
#define CFG_IN_PULL    0x8u   // pull direction chosen through ODR
#define CFG_OUT_PP     0x3u   // 50 MHz
#define CFG_AF_PP      0xBu   // 50 MHz

static inline void gpio_cfg(GPIO_TypeDef *g, uint8_t pin, uint32_t cfg)
{
    volatile uint32_t *r = pin < 8 ? &g->CRL : &g->CRH;
    uint8_t s = (pin & 7) * 4;
    *r = (*r & ~(0xFu << s)) | (cfg << s);
}

static inline void gpio_write(GPIO_TypeDef *g, uint8_t pin, int level)
{
    g->BSRR = level ? (1u << pin) : (1u << (pin + 16));
}

uint32_t millis(void);
void delay_us(uint32_t us);
