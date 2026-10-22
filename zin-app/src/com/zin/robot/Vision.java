package com.zin.robot;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.RectF;
import android.media.FaceDetector;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * Finds a target in the camera frames, off the UI thread, dropping frames while busy:
 *  COLOR - pixels close to a picked hue (centroid and spread of the matching pixels)
 *  FACE  - the largest face (android.media.FaceDetector, built into Android, no extra library)
 * Results come back on the UI thread with the position normalised to 0..1 of the frame.
 */
class Vision {
    interface Listener { void onResult(Result r); }

    static final int OFF = 0, COLOR = 1, FACE = 2;

    static class Result {
        int mode;
        boolean found;
        float cx, cy;     // target centre, 0..1
        float size;       // COLOR: share of the frame covered; FACE: eye distance / frame width
        final RectF box = new RectF();
    }

    private static final int CW = 160, CH = 120;    // colour analysis size
    private static final int FW = 320, FH = 240;    // face analysis size
    private static final float HUE_TOL = 18f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler work;
    private final Listener listener;
    private volatile int mode = OFF;
    private volatile boolean busy;
    private volatile float hue, satMin = 0.35f;
    private final int[] px = new int[CW * CH];
    private final float[] hsv = new float[3];
    private FaceDetector faces;
    private final FaceDetector.Face[] found = new FaceDetector.Face[4];

    Vision(Listener l) {
        listener = l;
        HandlerThread t = new HandlerThread("vision");
        t.start();
        work = new Handler(t.getLooper());
    }

    int mode() { return mode; }
    void setMode(int m) { mode = m; }

    /** Picks the colour to follow; false when it is too grey to track. */
    boolean setColor(int rgb) {
        float[] c = new float[3];   // own array: the worker thread uses hsv
        Color.colorToHSV(rgb, c);
        if (c[1] < 0.25f || c[2] < 0.2f) return false;
        hue = c[0];
        satMin = Math.max(0.25f, c[1] * 0.5f);
        return true;
    }

    float hue() { return hue; }

    /** Called from the camera thread for every decoded frame. */
    void onFrame(final Bitmap bm) {
        final int m = mode;
        if (m == OFF || busy) return;
        busy = true;
        work.post(() -> {
            Result r = null;
            try { r = m == COLOR ? color(bm) : face(bm); } catch (Exception ignored) { }
            busy = false;
            if (r == null) return;
            final Result res = r;
            ui.post(() -> { if (mode == res.mode) listener.onResult(res); });
        });
    }

    private Result color(Bitmap bm) {
        Bitmap s = Bitmap.createScaledBitmap(bm, CW, CH, false);
        s.getPixels(px, 0, CW, 0, 0, CW, CH);
        float h0 = hue, sMin = satMin;
        long n = 0;
        double sx = 0, sy = 0, sxx = 0, syy = 0;
        for (int y = 0; y < CH; y++) {
            for (int x = 0; x < CW; x++) {
                Color.colorToHSV(px[y * CW + x], hsv);
                float dh = Math.abs(hsv[0] - h0);
                if (dh > 180) dh = 360 - dh;
                if (dh > HUE_TOL || hsv[1] < sMin || hsv[2] < 0.18f) continue;
                n++;
                sx += x; sy += y; sxx += (double) x * x; syy += (double) y * y;
            }
        }
        Result r = new Result();
        r.mode = COLOR;
        if (n < CW * CH * 0.004) return r;
        double mx = sx / n, my = sy / n;
        double dx = Math.sqrt(Math.max(1, sxx / n - mx * mx)), dy = Math.sqrt(Math.max(1, syy / n - my * my));
        r.found = true;
        r.cx = (float) (mx / CW);
        r.cy = (float) (my / CH);
        r.size = (float) n / (CW * CH);
        r.box.set((float) ((mx - 2 * dx) / CW), (float) ((my - 2 * dy) / CH),
                  (float) ((mx + 2 * dx) / CW), (float) ((my + 2 * dy) / CH));
        return r;
    }

    private Result face(Bitmap bm) {
        Bitmap s = Bitmap.createScaledBitmap(bm, FW, FH, true).copy(Bitmap.Config.RGB_565, false);
        if (faces == null) faces = new FaceDetector(FW, FH, found.length);
        int n = faces.findFaces(s, found);
        Result r = new Result();
        r.mode = FACE;
        FaceDetector.Face best = null;
        for (int i = 0; i < n; i++) {
            if (found[i] == null || found[i].confidence() < 0.3f) continue;
            if (best == null || found[i].eyesDistance() > best.eyesDistance()) best = found[i];
        }
        if (best == null) return r;
        PointF mid = new PointF();
        best.getMidPoint(mid);
        float e = best.eyesDistance();
        r.found = true;
        r.cx = mid.x / FW;
        r.cy = mid.y / FH;
        r.size = e / FW;
        r.box.set((mid.x - 1.3f * e) / FW, (mid.y - 1.2f * e) / FH, (mid.x + 1.3f * e) / FW, (mid.y + 1.9f * e) / FH);
        return r;
    }
}
