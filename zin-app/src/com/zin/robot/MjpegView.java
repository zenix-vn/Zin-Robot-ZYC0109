package com.zin.robot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ESP32-CAM live view (MJPEG, multipart/x-mixed-replace with Content-Length per part) with a
 * driving HUD drawn on top: mode, distance, line sensors, collision guard, frame rate.
 */
public class MjpegView extends View {
    /** Every decoded frame, on the camera thread (for image analysis). */
    interface FrameListener { void onFrame(Bitmap bm); }

    private static final int MAX_FRAME = 2 * 1024 * 1024;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ConnectivityManager cm;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rf = new RectF();
    private final Rect dst = new Rect();

    private volatile boolean running;
    private Thread worker;
    private Bitmap frame;
    private static final String OFF_TEXT = "Camera đang tắt\nBấm nút camera để xem hình";
    private String status = OFF_TEXT;
    private int fps;

    // HUD values, set from telemetry
    private boolean hudValid;
    private String modeName = "";
    private int modeColor = Ui.ACCENT;
    private int distCm = -1;
    private int lineBits;
    private boolean guard, lineLost, thinking, stuck, alarm;
    private int modeIndex = -1;

    // tracking overlay, normalised to the frame
    private final RectF track = new RectF();
    private boolean trackOn;
    private int trackColor;
    private String trackLabel;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile FrameListener frameListener;

    private final Paint scrim = new Paint();
    private final Drawable rulerIcon, wifiIcon;

    public MjpegView(Context c) {
        super(c);
        cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        text.setColor(Ui.TEXT);
        text.setTextSize(Ui.dp(c, 13));
        text.setTypeface(Ui.MEDIUM);
        rulerIcon = Ui.icon(c, R.drawable.ic_ruler, Ui.TEXT);
        wifiIcon = Ui.icon(c, R.drawable.ic_wifi, Ui.MUTED);
    }

    public boolean isRunning() { return running; }

    void setFrameListener(FrameListener l) { frameListener = l; }

    /** Box around the tracked target (normalised 0..1), or null to hide it. */
    void setTrack(RectF box, int color, String label) {
        trackOn = box != null;
        if (box != null) track.set(box);
        trackColor = color;
        trackLabel = label;
        invalidate();
    }

    /** Average colour around a touch point (view coordinates), or 0 when no picture is shown there. */
    int colorAt(float x, float y) {
        Bitmap bm = frame;
        if (bm == null || !dst.contains((int) x, (int) y)) return 0;
        int fx = (int) ((x - dst.left) / dst.width() * bm.getWidth());
        int fy = (int) ((y - dst.top) / dst.height() * bm.getHeight());
        int r = 0, g = 0, b = 0, n = 0;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                int px = fx + dx, py = fy + dy;
                if (px < 0 || py < 0 || px >= bm.getWidth() || py >= bm.getHeight()) continue;
                int c = bm.getPixel(px, py);
                r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
                n++;
            }
        }
        return n == 0 ? 0 : 0xFF000000 | (r / n << 16) | (g / n << 8) | (b / n);
    }

    public void setHud(String mode, int color, int dist, int flags, int index) {
        hudValid = true;
        modeIndex = index;
        alarm = (flags & 0x100) != 0;
        modeName = mode;
        modeColor = color;
        distCm = dist;
        lineBits = flags & 7;
        guard = (flags & 0x08) != 0;
        lineLost = (flags & 0x10) != 0;
        thinking = (flags & 0x20) != 0;
        stuck = (flags & 0x80) != 0;
        invalidate();
    }

    public void clearHud() { hudValid = false; invalidate(); }

    // ------------------------------------------------------------------ streaming

    static String normalize(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "http://" + s;
        if (s.indexOf('/', s.indexOf("://") + 3) < 0) s += "/";
        return s;
    }

    static String base(String url) {
        int end = url.indexOf('/', url.indexOf("://") + 3);
        return end < 0 ? url + "/" : url.substring(0, end + 1);
    }

    /** Uses the Wi-Fi network explicitly: the camera AP has no Internet, so it is not the default route. */
    @SuppressWarnings("deprecation")
    HttpURLConnection open(String url) throws IOException {
        URL u = new URL(url);
        if (cm != null) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                    return (HttpURLConnection) n.openConnection(u);
            }
        }
        return (HttpURLConnection) u.openConnection();
    }

    public void start(String address) {
        final String url = normalize(address);
        if (url == null || running) return;
        running = true;
        setStatus("Đang kết nối camera...");
        worker = new Thread(new Runnable() { @Override public void run() { loop(url); } }, "mjpeg");
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
        frame = null;
        fps = 0;
        setStatus(OFF_TEXT);
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() { @Override public void run() { status = s; invalidate(); } });
    }

    private void loop(String url) {
        while (running) {
            HttpURLConnection conn = null;
            try {
                conn = open(url);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);
                InputStream in = new BufferedInputStream(conn.getInputStream(), 1 << 16);
                int frames = 0;
                long t0 = System.currentTimeMillis();
                while (running) {
                    int len = -1;
                    for (;;) {
                        String line = readLine(in);
                        if (line == null) throw new IOException("luồng bị đóng");
                        if (line.isEmpty()) { if (len > 0) break; else continue; }
                        if (line.regionMatches(true, 0, "content-length:", 0, 15))
                            len = Integer.parseInt(line.substring(15).trim());
                    }
                    if (len > MAX_FRAME) throw new IOException("khung hình quá lớn");
                    byte[] buf = new byte[len];
                    int got = 0;
                    while (got < len) {
                        int n = in.read(buf, got, len - got);
                        if (n < 0) throw new IOException("luồng bị đóng");
                        got += n;
                    }
                    final Bitmap bm = BitmapFactory.decodeByteArray(buf, 0, len);
                    if (bm == null) continue;
                    FrameListener fl = frameListener;
                    if (fl != null) fl.onFrame(bm);
                    frames++;
                    long dt = System.currentTimeMillis() - t0;
                    if (dt >= 2000) { fps = (int) (frames * 1000 / dt); frames = 0; t0 = System.currentTimeMillis(); }
                    ui.post(new Runnable() {
                        @Override public void run() { if (running) { frame = bm; status = null; invalidate(); } }
                    });
                }
            } catch (Exception e) {
                if (running) {
                    setStatus("Chưa thấy camera\nNối WiFi ZinRobot-CAM · mật khẩu zinrobot2026");
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (;;) {
            int b = in.read();
            if (b < 0) return sb.length() == 0 ? null : sb.toString();
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 256) throw new IOException("tiêu đề không hợp lệ");
        }
        return sb.toString();
    }

    /** Flash LED of the ESP32-CAM over Wi-Fi (firmware endpoint /led?level=0..100&fx=0..3). */
    public void setFlash(final String address, final int level, final int fx) {
        final String url = normalize(address);
        if (url == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = open(base(url) + "led?level=" + level + "&fx=" + fx);
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(3000);
                    c.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }, "led").start();
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float r = Ui.dp(getContext(), 24);
        rf.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rf, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawColor(0xFF05070A);

        Bitmap bm = frame;
        if (bm != null) {
            float s = Math.min((float) w / bm.getWidth(), (float) h / bm.getHeight());
            int dw = (int) (bm.getWidth() * s), dh = (int) (bm.getHeight() * s);
            dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            c.drawBitmap(bm, null, dst, paint);
            if (trackOn) drawTrack(c);
        }
        // bottom scrim keeps the overlay readable on bright scenes
        scrim.setShader(new LinearGradient(0, h * 0.62f, 0, h, 0x00050A18, 0xCC050A18, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.62f, w, h, scrim);

        if (status != null) {
            float is = Ui.dp(getContext(), 34);
            Drawable ic = frame == null ? wifiIcon : null;
            String[] lines = status.split("\n");
            float lh = text.getTextSize() * 1.5f;
            float y0 = h / 2f - (lines.length - 1) * lh / 2 + (ic != null ? is / 2 : 0);
            if (ic != null) {
                ic.setBounds((int) (w / 2f - is / 2), (int) (y0 - is - lh), (int) (w / 2f + is / 2), (int) (y0 - lh));
                ic.draw(c);
            }
            text.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < lines.length; i++) {
                text.setColor(i == 0 ? Ui.TEXT : Ui.MUTED);
                c.drawText(lines[i], w / 2f, y0 + i * lh, text);
            }
        }
        if (hudValid) drawHud(c, w, h);
        c.restore();
    }

    private void drawTrack(Canvas c) {
        float l = dst.left + track.left * dst.width(), t = dst.top + track.top * dst.height();
        float r = dst.left + track.right * dst.width(), b = dst.top + track.bottom * dst.height();
        float rad = Ui.dp(getContext(), 10);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(Ui.dp(getContext(), 3));
        trackPaint.setColor(trackColor);
        rf.set(l, t, r, b);
        c.drawRoundRect(rf, rad, rad, trackPaint);
        trackPaint.setStyle(Paint.Style.FILL);
        c.drawCircle((l + r) / 2, (t + b) / 2, Ui.dp(getContext(), 4), trackPaint);
        if (trackLabel != null) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(trackColor);
            c.drawText(trackLabel, l, t - Ui.dp(getContext(), 6), text);
        }
    }

    private float chip(Canvas c, float x, float y, String s, int bg, Drawable ic, boolean alignRight) {
        float pad = Ui.dp(getContext(), 10), ch = Ui.dp(getContext(), 28), is = Ui.dp(getContext(), 14);
        float gap = ic != null ? is + Ui.dp(getContext(), 6) : 0;
        text.setTextAlign(Paint.Align.LEFT);
        float tw = text.measureText(s);
        float wd = tw + gap + 2 * pad;
        float left = alignRight ? x - wd : x;
        rf.set(left, y, left + wd, y + ch);
        paint.setShader(null);
        paint.setColor(bg);
        c.drawRoundRect(rf, ch / 2, ch / 2, paint);
        if (ic != null) {
            int iy = (int) (y + (ch - is) / 2);
            ic.setBounds((int) (left + pad), iy, (int) (left + pad + is), (int) (iy + is));
            ic.draw(c);
        }
        text.setColor(Ui.TEXT);
        c.drawText(s, left + pad + gap, y + ch / 2 - (text.descent() + text.ascent()) / 2, text);
        return wd;
    }

    private void drawHud(Canvas c, int w, int h) {
        float m = Ui.dp(getContext(), 12);
        chip(c, m, m, modeName, Ui.withAlpha(modeColor, 215), null, false);

        boolean none = distCm <= 0 || distCm >= 400;
        int dc = none ? Ui.SCRIM
               : distCm < 15 ? Ui.withAlpha(Ui.RED, 225)
               : distCm < 40 ? Ui.withAlpha(Ui.ORANGE, 225) : Ui.withAlpha(Ui.GREEN, 205);
        chip(c, w - m, m, none ? "-- cm" : distCm + " cm", dc, rulerIcon, true);

        float row2 = m + Ui.dp(getContext(), 36);
        if (alarm) chip(c, m, row2, "BÁO ĐỘNG · có chuyển động", Ui.withAlpha(Ui.RED, 235), null, false);
        else if (modeIndex == 4) chip(c, m, row2, thinking ? "Đang ghi nhớ căn phòng" : "Đang canh gác", Ui.withAlpha(Ui.VIOLET, 215), null, false);
        else if (stuck) chip(c, m, row2, thinking ? "Đang thoát kẹt" : "Bị kẹt · cần giúp", Ui.withAlpha(Ui.RED, 215), null, false);
        else if (guard) chip(c, m, row2, "Đang tránh va chạm", Ui.withAlpha(Ui.RED, 215), null, false);
        else if (lineLost) chip(c, m, row2, "Mất vạch", Ui.withAlpha(Ui.ORANGE, 215), null, false);
        else if (thinking) chip(c, m, row2, "Đang tìm đường", Ui.withAlpha(0xFF14B8D4, 215), null, false);

        // line sensors (L M R): filled = black line under the sensor
        float r = Ui.dp(getContext(), 5), y = h - m - r, x = m + r;
        for (int i = 0; i < 3; i++) {
            paint.setColor((lineBits & (1 << i)) != 0 ? Ui.YELLOW : 0x55FFFFFF);
            c.drawCircle(x + i * r * 3, y, r, paint);
        }
        if (frame != null) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(0xB3FFFFFF);
            c.drawText(fps + " fps", x + 9 * r, y - (text.descent() + text.ascent()) / 2, text);
        }
    }
}
