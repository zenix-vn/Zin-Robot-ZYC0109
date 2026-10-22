#include "horn.h"
#include "board.h"

#define SAMPLE_HZ   20000
#define TONE_LOW    420
#define TONE_HIGH   500
#define HORN_MIN_MS 180    // a quick tap still gives a proper short honk

#define PHASE_INC(f) ((uint32_t)((f) * 4294967296.0 / SAMPLE_HZ))

// signal patterns: on, off, on, off ... ms, 0-terminated
const uint16_t HORN_CHIRP[]   = { 90, 60, 0 };
const uint16_t HORN_DOUBLE[]  = { 110, 90, 170, 60, 0 };
const uint16_t HORN_TRIPLE[]  = { 100, 80, 100, 80, 100, 300, 0 };
const uint16_t HORN_LONG[]    = { 450, 100, 0 };
const uint16_t HORN_REVERSE[] = { 160, 340, 0 };
const uint16_t HORN_SIREN[]   = { 380, 20, 380, 20, 0 };   // alarm: high / low wail

#define SIREN_HIGH 950
#define SIREN_LOW  650

static uint32_t ph_lo, ph_hi;
static volatile uint32_t inc_lo = PHASE_INC(TONE_LOW), inc_hi = PHASE_INC(TONE_HIGH);
static uint8_t running, user, release_pending;
static uint32_t start_ms;
static const uint16_t *seq;
static uint8_t seq_i;
static uint32_t seq_t;

static void buzzer(int on) { gpio_write(GPIOB, 1, !on); }   // low = sounding

void horn_init(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPBEN | RCC_APB2ENR_TIM1EN;
    gpio_cfg(GPIOB, 1, CFG_OUT_PP);
    buzzer(0);
    TIM1->PSC = 0;
    TIM1->ARR = SystemCoreClock / SAMPLE_HZ - 1;
    TIM1->DIER = TIM_DIER_UIE;
    NVIC_SetPriority(TIM1_UP_IRQn, 3);   // below the UART
    NVIC_EnableIRQ(TIM1_UP_IRQn);
}

static void tone_on(void)
{
    if (running) return;
    ph_lo = ph_hi = 0;
    running = 1;
    TIM1->CNT = 0;
    TIM1->CR1 |= TIM_CR1_CEN;
}

static void tone_off(void)
{
    TIM1->CR1 &= ~TIM_CR1_CEN;
    running = 0;
    buzzer(0);
}

static void set_tones(uint32_t lo, uint32_t hi) { inc_lo = lo; inc_hi = hi; }

void horn_off(void)
{
    user = release_pending = 0;
    seq = 0;
    tone_off();
}

void horn_set(int on)
{
    if (on) {
        seq = 0;   // the driver's horn wins over any signal pattern
        tone_off();
        set_tones(PHASE_INC(TONE_LOW), PHASE_INC(TONE_HIGH));
        if (!user) start_ms = millis();
        user = 1;
        release_pending = 0;
        tone_on();
    } else if (user) {
        release_pending = 1;
    }
}

/** The siren is one tone at a time, switching pitch on every "on" part of its pattern. */
static void pattern_tones(void)
{
    if (seq != HORN_SIREN) set_tones(PHASE_INC(TONE_LOW), PHASE_INC(TONE_HIGH));
    else if (seq_i & 2) set_tones(PHASE_INC(SIREN_LOW), PHASE_INC(SIREN_LOW));
    else set_tones(PHASE_INC(SIREN_HIGH), PHASE_INC(SIREN_HIGH));
}

void horn_play(const uint16_t *pattern)
{
    if (user) return;
    seq = pattern;
    seq_i = 0;
    seq_t = millis();
    pattern_tones();
    tone_on();
}

int horn_active(void) { return user; }
int horn_busy(void) { return user || seq; }

void horn_tick(void)
{
    uint32_t now = millis();
    if (release_pending && now - start_ms >= HORN_MIN_MS) horn_off();
    if (seq && now - seq_t >= seq[seq_i]) {
        seq_t = now;
        if (!seq[++seq_i]) { seq = 0; tone_off(); }
        else if (seq_i & 1) tone_off();
        else { pattern_tones(); tone_on(); }
    }
}

// Two square waves mixed (OR): the buzzer sounds whenever either tone is in its high half,
// so the output carries both pitches plus their 80 Hz beat, like a dual-tone car horn.
void TIM1_UP_IRQHandler(void)
{
    TIM1->SR = ~TIM_SR_UIF;
    ph_lo += inc_lo;
    ph_hi += inc_hi;
    buzzer((ph_lo | ph_hi) >> 31);
}
