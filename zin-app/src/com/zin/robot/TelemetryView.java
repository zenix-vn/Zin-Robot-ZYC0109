package com.zin.robot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Live power bars for the two motor sides (centre = stopped, right = forward) plus steering / camera angles. */
class TelemetryView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean valid;
    private int left, right, steer, pan;

    TelemetryView(Context c) {
        super(c);
        track.setColor(0x1AFFFFFF);
        tick.setColor(0x55FFFFFF);
        label.setColor(Ui.MUTED);
        label.setTextSize(Ui.dp(c, 12));
        label.setTypeface(Ui.MEDIUM);
        value.setColor(Ui.TEXT);
        value.setTextSize(Ui.dp(c, 12));
        value.setTypeface(Ui.MEDIUM);
        value.setTextAlign(Paint.Align.RIGHT);
    }

    void set(int l, int rgt, int st, int p) {
        valid = true;
        left = l; right = rgt; steer = st; pan = p;
        invalidate();
    }

    void clear() { valid = false; invalidate(); }

    private void bar(Canvas c, float y, String name, int v) {
        float lw = Ui.dp(getContext(), 64), vw = Ui.dp(getContext(), 46), h = Ui.dp(getContext(), 8);
        float x0 = lw, x1 = getWidth() - vw, cx = (x0 + x1) / 2;
        c.drawText(name, 0, y + h / 2 - (label.descent() + label.ascent()) / 2, label);
        r.set(x0, y, x1, y + h);
        c.drawRoundRect(r, h / 2, h / 2, track);
        if (valid && v != 0) {
            float end = cx + (x1 - x0) / 2 * Math.max(-100, Math.min(100, v)) / 100f;
            r.set(Math.min(cx, end), y, Math.max(cx, end), y + h);
            fill.setColor(v > 0 ? Ui.CYAN : Ui.ORANGE);
            c.drawRoundRect(r, h / 2, h / 2, fill);
        }
        c.drawRect(cx - 1, y - Ui.dp(getContext(), 3), cx + 1, y + h + Ui.dp(getContext(), 3), tick);
        c.drawText(valid ? v + "%" : "--", getWidth(), y + h / 2 - (value.descent() + value.ascent()) / 2, value);
    }

    @Override protected void onDraw(Canvas c) {
        float row = Ui.dp(getContext(), 22);
        bar(c, Ui.dp(getContext(), 4), "Motor trái", left);
        bar(c, Ui.dp(getContext(), 4) + row, "Motor phải", right);
        float y = Ui.dp(getContext(), 4) + 2 * row + Ui.dp(getContext(), 8);
        String s = valid ? "Bánh lái " + steer + "°    ·    Camera " + pan + "°" : "Chưa có dữ liệu từ robot";
        label.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, getWidth() / 2f, y, label);
        label.setTextAlign(Paint.Align.LEFT);
    }
}
