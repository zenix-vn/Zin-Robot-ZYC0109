#include "board.h"
#include "motor.h"
#include "servo.h"
#include "ultrasonic.h"
#include "line.h"
#include "comm.h"
#include "rgb.h"
#include "modes.h"

#ifndef SELFTEST
#define SELFTEST 0   // build env "selftest": bench test of every peripheral, no Bluetooth needed
#endif
void selftest_run(void);

static volatile uint32_t g_ms;

uint32_t millis(void) { return g_ms; }
void SysTick_Handler(void) { g_ms++; }

void delay_us(uint32_t us)
{
    uint32_t t = DWT->CYCCNT, n = us * (SystemCoreClock / 1000000u);
    while (DWT->CYCCNT - t < n);
}

static void clock_init(void)
{
    RCC->CR |= RCC_CR_HSEON;
    for (uint32_t i = 0; i < 200000 && !(RCC->CR & RCC_CR_HSERDY); i++);
    if (!(RCC->CR & RCC_CR_HSERDY)) return;   // crystal missing: stay on the 8 MHz HSI

    FLASH->ACR = FLASH_ACR_PRFTBE | FLASH_ACR_LATENCY_2;
    RCC->CFGR = RCC_CFGR_PLLSRC | RCC_CFGR_PLLMULL9 | RCC_CFGR_PPRE1_DIV2;   // 8 MHz * 9 = 72 MHz
    RCC->CR |= RCC_CR_PLLON;
    while (!(RCC->CR & RCC_CR_PLLRDY));
    RCC->CFGR |= RCC_CFGR_SW_PLL;
    while ((RCC->CFGR & RCC_CFGR_SWS) != RCC_CFGR_SWS_PLL);
    SystemCoreClock = 72000000;
}

static void watchdog_init(void)
{
    IWDG->KR = 0x5555;
    IWDG->PR = 3;        // /32 -> 1.25 kHz
    IWDG->RLR = 1250;    // ~1 s
    IWDG->KR = 0xAAAA;
    IWDG->KR = 0xCCCC;
}

int main(void)
{
    clock_init();
    CoreDebug->DEMCR |= CoreDebug_DEMCR_TRCENA_Msk;
    DWT->CYCCNT = 0;
    DWT->CTRL |= DWT_CTRL_CYCCNTENA_Msk;
    SysTick_Config(SystemCoreClock / 1000);

    motor_init();
    servo_init();
    ultrasonic_init();
    line_init();
    comm_init();
    rgb_init();
    modes_init();
    watchdog_init();
#if SELFTEST
    selftest_run();
#endif

    uint32_t next = millis() + 10, next_tm = millis() + 200;
    for (;;) {
        IWDG->KR = 0xAAAA;
        ultrasonic_service();
        int c;
        while ((c = comm_getc()) >= 0) modes_rx((uint8_t)c);

        if ((int32_t)(millis() - next) >= 0) {
            next += 10;
            line_tick();
            modes_tick();
            motor_tick();
            servo_tick();
        }
        if ((int32_t)(millis() - next_tm) >= 0) {
            next_tm += 200;
            modes_telemetry();
        }
    }
}
