#pragma once

void comm_init(void);
int  comm_getc(void);              // next received byte, or -1
void comm_puts(const char *s);     // queued, interrupt driven; drops data if the buffer is full
