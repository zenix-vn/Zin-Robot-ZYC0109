package com.zin.robot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity implements BleLink.Listener {
    private static final String[] MODE_NAMES = { "Lái tay", "Tránh vật", "Dò line", "Bám theo" };
    private static final String[] MODE_HUD = { "Lái tay", "Tránh vật cản", "Dò line", "Bám theo" };
    private static final int[] MODE_ICONS = { R.drawable.ic_wheel, R.drawable.ic_radar, R.drawable.ic_route, R.drawable.ic_target };
    private static final int[] MODE_COLORS = { Ui.ACCENT, 0xFF14B8D4, 0xFFF08A24, 0xFFE0559A };
    private static final int[] SPEEDS = { 40, 70, 100 };
    private static final String[] SPEED_NAMES = { "Chậm", "Vừa", "Nhanh" };
    private static final int SPIN = 70;
    private static final String DEFAULT_CAM = "192.168.4.1";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private BleLink link;

    private TextView subtitle, linkText;
    private ImageView linkIcon;
    private MjpegView camera;
    private RadarView radar;
    private LinearLayout camGroup, radarGroup;
    private Tile radarToggle;
    private Tile camBtn, flashBtn, headBtn;
    private SeekBar panBar;
    private final Tile[] modeTiles = new Tile[4];
    private final LinearLayout[] speedPills = new LinearLayout[3];
    private LinearLayout styleJoy, styleTank;
    private View joyPane, tankPane;
    private JoystickView joy, tankL, tankR;
    private TelemetryView telemetry;

    private boolean tankMode, camLed, headlight;
    private int robotMode = -1;    // from telemetry; -1 = unknown
    private int spin;              // +1 spin left, -1 spin right, 0 none
    private int stopBurst;
    private long lastSent, lastPanSent;

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Ui.BG2);
        getWindow().setNavigationBarColor(Ui.BG);
        prefs = getSharedPreferences("robot", MODE_PRIVATE);
        tankMode = prefs.getBoolean("tank", false);
        headlight = prefs.getBoolean("headlight", true);
        link = new BleLink(this, this);
        setContentView(buildUi());
        selectStyle(tankMode);
        selectSpeed(prefs.getInt("speed", 1));
        showMode(-1);
        headBtn.setActive(headlight);
        if (!hasPerms()) requestPermissions(neededPerms(), 1);
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasPerms()) link.start();
        if (prefs.getBoolean("cam_on", true)) startCamera();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override protected void onPause() {
        if (link.ready() && robotMode == 0) link.send("#S");   // only stop manual driving; auto modes keep running
        ui.removeCallbacks(tick);
        camera.stop();
        camBtn.setActive(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        link.stop();
        super.onDestroy();
    }

    private String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION };
        return new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
    }

    private boolean hasPerms() {
        for (String p : neededPerms())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (hasPerms()) link.start();
        else subtitle.setText("Cần quyền Bluetooth và Vị trí để tìm robot");
    }

    // ------------------------------------------------------------------ layout helpers

    private int dp(float v) { return Ui.dp(this, v); }

    private LinearLayout hbox() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout vbox() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    private LinearLayout.LayoutParams weight(float w, int hDp) {
        return new LinearLayout.LayoutParams(0, hDp > 0 ? dp(hDp) : ViewGroup.LayoutParams.MATCH_PARENT, w);
    }

    private LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private ImageView iconView(int res, int color, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(Ui.icon(this, res, color));
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** Segment for a segmented control: optional icon + text. */
    private LinearLayout pill(int iconRes, String text) {
        LinearLayout p = hbox();
        p.setGravity(Gravity.CENTER);
        if (iconRes != 0) {
            ImageView iv = iconView(iconRes, Ui.MUTED, 16);
            p.addView(iv);
        }
        TextView t = Ui.text(this, text, 13, Ui.MUTED, Ui.MEDIUM);
        LinearLayout.LayoutParams tl = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (iconRes != 0) tl.leftMargin = dp(6);
        p.addView(t, tl);
        return p;
    }

    private void setPill(LinearLayout p, boolean on, int color) {
        p.setBackground(on ? Ui.gradient(this, color, Ui.withAlpha(color, 170), 12, GradientDrawable.Orientation.TL_BR) : null);
        for (int i = 0; i < p.getChildCount(); i++) {
            View v = p.getChildAt(i);
            int c = on ? Ui.TEXT : Ui.MUTED;
            if (v instanceof TextView) ((TextView) v).setTextColor(c);
            else if (v instanceof ImageView) ((ImageView) v).getDrawable().setTint(c);
        }
    }

    private LinearLayout segmented() {
        LinearLayout s = hbox();
        s.setBackground(Ui.glass(this, 15));
        s.setPadding(dp(3), dp(3), dp(3), dp(3));
        return s;
    }

    private LinearLayout card(int padDp) {
        LinearLayout c = vbox();
        c.setBackground(Ui.glass(this, 24));
        c.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        return c;
    }

    // ------------------------------------------------------------------ layout

    private View buildUi() {
        LinearLayout root = vbox();
        root.setPadding(dp(16), dp(10), dp(16), dp(20));

        // ---- header: logo, name, link state, settings
        LinearLayout header = hbox();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackground(Ui.round(0, dp(12)));
        logo.setClipToOutline(true);
        header.addView(logo, lp(dp(46), dp(46)));
        LinearLayout titles = vbox();
        titles.addView(Ui.text(this, "Zin Robot", 22, Ui.TEXT, Ui.BOLD));
        subtitle = Ui.text(this, "Đang khởi động...", 12, Ui.MUTED, null);
        titles.addView(subtitle, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 4, 0, 0));
        header.addView(titles, margins(weight(1, 0), 12, 0, 8, 0));
        ((LinearLayout.LayoutParams) titles.getLayoutParams()).height = ViewGroup.LayoutParams.WRAP_CONTENT;

        LinearLayout linkPill = hbox();
        linkPill.setPadding(dp(12), 0, dp(14), 0);
        linkPill.setBackground(Ui.ripple(Ui.glass(this, 18), Ui.round(0xFFFFFFFF, dp(18))));
        linkPill.setClickable(true);
        linkIcon = iconView(R.drawable.ic_bluetooth, Ui.MUTED, 16);
        linkPill.addView(linkIcon);
        linkText = Ui.text(this, "Ngắt", 13, Ui.MUTED, Ui.MEDIUM);
        linkPill.addView(linkText, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 6, 0, 0, 0));
        linkPill.setOnClickListener(v -> showDevicePicker());
        header.addView(linkPill, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        Tile gear = new Tile(this, R.drawable.ic_settings, null, true, 18);
        gear.setOnClickListener(v -> showSettings());
        header.addView(gear, margins(lp(dp(38), dp(38)), 8, 0, 0, 0));
        root.addView(header);

        // ---- camera with HUD and floating controls
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int camH = (dm.widthPixels - dp(32)) * 3 / 4;
        FrameLayout camBox = new FrameLayout(this);
        camBox.setElevation(dp(8));
        camBox.setBackground(Ui.round(0xFF02050C, dp(24)));
        camera = new MjpegView(this);
        camBox.addView(camera, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        radar = new RadarView(this);
        radar.setVisibility(View.GONE);
        camBox.addView(radar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout floating = hbox();
        camBtn = new Tile(this, R.drawable.ic_camera, null, true, 20);
        camBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            boolean on = !camera.isRunning();
            prefs.edit().putBoolean("cam_on", on).apply();
            if (on) startCamera(); else { camera.stop(); camBtn.setActive(false); camBtn.setIcon(R.drawable.ic_camera_off); }
        });
        flashBtn = new Tile(this, R.drawable.ic_flash, null, true, 20);
        flashBtn.setActiveColor(Ui.YELLOW);
        flashBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            camLed = !camLed;
            camera.setLed(camAddress(), camLed);
            flashBtn.setActive(camLed);
        });
        headBtn = new Tile(this, R.drawable.ic_headlight, null, true, 20);
        headBtn.setActiveColor(0xFF5B8CFF);
        headBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            headlight = !headlight;
            prefs.edit().putBoolean("headlight", headlight).apply();
            headBtn.setActive(headlight);
            sendHeadlight();
        });
        camGroup = hbox();
        camGroup.addView(headBtn, lp(dp(44), dp(44)));
        camGroup.addView(flashBtn, margins(lp(dp(44), dp(44)), 10, 0, 0, 0));
        camGroup.addView(camBtn, margins(lp(dp(44), dp(44)), 10, 0, 0, 0));
        floating.addView(camGroup);

        // radar controls: sweep, save, saved sweeps
        radarGroup = hbox();
        Tile scanBtn = new Tile(this, R.drawable.ic_play, null, true, 18);
        scanBtn.setActiveColor(Ui.CYAN);
        scanBtn.setOnClickListener(v -> startRadar());
        Tile saveBtn = new Tile(this, R.drawable.ic_save, null, true, 20);
        saveBtn.setOnClickListener(v -> saveRadar());
        Tile histBtn = new Tile(this, R.drawable.ic_history, null, true, 20);
        histBtn.setOnClickListener(v -> showRadarHistory());
        radarGroup.addView(histBtn, lp(dp(44), dp(44)));
        radarGroup.addView(saveBtn, margins(lp(dp(44), dp(44)), 10, 0, 0, 0));
        radarGroup.addView(scanBtn, margins(lp(dp(44), dp(44)), 10, 0, 0, 0));
        radarGroup.setVisibility(View.GONE);
        floating.addView(radarGroup);

        radarToggle = new Tile(this, R.drawable.ic_radar, null, true, 20);
        radarToggle.setActiveColor(0xFF14B8D4);
        radarToggle.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); showRadar(!radarToggle.isActive()); });
        floating.addView(radarToggle, margins(lp(dp(44), dp(44)), 10, 0, 0, 0));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, dp(12), dp(12));
        camBox.addView(floating, flp);
        root.addView(camBox, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, camH), 0, 14, 0, 0));

        // ---- camera pan
        LinearLayout panCard = hbox();
        panCard.setBackground(Ui.glass(this, 20));
        panCard.setPadding(dp(14), dp(3), dp(4), dp(3));
        panCard.addView(iconView(R.drawable.ic_scan, Ui.MUTED, 18));
        panCard.addView(Ui.text(this, "Góc camera", 13, Ui.MUTED, Ui.MEDIUM),
                margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 8, 0, 4, 0));
        panBar = new SeekBar(this);
        panBar.setMax(160);
        panBar.setProgress(80);
        panBar.setSplitTrack(false);
        panBar.setProgressTintList(ColorStateList.valueOf(Ui.CYAN));
        panBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        panBar.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        panBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                long now = SystemClock.uptimeMillis();
                if (user && now - lastPanSent > 80) { sendPan(); lastPanSent = now; }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { sendPan(); }
        });
        panCard.addView(panBar, weight(1, 40));
        Tile center = new Tile(this, R.drawable.ic_crosshair, null, true, 18);
        center.setOnClickListener(v -> { panBar.setProgress(80); sendPan(); });
        panCard.addView(center, lp(dp(38), dp(38)));
        root.addView(panCard, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 10, 0, 0));

        // ---- modes
        LinearLayout modes = hbox();
        for (int i = 0; i < 4; i++) {
            final int m = i;
            modeTiles[i] = new Tile(this, MODE_ICONS[i], MODE_NAMES[i], false, 22);
            modeTiles[i].setActiveColor(MODE_COLORS[i]);
            modeTiles[i].setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (link.ready()) link.send("#A" + m);
                showMode(m);
            });
            modes.addView(modeTiles[i], margins(weight(1, 66), i == 0 ? 0 : 4, 0, i == 3 ? 0 : 4, 0));
        }
        root.addView(modes, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 12, 0, 0));

        // ---- control deck
        LinearLayout deck = card(12);
        LinearLayout opts = hbox();
        LinearLayout style = segmented();
        styleJoy = pill(R.drawable.ic_joystick, "Joystick");
        styleTank = pill(R.drawable.ic_tank, "Hai bên");
        style.addView(styleJoy, weight(1, 34));
        style.addView(styleTank, weight(1, 34));
        styleJoy.setOnClickListener(v -> selectStyle(false));
        styleTank.setOnClickListener(v -> selectStyle(true));
        opts.addView(style, weight(1.15f, 0));
        LinearLayout speed = segmented();
        for (int i = 0; i < 3; i++) {
            final int s = i;
            speedPills[i] = pill(0, SPEED_NAMES[i]);
            speedPills[i].setOnClickListener(v -> selectSpeed(s));
            speed.addView(speedPills[i], weight(1, 34));
        }
        opts.addView(speed, margins(weight(1, 0), 8, 0, 0, 0));
        deck.addView(opts);

        FrameLayout controls = new FrameLayout(this);
        deck.addView(controls, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(212)), 0, 10, 0, 0));

        // joystick: stick left, stop + spin / horn right
        LinearLayout jp = hbox();
        joy = new JoystickView(this, JoystickView.BOTH, "", Ui.ACCENT);
        joy.setListener(this::onStick);
        jp.addView(joy, weight(1f, 0));
        LinearLayout actions = vbox();
        actions.setGravity(Gravity.CENTER);
        actions.addView(stopButton(), lp(dp(100), dp(100)));
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER);
        Tile ccw = new Tile(this, R.drawable.ic_rotate_ccw, null, true, 20);
        ccw.onHold(on -> setSpin(on ? 1 : 0));
        Tile horn = new Tile(this, R.drawable.ic_horn, null, true, 20);
        horn.setActiveColor(Ui.ORANGE);
        horn.onHold(this::horn);
        Tile cw = new Tile(this, R.drawable.ic_rotate_cw, null, true, 20);
        cw.onHold(on -> setSpin(on ? -1 : 0));
        row.addView(ccw, lp(dp(44), dp(44)));
        row.addView(horn, margins(lp(dp(44), dp(44)), 8, 0, 8, 0));
        row.addView(cw, lp(dp(44), dp(44)));
        actions.addView(row, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 14, 0, 0));
        jp.addView(actions, weight(1, 0));
        joyPane = jp;
        controls.addView(jp, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // tank: one slider per side, stop and horn between them
        LinearLayout tp = hbox();
        tankL = new JoystickView(this, JoystickView.VERTICAL, "TRÁI", Ui.GREEN);
        tankR = new JoystickView(this, JoystickView.VERTICAL, "PHẢI", Ui.GREEN);
        tankL.setListener(this::onStick);
        tankR.setListener(this::onStick);
        tp.addView(tankL, weight(1, 0));
        LinearLayout mid = vbox();
        mid.setGravity(Gravity.CENTER);
        mid.addView(stopButton(), lp(dp(100), dp(100)));
        Tile horn2 = new Tile(this, R.drawable.ic_horn, null, true, 22);
        horn2.setActiveColor(Ui.ORANGE);
        horn2.onHold(this::horn);
        mid.addView(horn2, margins(lp(dp(50), dp(50)), 0, 16, 0, 0));
        tp.addView(mid, weight(1.2f, 0));
        tp.addView(tankR, weight(1, 0));
        tankPane = tp;
        controls.addView(tp, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(deck, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 12, 0, 0));

        // ---- telemetry
        LinearLayout tcard = card(14);
        telemetry = new TelemetryView(this);
        tcard.addView(telemetry, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
        root.addView(tcard, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 12, 0, 0));

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setVerticalScrollBarEnabled(false);
        page.setBackground(Ui.pageBackground(this));
        page.addView(root);
        return page;
    }

    /** Big round emergency stop with a red glow. */
    private View stopButton() {
        LinearLayout b = vbox();
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFFFF6B78, Ui.RED_DARK });
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(2), 0x40FFFFFF);
        b.setBackground(Ui.ripple(g, Ui.oval(0xFFFFFFFF)));
        b.setElevation(dp(10));
        if (Build.VERSION.SDK_INT >= 28) { b.setOutlineSpotShadowColor(Ui.RED); b.setOutlineAmbientShadowColor(Ui.RED); }
        b.addView(iconView(R.drawable.ic_stop, Ui.TEXT, 26));
        TextView t = Ui.text(this, "DỪNG", 15, Ui.TEXT, Ui.BOLD);
        t.setLetterSpacing(0.1f);
        b.addView(t, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 4, 0, 0));
        b.setClickable(true);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            spin = 0;
            stopBurst = 0;
            panBar.setProgress(80);
            if (link.ready()) { link.send("#R"); link.send("#R"); }   // rest pose: stopped, wheels and camera straight
        });
        return b;
    }

    // ------------------------------------------------------------------ UI state

    private void selectStyle(boolean tank) {
        tankMode = tank;
        prefs.edit().putBoolean("tank", tank).apply();
        setPill(styleJoy, !tank, Ui.ACCENT);
        setPill(styleTank, tank, Ui.ACCENT);
        joyPane.setVisibility(tank ? View.GONE : View.VISIBLE);
        tankPane.setVisibility(tank ? View.VISIBLE : View.GONE);
    }

    private void selectSpeed(int i) {
        prefs.edit().putInt("speed", i).apply();
        for (int k = 0; k < 3; k++) setPill(speedPills[k], k == i, i == 2 ? Ui.ORANGE : Ui.ACCENT);
        sendConfig();
    }

    private void showMode(int m) {
        for (int k = 0; k < 4; k++) modeTiles[k].setActive(k == m);
    }

    private String camAddress() { return prefs.getString("cam_url", DEFAULT_CAM); }

    private void startCamera() {
        camera.start(camAddress());
        camBtn.setIcon(R.drawable.ic_camera);
        camBtn.setActive(true);
    }

    // ------------------------------------------------------------------ driving

    private void onStick(JoystickView v, int x, int y, boolean active) {
        if (!active && !anyStickActive()) {   // released: stop right away, then a short burst
            stopBurst = 3;
            if (link.ready()) link.send("#S");
        }
    }

    private boolean anyStickActive() {
        return tankMode ? tankL.isActive() || tankR.isActive() : joy.isActive();
    }

    private void setSpin(int s) {
        spin = s;
        if (s == 0) { stopBurst = 3; if (link.ready()) link.send("#S"); }
    }

    private void horn(boolean on) { if (link.ready()) link.send(on ? "#B1" : "#B0"); }

    private void sendPan() {
        if (link.ready()) link.send("#P" + (175 - panBar.getProgress()));   // slide right = look right
    }

    private void sendHeadlight() {
        if (link.ready()) link.send(headlight ? "#G40,40,40" : "#G0,0,0");
    }

    private void sendConfig() {
        if (!link.ready()) return;
        int s = SPEEDS[prefs.getInt("speed", 1)];
        link.send("#C" + s + "," + prefs.getInt("trim_l", 0) + "," + prefs.getInt("trim_r", 0) + "," + prefs.getInt("steer_trim", 0));
    }

    /** 10 Hz command stream: the robot stops by itself if this stream stops (failsafe). */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            controlTick();
            ui.postDelayed(this, 100);
        }
    };

    private void controlTick() {
        if (!link.ready()) return;
        long now = SystemClock.uptimeMillis();
        String cmd = null;
        if (tankMode && (tankL.isActive() || tankR.isActive()))
            cmd = "#M" + tankL.stickY() + "," + tankR.stickY();
        else if (!tankMode && joy.isActive())
            cmd = "#D" + joy.stickY() + "," + (-joy.stickX());   // stick right = steer right (negative)
        else if (spin != 0)
            cmd = "#M" + (-spin * SPIN) + "," + (spin * SPIN);
        if (cmd != null) {
            link.send(cmd);
            stopBurst = 3;
            lastSent = now;
        } else if (stopBurst > 0) {
            link.send("#S");
            stopBurst--;
            lastSent = now;
        } else if (now - lastSent > 400) {
            link.send("#H");
            lastSent = now;
        }
    }

    // ------------------------------------------------------------------ link callbacks

    @Override public void onLinkState(int state, String message) {
        subtitle.setText(message);
        int c = state == BleLink.READY ? Ui.GREEN : state == BleLink.OFF ? Ui.MUTED : Ui.YELLOW;
        linkText.setText(state == BleLink.READY ? "Đã kết nối" : state == BleLink.OFF ? "Ngắt" : "Đang tìm");
        linkText.setTextColor(c);
        linkIcon.getDrawable().setTint(c);
        if (state == BleLink.READY) {
            sendConfig();
            sendHeadlight();
            sendPan();
        } else {
            camera.clearHud();
            telemetry.clear();
            showMode(-1);
            robotMode = -1;
        }
    }

    @Override public void onLine(String line) {
        if (line.startsWith("#R")) {   // radar reading: offset,cm
            String[] q = line.substring(2).split(",");
            try { radar.add(Integer.parseInt(q[0].trim()), Integer.parseInt(q[1].trim())); } catch (Exception ignored) { }
            return;
        }
        if (line.equals("#E")) { radar.finishScan(); return; }
        if (!line.startsWith("#T")) return;
        String[] p = line.substring(2).split(",");
        if (p.length < 7) return;
        int[] v = new int[7];
        try { for (int i = 0; i < 7; i++) v[i] = Integer.parseInt(p[i].trim()); }
        catch (NumberFormatException e) { return; }
        int mode = v[0];
        if (mode < 0 || mode > 3) return;
        robotMode = mode;
        showMode(mode);
        camera.setHud(MODE_HUD[mode], MODE_COLORS[mode], v[1], v[2]);
        telemetry.set(v[3], v[4], v[5], v[6]);
    }

    // ------------------------------------------------------------------ radar

    private void showRadar(boolean on) {
        radarToggle.setActive(on);
        radar.setVisibility(on ? View.VISIBLE : View.GONE);
        camera.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        radarGroup.setVisibility(on ? View.VISIBLE : View.GONE);
        camGroup.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void startRadar() {
        if (!link.ready()) { toast("Chưa kết nối robot"); return; }
        showRadar(true);
        radar.startScan();
        link.send("#Q");
        ui.postDelayed(() -> { if (radar.isScanning()) radar.finishScan(); }, 9000);   // robot never answered fully
    }

    private void saveRadar() {
        if (radar.isScanning() || radar.points() == 0) { toast("Chưa có lần quét nào để lưu"); return; }
        String all = prefs.getString("radar_scans", "");
        String entry = System.currentTimeMillis() + ";" + radar.encode();
        String[] old = all.isEmpty() ? new String[0] : all.split("\n");
        StringBuilder sb = new StringBuilder(entry);
        for (int i = 0; i < old.length && i < 19; i++) sb.append('\n').append(old[i]);   // newest first, keep 20
        prefs.edit().putString("radar_scans", sb.toString()).apply();
        toast("Đã lưu bản quét");
    }

    private void showRadarHistory() {
        String all = prefs.getString("radar_scans", "");
        if (all.isEmpty()) { toast("Chưa có bản quét nào được lưu"); return; }
        final String[] entries = all.split("\n");
        final String[] labels = new String[entries.length];
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault());
        for (int i = 0; i < entries.length; i++) {
            long t = Long.parseLong(entries[i].substring(0, entries[i].indexOf(';')));
            labels[i] = "Quét lúc " + fmt.format(new java.util.Date(t));
        }
        dialog("Bản quét đã lưu")
                .setItems(labels, (d, which) -> {
                    showRadar(true);
                    radar.load(entries[which].substring(entries[which].indexOf(';') + 1), labels[which].toLowerCase());
                })
                .setNeutralButton("Xóa tất cả", (d, w) -> { prefs.edit().remove("radar_scans").apply(); toast("Đã xóa"); })
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ dialogs

    private SeekBar trimBar(LinearLayout box, final String label, String key) {
        return slider(box, label, key, 30, "%", null);
    }

    private interface IntListener { void accept(int v); }

    /** Labelled slider for -range..range, stored under key; onChange (may be null) gets the live value. */
    private SeekBar slider(LinearLayout box, final String label, String key, final int range, final String unit,
                           final IntListener onChange) {
        final TextView t = Ui.text(this, "", 14, Ui.TEXT, Ui.MEDIUM);
        box.addView(t, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 14, 0, 0));
        SeekBar s = new SeekBar(this);
        s.setMax(2 * range);
        s.setProgress(prefs.getInt(key, 0) + range);
        s.setSplitTrack(false);
        s.setProgressTintList(ColorStateList.valueOf(Ui.CYAN));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        s.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        t.setText(label + ":  " + (s.getProgress() - range) + unit);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                t.setText(label + ":  " + (p - range) + unit);
                if (u && onChange != null) onChange.accept(p - range);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(s, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        return s;
    }

    private AlertDialog.Builder dialog(String title) {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title);
    }

    private void showSettings() {
        LinearLayout box = vbox();
        box.setPadding(dp(24), dp(8), dp(24), dp(4));
        box.addView(Ui.text(this, "Cân chỉnh motor: xe chạy lệch thì tăng bên yếu hơn", 13, Ui.MUTED, null));
        final SeekBar tl = trimBar(box, "Bên trái", "trim_l");
        final SeekBar tr = trimBar(box, "Bên phải", "trim_r");
        box.addView(Ui.text(this, "Căn thẳng bánh lái: kéo tới khi bánh trước thẳng", 13, Ui.MUTED, null),
                margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 18, 0, 0));
        final int oldSteer = prefs.getInt("steer_trim", 0);
        final SeekBar st = slider(box, "Bánh lái", "steer_trim", 15, "°", v -> {
            prefs.edit().putInt("steer_trim", v).apply();   // live preview on the robot
            sendConfig();
        });
        box.addView(Ui.text(this, "Địa chỉ camera", 14, Ui.TEXT, Ui.MEDIUM),
                margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 18, 0, 0));
        final EditText cam = new EditText(this);
        cam.setSingleLine(true);
        cam.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        cam.setText(camAddress());
        cam.setTextColor(Ui.TEXT);
        box.addView(cam);
        box.addView(Ui.text(this, "WiFi camera: ZinRobot-CAM · mật khẩu zinrobot2026", 12, Ui.MUTED, null));

        dialog("Cài đặt")
                .setView(box)
                .setPositiveButton("Lưu", (d, w) -> {
                    String c = cam.getText().toString().trim();
                    prefs.edit().putInt("trim_l", tl.getProgress() - 30).putInt("trim_r", tr.getProgress() - 30)
                            .putInt("steer_trim", st.getProgress() - 15)
                            .putString("cam_url", c.isEmpty() ? DEFAULT_CAM : c).apply();
                    sendConfig();
                    if (camera.isRunning()) { camera.stop(); startCamera(); }
                })
                .setNeutralButton("Chọn robot khác", (d, w) -> showDevicePicker())
                .setNegativeButton("Đóng", (d, w) -> { prefs.edit().putInt("steer_trim", oldSteer).apply(); sendConfig(); })
                .show();
    }

    private void showDevicePicker() {
        final List<BleLink.Found> list = link.found();
        if (list.isEmpty()) {
            dialog("Chọn robot")
                    .setMessage("Chưa thấy thiết bị Bluetooth nào. Bật nguồn robot (công tắc Bluetooth S3 bật, S4 tắt) rồi thử lại.")
                    .setPositiveButton("Tìm lại", (d, w) -> { link.forget(); link.stop(); link.start(); })
                    .setNegativeButton("Đóng", null)
                    .show();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) {
            BleLink.Found f = list.get(i);
            items[i] = (f.name != null ? f.name : "(không tên)") + "   " + f.rssi + " dBm";
        }
        dialog("Chọn robot")
                .setItems(items, (d, which) -> link.connectTo(list.get(which).device))
                .setPositiveButton("Tìm lại", (d, w) -> { link.forget(); link.stop(); link.start(); })
                .setNegativeButton("Đóng", null)
                .show();
    }
}
