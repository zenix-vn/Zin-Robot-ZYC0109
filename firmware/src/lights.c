#include "lights.h"
#include "rgb.h"
#include "board.h"

#define BLINK_MS  350
#define POLICE_MS 150

static uint8_t base[3] = { 8, 8, 8 };
static uint8_t layout, lflags;
static uint8_t shown[RGB_COUNT * 3];
static uint8_t force = 1;

void lights_base(uint8_t r, uint8_t g, uint8_t b)
{
    base[0] = r;
    base[1] = g;
    base[2] = b;
    force = 1;
}

void lights_layout(int l, int f)
{
    layout = (uint8_t)(l >= 0 && l <= 2 ? l : 0);
    lflags = (uint8_t)(f & 7);
    force = 1;
}

/** Where LED i sits: side -1 left, 0 middle column, +1 right; bottom = in the lowest row. */
static void place(int i, int *side, int *bottom)
{
    int w, h, x, y;
    if (layout == 2) {   // strip: no rows, halves are the sides, the whole strip is the "bottom"
        x = i < RGB_COUNT / 2 ? 0 : 1;
        w = 2;
        h = 1;
        y = 0;
    } else {
        w = layout == 0 ? 8 : 5;
        h = RGB_COUNT / w;
        x = i % w;
        y = i / w;
        if ((lflags & 4) && (y & 1)) x = w - 1 - x;
    }
    if (lflags & 1) x = w - 1 - x;
    if (lflags & 2) y = h - 1 - y;
    *side = 2 * x + 1 < w ? -1 : (2 * x + 1 > w ? 1 : 0);
    *bottom = y == h - 1;
}

static void put(uint8_t *p, uint8_t r, uint8_t g, uint8_t b) { p[0] = r; p[1] = g; p[2] = b; }

void lights_render(uint8_t sig)
{
    static uint8_t frame[RGB_COUNT * 3];
    uint32_t now = millis();
    int blink_on = (now / BLINK_MS) & 1;
    int police_left = (now / POLICE_MS) & 1;

    for (int i = 0; i < RGB_COUNT; i++) {
        uint8_t *p = frame + 3 * i;
        int side, bottom;
        place(i, &side, &bottom);
        put(p, base[0], base[1], base[2]);
        if (sig & LT_POLICE) {
            if (side < 0) put(p, police_left ? 70 : 0, 0, 0);
            else if (side > 0) put(p, 0, 0, police_left ? 0 : 70);
            else put(p, 0, 0, 0);
            continue;
        }
        if (bottom && (sig & LT_REVERSE)) put(p, 40, 40, 40);
        else if (bottom && (sig & LT_BRAKE)) put(p, 70, 0, 0);
        if ((side < 0 && (sig & LT_LEFT)) || (side > 0 && (sig & LT_RIGHT)))
            put(p, blink_on ? 70 : 0, blink_on ? 26 : 0, 0);
    }

    int same = !force;
    for (int i = 0; same && i < RGB_COUNT * 3; i++) same = frame[i] == shown[i];
    if (same) return;
    for (int i = 0; i < RGB_COUNT * 3; i++) shown[i] = frame[i];
    force = 0;
    rgb_show(frame);
}
