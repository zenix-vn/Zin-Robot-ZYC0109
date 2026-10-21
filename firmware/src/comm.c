#include "comm.h"
#include "board.h"

#define RX_SIZE 128
#define TX_SIZE 256

static volatile uint8_t rx[RX_SIZE];
static volatile uint16_t rx_head, rx_tail;
static volatile uint8_t tx[TX_SIZE];
static volatile uint16_t tx_head, tx_tail;

void comm_init(void)
{
    RCC->APB2ENR |= RCC_APB2ENR_IOPAEN | RCC_APB2ENR_USART1EN;
    gpio_cfg(GPIOA, 9, CFG_AF_PP);
    gpio_cfg(GPIOA, 10, CFG_IN_PULL);
    GPIOA->BSRR = 1u << 10;   // RX pull-up

    USART1->BRR = (SystemCoreClock + 4800) / 9600;
    USART1->CR1 = USART_CR1_UE | USART_CR1_TE | USART_CR1_RE | USART_CR1_RXNEIE;
    NVIC_SetPriority(USART1_IRQn, 0);
    NVIC_EnableIRQ(USART1_IRQn);
}

void USART1_IRQHandler(void)
{
    uint32_t sr = USART1->SR;
    if (sr & (USART_SR_RXNE | USART_SR_ORE)) {
        uint8_t b = USART1->DR;   // reading DR clears RXNE/ORE
        if (!(sr & (USART_SR_FE | USART_SR_NE))) {   // drop corrupted frames
            uint16_t n = (rx_head + 1) % RX_SIZE;
            if (n != rx_tail) { rx[rx_head] = b; rx_head = n; }
        }
    }
    if ((USART1->CR1 & USART_CR1_TXEIE) && (sr & USART_SR_TXE)) {
        if (tx_head != tx_tail) {
            USART1->DR = tx[tx_tail];
            tx_tail = (tx_tail + 1) % TX_SIZE;
        } else {
            USART1->CR1 &= ~USART_CR1_TXEIE;
        }
    }
}

int comm_getc(void)
{
    if (rx_head == rx_tail) return -1;
    uint8_t b = rx[rx_tail];
    rx_tail = (rx_tail + 1) % RX_SIZE;
    return b;
}

void comm_puts(const char *s)
{
    while (*s) {
        uint16_t n = (tx_head + 1) % TX_SIZE;
        if (n == tx_tail) break;   // buffer full: drop the rest rather than block
        tx[tx_head] = (uint8_t)*s++;
        tx_head = n;
    }
    USART1->CR1 |= USART_CR1_TXEIE;
}
