package com.zin.robot;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Finds beats in what the microphone hears: a 32 ms frame whose energy jumps well above the average
 * of the last ~1.4 s is a beat (classic energy-based onset detection, no FFT needed).
 * Needs the RECORD_AUDIO permission.
 */
class BeatDetector {
    interface Listener { void onBeat(float strength); }

    private static final int RATE = 16000, FRAME = 512, HISTORY = 43;
    private static final float JUMP = 1.45f;        // energy over the recent average to count as a beat
    private static final double MIN_ENERGY = 2e5;   // ignore silence and room noise
    private static final long REFRACTORY_MS = 280;  // no two beats closer than this

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile boolean running;
    private Thread worker;

    BeatDetector(Listener l) { listener = l; }

    boolean isRunning() { return running; }

    /** false if the microphone could not be opened. */
    @SuppressWarnings("MissingPermission")
    boolean start() {
        if (running) return true;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); return false; }
            rec.startRecording();
        } catch (Exception e) {
            return false;
        }
        running = true;
        worker = new Thread(() -> loop(rec), "beats");
        worker.start();
        return true;
    }

    void stop() {
        running = false;
        worker = null;
    }

    private void loop(AudioRecord rec) {
        short[] buf = new short[FRAME];
        double[] hist = new double[HISTORY];
        int filled = 0, pos = 0;
        long last = 0;
        try {
            while (running) {
                int n = rec.read(buf, 0, FRAME);
                if (n <= 0) continue;
                double e = 0;
                for (int i = 0; i < n; i++) e += (double) buf[i] * buf[i];
                e /= n;
                double avg = 0;
                for (int i = 0; i < filled; i++) avg += hist[i];
                avg = filled > 0 ? avg / filled : e;
                long now = SystemClock.uptimeMillis();
                if (filled == HISTORY && e > JUMP * avg && e > MIN_ENERGY && now - last > REFRACTORY_MS) {
                    last = now;
                    final float strength = (float) Math.min(3.0, e / avg);
                    ui.post(() -> { if (running) listener.onBeat(strength); });
                }
                hist[pos] = e;
                pos = (pos + 1) % HISTORY;
                if (filled < HISTORY) filled++;
            }
        } finally {
            rec.stop();
            rec.release();
        }
    }
}
