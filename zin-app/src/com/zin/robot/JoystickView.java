package com.zin.robot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Virtual stick drawn on a canvas. Reports x (right +) and y (up +) in -100..100 and springs back
 * to the centre on release. With axis VERTICAL it becomes a spring-loaded slider (one motor side).
 */
public class JoystickView extends View {
    public interface Listener { void onStick(JoystickView v, int x, int y, boolean active); }

    public static final int BOTH = 0, VERTICAL = 1;

    private final int axis;
    private final String label;
    private final int color;
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevron = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private float kx, ky;          // knob offset, -1..1
    private boolean active;
    private Listener listener;

    public JoystickView(Context c, int axis, String label, int color) {
        super(c);
        this.axis = axis;
        this.label = label;
        this.color = color;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 1.5f));
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(Ui.dp(c, 1));
        guide.setColor(0x1FFFFFFF);
        chevron.setStyle(Paint.Style.STROKE);
        chevron.setStrokeWidth(Ui.dp(c, 2));
        chevron.setStrokeCap(Paint.Cap.ROUND);
        chevron.setStrokeJoin(Paint.Join.ROUND);
        chevron.setColor(0x59FFFFFF);
        shine.setColor(0x40FFFFFF);
        labelPaint.setColor(Ui.MUTED);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(Ui.dp(c, 11));
        labelPaint.setTypeface(Ui.MEDIUM);
        labelPaint.setLetterSpacing(0.12f);
    }

    public void setListener(Listener l) { listener = l; }

    public int stickX() { return Math.round(kx * 100); }
    public int stickY() { return Math.round(-ky * 100); }
    public boolean isActive() { return active; }

    private float knobRadius() {
        return axis == VERTICAL ? getWidth() * 0.30f : Math.min(getWidth(), getHeight()) * 0.16f;
    }

    private float travel() {
        float pad = Ui.dp(getContext(), axis == VERTICAL ? 20 : 8);
        return (axis == VERTICAL ? getHeight() / 2f : Math.min(getWidth(), getHeight()) / 2f) - knobRadius() - pad;
    }

    /** Small "v" pointing at angle (radians), centred at x,y. */
    private void drawChevron(Canvas c, float x, float y, double angle) {
        float s = Ui.dp(getContext(), 5);
        double a1 = angle + Math.PI * 0.75, a2 = angle - Math.PI * 0.75;
        float tx = x + (float) Math.cos(angle) * s * 0.6f, ty = y + (float) Math.sin(angle) * s * 0.6f;
        path.reset();
        path.moveTo(tx + (float) Math.cos(a1) * s, ty + (float) Math.sin(a1) * s);
        path.lineTo(tx, ty);
        path.lineTo(tx + (float) Math.cos(a2) * s, ty + (float) Math.sin(a2) * s);
        c.drawPath(path, chevron);
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float kr = knobRadius(), t = travel();
        ring.setColor(active ? Ui.withAlpha(color, 170) : 0x26FFFFFF);

        if (axis == VERTICAL) {
            float w = getWidth() * 0.62f;
            float top = cy - t - kr - Ui.dp(getContext(), 4), bottom = cy + t + kr + Ui.dp(getContext(), 4);
            rect.set(cx - w / 2, top, cx + w / 2, bottom);
            base.setShader(new LinearGradient(0, top, 0, bottom,
                    new int[] { 0xFF14264F, 0xFF0B1631, 0xFF14264F }, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, w / 2, w / 2, base);
            c.drawRoundRect(rect, w / 2, w / 2, ring);
            c.drawLine(cx - w / 2 + Ui.dp(getContext(), 8), cy, cx + w / 2 - Ui.dp(getContext(), 8), cy, guide);
            drawChevron(c, cx, top + Ui.dp(getContext(), 12), -Math.PI / 2);
            drawChevron(c, cx, bottom - Ui.dp(getContext(), 12), Math.PI / 2);
            c.drawText(label, cx, getHeight() - Ui.dp(getContext(), 2), labelPaint);
        } else {
            float r = t + kr + Ui.dp(getContext(), 4);
            base.setShader(new RadialGradient(cx, cy, r, new int[] { 0xFF16295A, 0xFF0B1631 }, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, base);
            c.drawCircle(cx, cy, r, ring);
            c.drawCircle(cx, cy, r * 0.55f, guide);
            float ch = r - Ui.dp(getContext(), 12);
            drawChevron(c, cx, cy - ch, -Math.PI / 2);
            drawChevron(c, cx, cy + ch, Math.PI / 2);
            drawChevron(c, cx - ch, cy, Math.PI);
            drawChevron(c, cx + ch, cy, 0);
        }

        float x = cx + kx * t, y = cy + ky * t;
        if (active) {
            glow.setShader(new RadialGradient(x, y, kr * 2.1f, new int[] { Ui.withAlpha(color, 110), Ui.withAlpha(color, 0) },
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, kr * 2.1f, glow);
        }
        knob.setShader(new RadialGradient(x - kr * 0.35f, y - kr * 0.45f, kr * 1.5f,
                active ? new int[] { Ui.CYAN, color } : new int[] { 0xFF4A5F8C, 0xFF1C2A4D },
                null, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, kr, knob);
        c.drawCircle(x - kr * 0.3f, y - kr * 0.35f, kr * 0.28f, shine);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                active = true;
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float t = travel();
                float dx = axis == VERTICAL ? 0 : (e.getX() - getWidth() / 2f) / t;
                float dy = (e.getY() - getHeight() / 2f) / t;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1) { dx /= len; dy /= len; }
                kx = dx;
                ky = dy;
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = false;
                kx = ky = 0;
                break;
            default:
                return true;
        }
        invalidate();
        if (listener != null) listener.onStick(this, stickX(), stickY(), active);
        return true;
    }
}
