package com.zin.robot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Polar plot of one ultrasonic sweep around the robot: free space as a filled area, echoes as dots
 * coloured by distance, range rings, and the sweep line while a scan is running.
 * The robot sits at the bottom centre facing up; angles are offsets from straight ahead (+ = left).
 */
class RadarView extends View {
    static final int FROM = 75, STEP = 10, COUNT = 16;   // +75 .. -75 degrees
    static final int RANGE_CM = 200;                      // plotted range; farther = open

    private final int[] dist = new int[COUNT];            // cm, -1 = not measured
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spoke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path clip = new Path();
    private final RectF rf = new RectF();
    private boolean scanning;
    private int lastIndex = -1;
    private String caption = "Bấm nút quét để vẽ radar quanh robot";

    RadarView(Context c) {
        super(c);
        clear();
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 1));
        ring.setColor(0x2EFFFFFF);
        spoke.setStyle(Paint.Style.STROKE);
        spoke.setStrokeWidth(Ui.dp(c, 1));
        spoke.setColor(0x14FFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.dp(c, 2));
        edge.setStrokeJoin(Paint.Join.ROUND);
        edge.setColor(Ui.CYAN);
        text.setColor(Ui.MUTED);
        text.setTextSize(Ui.dp(c, 11));
        text.setTypeface(Ui.MEDIUM);
    }

    void clear() {
        for (int i = 0; i < COUNT; i++) dist[i] = -1;
        lastIndex = -1;
        invalidate();
    }

    void startScan() {
        clear();
        scanning = true;
        caption = "Đang quét...";
        invalidate();
    }

    void finishScan() {
        scanning = false;
        caption = points() + " điểm · vừa quét";
        invalidate();
    }

    boolean isScanning() { return scanning; }

    /** One reading from the robot: offset in degrees (+ = left), distance in cm (400 = no echo). */
    void add(int offset, int cm) {
        int i = Math.round((FROM - offset) / (float) STEP);
        if (i < 0 || i >= COUNT) return;
        dist[i] = cm;
        lastIndex = i;
        invalidate();
    }

    int points() {
        int n = 0;
        for (int d : dist) if (d >= 0) n++;
        return n;
    }

    /** "d0,d1,...": compact form for saving. */
    String encode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < COUNT; i++) { if (i > 0) sb.append(','); sb.append(dist[i]); }
        return sb.toString();
    }

    void load(String data, String label) {
        clear();
        String[] p = data.split(",");
        for (int i = 0; i < COUNT && i < p.length; i++) {
            try { dist[i] = Integer.parseInt(p[i].trim()); } catch (NumberFormatException ignored) { }
        }
        scanning = false;
        caption = label;
        invalidate();
    }

    // ------------------------------------------------------------------ drawing

    private static int colorFor(int cm) {
        return cm < 30 ? Ui.RED : cm < 80 ? Ui.ORANGE : Ui.CYAN;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float pad = Ui.dp(getContext(), 18);
        float top = Ui.dp(getContext(), 50);            // below the caption chip
        float cx = w / 2f, cy = h - Ui.dp(getContext(), 74);   // above the floating buttons
        float maxR = Math.min(cy - top, w / 2f - pad);
        float corner = Ui.dp(getContext(), 24);

        rf.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rf, corner, corner, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawColor(0xFF040A16);
        Paint glow = new Paint();
        glow.setShader(new RadialGradient(cx, cy, maxR * 1.1f, 0x2235D0FF, 0x0035D0FF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, maxR * 1.1f, glow);

        // range rings (half circles) with labels, and spokes every 30 degrees
        text.setTextAlign(Paint.Align.LEFT);
        for (int k = 1; k <= 4; k++) {
            float r = maxR * k / 4f;
            rf.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rf, 180, 180, false, ring);
            c.drawText((RANGE_CM * k / 4) + (k == 4 ? " cm" : ""), cx + Ui.dp(getContext(), 4), cy - r + Ui.dp(getContext(), 12), text);
        }
        for (int a = -90; a <= 90; a += 30) {
            double t = Math.toRadians(a);
            c.drawLine(cx, cy, cx - (float) Math.sin(t) * maxR, cy - (float) Math.cos(t) * maxR, spoke);
        }

        // free space polygon through the measured points
        path.reset();
        path.moveTo(cx, cy);
        int n = 0;
        for (int i = 0; i < COUNT; i++) {
            if (dist[i] < 0) continue;
            float[] p = point(i, dist[i], cx, cy, maxR);
            path.lineTo(p[0], p[1]);
            n++;
        }
        if (n > 1) {
            path.close();
            area.setShader(new RadialGradient(cx, cy, maxR, 0x5535D0FF, 0x1535D0FF, Shader.TileMode.CLAMP));
            c.drawPath(path, area);
            c.drawPath(path, edge);
        }

        // echoes
        float dr = Ui.dp(getContext(), 4.5f);
        for (int i = 0; i < COUNT; i++) {
            if (dist[i] < 0 || dist[i] >= RANGE_CM) continue;
            float[] p = point(i, dist[i], cx, cy, maxR);
            dot.setColor(Ui.withAlpha(colorFor(dist[i]), 70));
            c.drawCircle(p[0], p[1], dr * 2.2f, dot);
            dot.setColor(colorFor(dist[i]));
            c.drawCircle(p[0], p[1], dr, dot);
        }

        // sweep line at the latest angle while scanning
        if (scanning && lastIndex >= 0) {
            float[] p = point(lastIndex, RANGE_CM, cx, cy, maxR);
            sweep.setStrokeWidth(Ui.dp(getContext(), 3));
            sweep.setStrokeCap(Paint.Cap.ROUND);
            sweep.setShader(new LinearGradient(cx, cy, p[0], p[1], 0x0035D0FF, 0xFF35D0FF, Shader.TileMode.CLAMP));
            c.drawLine(cx, cy, p[0], p[1], sweep);
        }

        // robot
        dot.setColor(Ui.ACCENT);
        rf.set(cx - Ui.dp(getContext(), 11), cy - Ui.dp(getContext(), 8), cx + Ui.dp(getContext(), 11), cy + Ui.dp(getContext(), 14));
        c.drawRoundRect(rf, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), dot);
        dot.setColor(Ui.TEXT);
        c.drawCircle(cx, cy - Ui.dp(getContext(), 1), Ui.dp(getContext(), 3), dot);

        // caption chip, top-left
        text.setTextSize(Ui.dp(getContext(), 13));
        String title = "Radar  ·  " + caption;
        float tw = text.measureText(title), ch = Ui.dp(getContext(), 28), m = Ui.dp(getContext(), 12);
        rf.set(m, m, m + tw + Ui.dp(getContext(), 20), m + ch);
        chipBg.setColor(Ui.SCRIM);
        c.drawRoundRect(rf, ch / 2, ch / 2, chipBg);
        text.setColor(Ui.TEXT);
        c.drawText(title, m + Ui.dp(getContext(), 10), m + ch / 2 - (text.descent() + text.ascent()) / 2, text);
        text.setColor(Ui.MUTED);
        text.setTextSize(Ui.dp(getContext(), 11));
        c.restore();
    }

    private static float[] point(int i, int cm, float cx, float cy, float maxR) {
        double t = Math.toRadians(FROM - i * STEP);
        float r = maxR * Math.min(cm, RANGE_CM) / RANGE_CM;
        return new float[] { cx - (float) Math.sin(t) * r, cy - (float) Math.cos(t) * r };
    }
}
