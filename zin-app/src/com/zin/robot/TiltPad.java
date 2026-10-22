package com.zin.robot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Tilt steering: hold the pad and tilt the phone. Pressing it takes the current attitude as neutral,
 * so any comfortable grip works; letting go stops the car (dead-man switch).
 * Tilt forward / back = throttle, tilt left / right = steering (+ = left), both -100..100.
 */
class TiltPad extends View implements SensorEventListener {
    interface Listener { void onTilt(boolean active); }

    private static final float FULL_DEG = 25f, DEAD_DEG = 4f;

    private final SensorManager sm;
    private final Sensor gravity;
    private final Paint pad = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float gx, gy, gz = 9.8f;
    private float pitch0, roll0;
    private boolean active, listening;
    private int throttle, steer;
    private Listener listener;

    TiltPad(Context c) {
        super(c);
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor g = sm != null ? sm.getDefaultSensor(Sensor.TYPE_GRAVITY) : null;
        gravity = g != null ? g : (sm != null ? sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 1));
        line.setColor(0x33FFFFFF);
        label.setColor(Ui.MUTED);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Ui.dp(c, 12));
        label.setTypeface(Ui.MEDIUM);
    }

    void setListener(Listener l) { listener = l; }
    boolean isActive() { return active; }
    int throttle() { return throttle; }
    int steer() { return steer; }
    boolean available() { return gravity != null; }

    /** Sensor only while the pad is on screen. */
    void listen(boolean on) {
        if (sm == null || gravity == null || on == listening) return;
        listening = on;
        if (on) sm.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME);
        else { sm.unregisterListener(this); release(); }
    }

    private float pitchDeg() { return (float) Math.toDegrees(Math.atan2(gy, gz)); }
    private float rollDeg() { return (float) Math.toDegrees(Math.atan2(gx, Math.sqrt(gy * gy + gz * gz))); }

    private static int axis(float deg) {
        if (Math.abs(deg) < DEAD_DEG) return 0;
        float v = (Math.abs(deg) - DEAD_DEG) / (FULL_DEG - DEAD_DEG) * 100f;
        return (int) Math.signum(deg) * (int) Math.min(100f, v);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        float k = 0.25f;   // light smoothing against hand tremor
        gx += k * (e.values[0] - gx);
        gy += k * (e.values[1] - gy);
        gz += k * (e.values[2] - gz);
        if (active) {
            throttle = axis(pitch0 - pitchDeg());   // top edge tipped away = forward
            steer = axis(rollDeg() - roll0);         // left edge down = left
        }
        invalidate();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private void release() {
        boolean was = active;
        active = false;
        throttle = steer = 0;
        invalidate();
        if (was && listener != null) listener.onTilt(false);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (gravity == null) return true;
                getParent().requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                pitch0 = pitchDeg();
                roll0 = rollDeg();
                active = true;
                throttle = steer = 0;
                if (listener != null) listener.onTilt(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
        }
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), m = Ui.dp(getContext(), 6);
        float rad = Ui.dp(getContext(), 22);
        r.set(m, m, w - m, h - m);
        pad.setColor(active ? Ui.withAlpha(Ui.ACCENT, 60) : 0x14FFFFFF);
        c.drawRoundRect(r, rad, rad, pad);
        line.setColor(active ? Ui.withAlpha(Ui.ACCENT, 200) : 0x33FFFFFF);
        c.drawRoundRect(r, rad, rad, line);
        float cx = w / 2, cy = h / 2;
        line.setColor(0x26FFFFFF);
        c.drawLine(cx, m + rad, cx, h - m - rad, line);
        c.drawLine(m + rad, cy, w - m - rad, cy, line);

        float bx = cx - steer / 100f * (w / 2 - m - rad), by = cy - throttle / 100f * (h / 2 - m - rad);
        bubble.setColor(active ? Ui.CYAN : 0x55FFFFFF);
        c.drawCircle(bx, by, Ui.dp(getContext(), active ? 16 : 12), bubble);

        if (gravity == null) { c.drawText("Máy không có cảm biến nghiêng", cx, h - m - Ui.dp(getContext(), 12), label); return; }
        String s = active ? "Ga " + throttle + " · lái " + (steer > 0 ? "trái " : steer < 0 ? "phải " : "") + Math.abs(steer)
                          : "Giữ ở đây và nghiêng máy để lái";
        c.drawText(s, cx, h - m - Ui.dp(getContext(), 12), label);
    }
}
