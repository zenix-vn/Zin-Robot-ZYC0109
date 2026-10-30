package com.zin.robot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
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

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements BleLink.Listener, GamePad.Listener {
    private static final String[] MODE_NAMES = { "Lái tay", "Tránh vật", "Dò line", "Bám theo", "Canh gác" };
    private static final String[] MODE_HUD = { "Lái tay", "Tránh vật cản", "Dò line", "Bám theo", "Canh gác" };
    private static final int[] MODE_ICONS = { R.drawable.ic_wheel, R.drawable.ic_radar, R.drawable.ic_route, R.drawable.ic_target,
            R.drawable.ic_shield };
    private static final int[] MODE_COLORS = { Ui.ACCENT, 0xFF14B8D4, 0xFFF08A24, 0xFFE0559A, Ui.VIOLET };
    private static final int MODES = MODE_NAMES.length;
    private static final int STYLE_JOY = 0, STYLE_TANK = 1, STYLE_TILT = 2;
    private static final int PERM_VOICE = 2, PERM_DANCE = 3, PERM_NOTIFY = 4;
    private static final int[] DANCE_STEPS = { 0, 1, 0, 1, 4, 5, 4, 5, 2, 3, 1, 0 };   // firmware #X moves
    private static final String ALARM_CHANNEL = "alarm";
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
    private final Tile[] modeTiles = new Tile[MODES];
    private final LinearLayout[] speedPills = new LinearLayout[3];
    private final LinearLayout[] stylePills = new LinearLayout[3];
    private View joyPane, tankPane, tiltPane;
    private JoystickView joy, tankL, tankR;
    private TiltPad tilt;
    private TelemetryView telemetry;
    private Tile voiceTile, danceTile, colorTile, faceTile;
    private Tile padTile;

    private int style;
    private boolean camLed, headlight;

    // voice: a timed manoeuvre ("rẽ trái" drives for a moment)
    private SpeechRecognizer speech;
    private boolean listening;
    private int voiceT, voiceS;
    private long voiceUntil;

    // dance
    private BeatDetector beats;
    private int danceStep;

    // tracking (colour / face) with the camera on the pan servo
    private Vision vision;
    private Vision.Result target;
    private int trackPan = 95;
    private long targetSeen, lastPanCmd;
    private boolean pickColor;
    private int robotMode = -1;    // from telemetry; -1 = unknown
    private int spin;              // +1 spin left, -1 spin right, 0 none
    private int stopBurst;
    private long lastSent, lastPanSent;

    // PS5 / Bluetooth gamepad paired with the phone
    private GamePad pad;
    private boolean padDriving;
    private int padSpin;           // L1 / R1 held: same as the spin buttons
    private int lastFlags;         // telemetry flags, for rumble on new events

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Ui.BG2);
        getWindow().setNavigationBarColor(Ui.BG);
        prefs = getSharedPreferences("robot", MODE_PRIVATE);
        style = prefs.getInt("style", prefs.getBoolean("tank", false) ? STYLE_TANK : STYLE_JOY);
        headlight = prefs.getBoolean("headlight", true);
        link = new BleLink(this, this);
        pad = new GamePad(this, this);
        beats = new BeatDetector(this::onBeat);
        vision = new Vision(this::onVision);
        setContentView(buildUi());
        camera.setFrameListener(vision::onFrame);
        selectStyle(style);
        selectSpeed(prefs.getInt("speed", 1));
        showMode(-1);
        headBtn.setActive(headlight);
        if (!hasPerms()) requestPermissions(neededPerms(), 1);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(ALARM_CHANNEL, "Báo động canh gác", NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasPerms()) link.start();
        pad.start();
        if (prefs.getBoolean("cam_on", true) || vision.mode() != Vision.OFF) startCamera();
        tilt.listen(style == STYLE_TILT);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override protected void onPause() {
        if (link.ready() && robotMode == 0) link.send("#S");   // only stop manual driving; auto modes keep running
        ui.removeCallbacks(tick);
        pad.stop();
        padDriving = false;
        padSpin = 0;
        camera.stop();
        camBtn.setActive(false);
        tilt.listen(false);
        stopDance();
        stopTracking();
        if (speech != null) speech.cancel();
        setListening(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (speech != null) speech.destroy();
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
        boolean granted = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
        if (code == PERM_VOICE) { if (granted) startListening(); else toast("Cần quyền micro để nghe lệnh"); return; }
        if (code == PERM_DANCE) { if (granted) startDance(); else toast("Cần quyền micro để nghe nhạc"); return; }
        if (code == PERM_NOTIFY) return;
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
        padTile = new Tile(this, R.drawable.ic_gamepad, null, true, 18);
        padTile.setActiveColor(Ui.VIOLET);
        padTile.setActive(true);
        padTile.setVisibility(View.GONE);   // shown while a gamepad is connected
        padTile.setOnClickListener(v -> showPadHelp());
        header.addView(padTile, margins(lp(dp(36), dp(36)), 0, 0, 8, 0));
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
        camera.setOnTouchListener((v, e) -> {   // colour tracking: tap the thing to follow
            if (e.getActionMasked() != MotionEvent.ACTION_UP || vision.mode() != Vision.COLOR) return true;
            pickColorAt(e.getX(), e.getY());
            return true;
        });
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
        flashBtn.setOnClickListener(v -> {   // tap: on / off, hold: brightness, effect, automatic
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            setFlash(!camLed);
        });
        flashBtn.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showFlashPanel();
            return true;
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
        for (int i = 0; i < MODES; i++) {
            final int m = i;
            modeTiles[i] = new Tile(this, MODE_ICONS[i], MODE_NAMES[i], false, 22);
            modeTiles[i].setActiveColor(MODE_COLORS[i]);
            modeTiles[i].setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                setRobotMode(m);
            });
            modes.addView(modeTiles[i], margins(weight(1, 66), i == 0 ? 0 : 3, 0, i == MODES - 1 ? 0 : 3, 0));
        }
        modeTiles[4].setOnLongClickListener(v -> { showAlarmHistory(); return true; });
        root.addView(modes, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 12, 0, 0));

        // ---- extra features: voice, dance, colour / face tracking
        LinearLayout feats = hbox();
        voiceTile = new Tile(this, R.drawable.ic_mic, "Giọng nói", false, 22);
        voiceTile.setActiveColor(Ui.GREEN);
        voiceTile.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); toggleListening(); });
        voiceTile.setOnLongClickListener(v -> { showVoiceHelp(); return true; });
        danceTile = new Tile(this, R.drawable.ic_music, "Nhảy", false, 22);
        danceTile.setActiveColor(Ui.PINK);
        danceTile.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); toggleDance(); });
        colorTile = new Tile(this, R.drawable.ic_palette, "Bám màu", false, 22);
        colorTile.setActiveColor(Ui.ORANGE);
        colorTile.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); toggleTracking(Vision.COLOR); });
        faceTile = new Tile(this, R.drawable.ic_face, "Bám mặt", false, 22);
        faceTile.setActiveColor(Ui.CYAN);
        faceTile.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); toggleTracking(Vision.FACE); });
        Tile[] ft = { voiceTile, danceTile, colorTile, faceTile };
        for (int i = 0; i < ft.length; i++)
            feats.addView(ft[i], margins(weight(1, 66), i == 0 ? 0 : 3, 0, i == ft.length - 1 ? 0 : 3, 0));
        root.addView(feats, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 0));

        // ---- control deck
        LinearLayout deck = card(12);
        LinearLayout styleBar = segmented();
        stylePills[STYLE_JOY] = pill(R.drawable.ic_joystick, "Joystick");
        stylePills[STYLE_TANK] = pill(R.drawable.ic_tank, "Hai bên");
        stylePills[STYLE_TILT] = pill(R.drawable.ic_tilt, "Nghiêng");
        for (int i = 0; i < 3; i++) {
            final int st = i;
            stylePills[i].setOnClickListener(v -> selectStyle(st));
            styleBar.addView(stylePills[i], weight(1, 34));
        }
        deck.addView(styleBar);
        LinearLayout speed = segmented();
        for (int i = 0; i < 3; i++) {
            final int s = i;
            speedPills[i] = pill(0, SPEED_NAMES[i]);
            speedPills[i].setOnClickListener(v -> selectSpeed(s));
            speed.addView(speedPills[i], weight(1, 34));
        }
        deck.addView(speed, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 0));

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

        // tilt: hold-to-drive pad left, stop + horn right
        LinearLayout lp2 = hbox();
        tilt = new TiltPad(this);
        tilt.setListener(on -> { if (!on && !anyStickActive()) { stopBurst = 3; if (link.ready()) link.send("#S"); } });
        lp2.addView(tilt, weight(1.3f, 0));
        LinearLayout tmid = vbox();
        tmid.setGravity(Gravity.CENTER);
        tmid.addView(stopButton(), lp(dp(100), dp(100)));
        Tile horn3 = new Tile(this, R.drawable.ic_horn, null, true, 22);
        horn3.setActiveColor(Ui.ORANGE);
        horn3.onHold(this::horn);
        tmid.addView(horn3, margins(lp(dp(50), dp(50)), 0, 16, 0, 0));
        lp2.addView(tmid, weight(1, 0));
        tiltPane = lp2;
        controls.addView(lp2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
            restPose();
        });
        return b;
    }

    private void restPose() {
        spin = 0;
        padSpin = 0;
        stopBurst = 0;
        voiceUntil = 0;
        stopDance();
        stopTracking();
        panBar.setProgress(80);
        if (link.ready()) { link.send("#R"); link.send("#R"); }   // rest pose: stopped, wheels and camera straight
    }

    // ------------------------------------------------------------------ UI state

    private void selectStyle(int st) {
        style = st;
        prefs.edit().putInt("style", st).apply();
        for (int i = 0; i < 3; i++) setPill(stylePills[i], i == st, Ui.ACCENT);
        joyPane.setVisibility(st == STYLE_JOY ? View.VISIBLE : View.GONE);
        tankPane.setVisibility(st == STYLE_TANK ? View.VISIBLE : View.GONE);
        tiltPane.setVisibility(st == STYLE_TILT ? View.VISIBLE : View.GONE);
        tilt.listen(st == STYLE_TILT);
        if (st == STYLE_TILT && !tilt.available()) toast("Máy không có cảm biến nghiêng");
    }

    private void selectSpeed(int i) {
        prefs.edit().putInt("speed", i).apply();
        for (int k = 0; k < 3; k++) setPill(speedPills[k], k == i, i == 2 ? Ui.ORANGE : Ui.ACCENT);
        sendConfig();
    }

    private void showMode(int m) {
        for (int k = 0; k < MODES; k++) modeTiles[k].setActive(k == m);
    }

    private void setRobotMode(int m) {
        if (m != 0) { stopDance(); stopTracking(); voiceUntil = 0; }
        if (m == 4 && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, PERM_NOTIFY);
        if (link.ready()) link.send("#A" + m);
        showMode(m);
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
        return style == STYLE_TANK ? tankL.isActive() || tankR.isActive()
             : style == STYLE_TILT ? tilt.isActive() : joy.isActive();
    }

    private void setSpin(int s) {
        spin = s;
        if (s == 0) { stopBurst = 3; if (link.ready()) link.send("#S"); }
    }

    private void horn(boolean on) { if (link.ready()) link.send(on ? "#B1" : "#B0"); }

    private void sendPan() {
        if (link.ready()) link.send("#P" + (175 - panBar.getProgress()));   // slide right = look right
    }

    private void setFlash(boolean on) {
        camLed = on;
        flashBtn.setActive(on);
        sendFlash();
    }

    /**
     * Camera flash goes over Bluetooth (the robot passes it on to the ESP32-CAM by wire, so the camera
     * view or its Wi-Fi is not needed) and also over Wi-Fi in case the robot's Bluetooth is not connected.
     */
    private void sendFlash() {
        int level = camLed ? prefs.getInt("flash_level", 100) : 0;
        int fx = prefs.getInt("flash_fx", 0);
        boolean auto = camLed && prefs.getBoolean("flash_auto", false);
        if (link.ready()) link.send("#F" + level + "," + fx + (auto ? ",1" : ""));
        if (!auto || !link.ready()) camera.setFlash(camAddress(), level, auto ? 0 : fx);
    }

    private final Runnable flashSend = this::sendFlash;

    private void sendHornAuto() {
        if (link.ready()) link.send(prefs.getBoolean("horn_auto", false) ? "#K1" : "#K0");
    }

    private void showFlashPanel() {
        LinearLayout box = vbox();
        box.setPadding(dp(24), dp(8), dp(24), dp(4));
        box.addView(Ui.text(this, "Chạm nút tia sét để bật/tắt, giữ để mở bảng này. Lệnh đi qua Bluetooth nên không cần mở camera.",
                13, Ui.MUTED, null));

        final LinearLayout control = segmented();
        final LinearLayout manualPill = pill(0, "Thủ công"), autoPill = pill(R.drawable.ic_flash, "Tự động");
        control.addView(manualPill, weight(1, 34));
        control.addView(autoPill, weight(1, 34));
        box.addView(control, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 16, 0, 0));

        final TextView lvlText = Ui.text(this, "", 14, Ui.TEXT, Ui.MEDIUM);
        box.addView(lvlText, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 16, 0, 0));
        SeekBar lvl = new SeekBar(this);
        lvl.setMax(95);   // 5..100 %
        lvl.setProgress(prefs.getInt("flash_level", 100) - 5);
        lvl.setSplitTrack(false);
        lvl.setProgressTintList(ColorStateList.valueOf(Ui.YELLOW));
        lvl.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        lvl.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        lvlText.setText("Độ sáng:  " + (lvl.getProgress() + 5) + "%");
        lvl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                lvlText.setText("Độ sáng:  " + (p + 5) + "%");
                if (!u) return;
                prefs.edit().putInt("flash_level", p + 5).apply();
                camLed = true;
                flashBtn.setActive(true);
                ui.removeCallbacks(flashSend);
                ui.postDelayed(flashSend, 120);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(lvl, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        final TextView fxTitle = Ui.text(this, "Hiệu ứng", 14, Ui.TEXT, Ui.MEDIUM);
        box.addView(fxTitle, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 6));
        final LinearLayout fxRow = segmented();
        final String[] fxNames = { "Sáng", "Nháy", "Thở", "Chớp" };
        final LinearLayout[] fxPills = new LinearLayout[fxNames.length];
        for (int i = 0; i < fxNames.length; i++) {
            fxPills[i] = pill(0, fxNames[i]);
            fxRow.addView(fxPills[i], weight(1, 34));
        }
        box.addView(fxRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView autoHelp = Ui.text(this, "Robot tự chọn: lái tay sáng khi chạy, nháy khi lùi hoặc gần vật cản, tắt sau 3 giây đứng yên · "
                + "vào chế độ tự hành nháy báo xuất phát · tránh vật nháy pha khi thấy vật cản, chớp khi lùi, nháy khi đang tìm hướng · "
                + "dò line nháy khi mất line · bám theo chớp khi bắt được mục tiêu, thở khi chờ · bấm còi thì chớp theo.", 12, Ui.MUTED, null);
        box.addView(autoHelp, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 10, 0, 0));

        box.addView(Ui.text(this, "Còi tự động khi tự hành", 14, Ui.TEXT, Ui.MEDIUM),
                margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 18, 0, 6));
        final LinearLayout hornRow = segmented();
        final LinearLayout hornOff = pill(0, "Tắt"), hornOn = pill(R.drawable.ic_horn, "Bật");
        hornRow.addView(hornOff, weight(1, 34));
        hornRow.addView(hornOn, weight(1, 34));
        box.addView(hornRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(Ui.text(this, "Như xe tự hành: \"tin tin\" trước khi lăn bánh, bíp báo lùi, \"tin tin\" khi phanh gấp lùi tránh vật, "
                + "tiếng dài khi bắt đầu thoát kẹt, 3 tiếng khi kẹt hẳn cần giúp, tiếng dài khi mất line, \"tin\" khi bắt được mục tiêu hoặc gặp vạch dừng. "
                + "Đèn ở chế độ Tự động còn nháy pha khi có vật cản phía trước.", 12, Ui.MUTED, null),
                margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 8, 0, 0));
        final Runnable hornRefresh = () -> {
            boolean on = prefs.getBoolean("horn_auto", false);
            setPill(hornOff, !on, Ui.ORANGE);
            setPill(hornOn, on, Ui.ORANGE);
        };
        hornOff.setOnClickListener(v -> { prefs.edit().putBoolean("horn_auto", false).apply(); hornRefresh.run(); sendHornAuto(); });
        hornOn.setOnClickListener(v -> {
            if (!link.ready()) toast("Còi tự động cần kết nối Bluetooth với robot");
            prefs.edit().putBoolean("horn_auto", true).apply();
            hornRefresh.run();
            sendHornAuto();
        });
        hornRefresh.run();

        final Runnable refresh = () -> {
            boolean auto = prefs.getBoolean("flash_auto", false);
            setPill(manualPill, !auto, Ui.YELLOW);
            setPill(autoPill, auto, Ui.YELLOW);
            int fx = prefs.getInt("flash_fx", 0);
            for (int i = 0; i < fxPills.length; i++) setPill(fxPills[i], !auto && i == fx, Ui.YELLOW);
            fxTitle.setVisibility(auto ? View.GONE : View.VISIBLE);
            fxRow.setVisibility(auto ? View.GONE : View.VISIBLE);
            autoHelp.setVisibility(auto ? View.VISIBLE : View.GONE);
        };
        manualPill.setOnClickListener(v -> { prefs.edit().putBoolean("flash_auto", false).apply(); refresh.run(); setFlash(true); });
        autoPill.setOnClickListener(v -> {
            if (!link.ready()) toast("Chế độ tự động cần kết nối Bluetooth với robot");
            prefs.edit().putBoolean("flash_auto", true).apply();
            refresh.run();
            setFlash(true);
        });
        for (int i = 0; i < fxPills.length; i++) {
            final int fx = i;
            fxPills[i].setOnClickListener(v -> { prefs.edit().putInt("flash_fx", fx).apply(); refresh.run(); setFlash(true); });
        }
        refresh.run();

        dialog("Đèn flash & còi tự động")
                .setView(box)
                .setPositiveButton(camLed ? "Xong" : "Bật đèn", (d, w) -> setFlash(true))
                .setNeutralButton("Tắt đèn", (d, w) -> setFlash(false))
                .show();
    }

    private void sendHeadlight() {
        if (link.ready()) link.send(headlight ? "#G40,40,40" : "#G0,0,0");
    }

    private void sendConfig() {
        ui.removeCallbacks(calibSync);
        ui.postDelayed(calibSync, 800);
        if (!link.ready()) return;
        int s = SPEEDS[prefs.getInt("speed", 1)];
        link.send("#C" + s + "," + prefs.getInt("trim_l", 0) + "," + prefs.getInt("trim_r", 0) + "," + prefs.getInt("steer_trim", 0));
    }

    /** The ESP32-CAM keeps a copy of the motor trims, so driving with a gamepad paired to the robot matches. */
    private final Runnable calibSync = () -> {
        String a = camAddress();
        final String url = (a.startsWith("http") ? a : "http://" + a) + "/config?tl=" + prefs.getInt("trim_l", 0)
                + "&tr=" + prefs.getInt("trim_r", 0) + "&st=" + prefs.getInt("steer_trim", 0);
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) { }   // not on the camera's Wi-Fi: synced next time
        }).start();
    };

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
        if (pad.active()) {
            padDriving = true;
            if (style == STYLE_TANK) cmd = "#M" + pad.leftY() + "," + pad.rightY();
            else {
                cmd = "#D" + pad.throttle() + "," + pad.steer();
                padPan();
            }
        } else if (padSpin != 0)
            cmd = "#M" + (-padSpin * SPIN) + "," + (padSpin * SPIN);
        else if (style == STYLE_TANK && (tankL.isActive() || tankR.isActive()))
            cmd = "#M" + tankL.stickY() + "," + tankR.stickY();
        else if (style == STYLE_JOY && joy.isActive())
            cmd = "#D" + joy.stickY() + "," + (-joy.stickX());   // stick right = steer right (negative)
        else if (style == STYLE_TILT && tilt.isActive())
            cmd = "#D" + tilt.throttle() + "," + tilt.steer();
        else if (spin != 0)
            cmd = "#M" + (-spin * SPIN) + "," + (spin * SPIN);
        else if (now < voiceUntil)
            cmd = "#D" + voiceT + "," + voiceS;
        else if (vision.mode() != Vision.OFF)
            cmd = trackCommand(now);
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

    // ------------------------------------------------------------------ PS5 gamepad

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (pad.handleMotion(e)) {
            if (padDriving && !pad.active()) {   // sticks and triggers released: stop now, like a touch stick
                padDriving = false;
                if (padSpin == 0 && !anyStickActive()) { stopBurst = 3; if (link.ready()) link.send("#S"); }
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return pad.handleKey(e) || super.dispatchKeyEvent(e);
    }

    @Override public void onPadConnected(String name) {
        padTile.setVisibility(name != null ? View.VISIBLE : View.GONE);
        if (name != null) {
            toast("Đã kết nối tay cầm " + name);
            padColor();
            pad.rumble(160, 0, 100, 80, 100);
            return;
        }
        toast("Tay cầm đã ngắt");
        boolean wasDriving = padDriving || padSpin != 0;
        padDriving = false;
        padSpin = 0;
        horn(false);   // in case Cross was held
        if (wasDriving) { stopBurst = 3; if (link.ready()) link.send("#S"); }
    }

    @Override public void onPadButton(int key, boolean down) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A:  horn(down); return;                        // Cross: horn while held
            case KeyEvent.KEYCODE_BUTTON_L1: padSpinKey(1, down); return;               // L1 / R1: spin on the spot
            case KeyEvent.KEYCODE_BUTTON_R1: padSpinKey(-1, down); return;
        }
        if (!down) return;
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_B:                                             // Circle: STOP, rest pose
                restPose();
                pad.rumble(200, 0, 60);
                break;
            case KeyEvent.KEYCODE_BUTTON_X: headBtn.performClick(); break;             // Square: headlight
            case KeyEvent.KEYCODE_BUTTON_Y: setFlash(!camLed); break;                  // Triangle: camera flash
            case KeyEvent.KEYCODE_DPAD_UP:   selectSpeed(Math.min(2, prefs.getInt("speed", 1) + 1)); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: selectSpeed(Math.max(0, prefs.getInt("speed", 1) - 1)); break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  panBar.setProgress(panBar.getProgress() - 20); sendPan(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: panBar.setProgress(panBar.getProgress() + 20); sendPan(); break;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: panBar.setProgress(80); sendPan(); break;               // R3: camera straight
            case KeyEvent.KEYCODE_BUTTON_THUMBL: selectStyle(style == STYLE_TANK ? STYLE_JOY : STYLE_TANK); break;   // L3: car / tank
            case KeyEvent.KEYCODE_BUTTON_START: setRobotMode(robotMode < 0 ? 0 : (robotMode + 1) % MODES); break;   // Options: next mode
            case KeyEvent.KEYCODE_BUTTON_SELECT: if (!radar.isScanning()) startRadar(); break;                    // Create: radar sweep
        }
    }

    private void padSpinKey(int dir, boolean down) {
        if (down) padSpin = dir;
        else if (padSpin == dir) {
            padSpin = 0;
            if (!pad.active() && !anyStickActive()) { stopBurst = 3; if (link.ready()) link.send("#S"); }
        }
    }

    /** Car style: the right stick turns the camera, faster the further it is pushed. */
    private void padPan() {
        int x = pad.rightX();
        if (x == 0 || vision.mode() != Vision.OFF) return;   // tracking owns the camera
        int p = panBar.getProgress();
        panBar.setProgress(p + x * 8 / 100);
        if (panBar.getProgress() != p) sendPan();
    }

    /** Light bar shows the robot's mode colour; dim while the robot is not connected. */
    private void padColor() {
        if (!pad.connected()) return;
        pad.setColor(!link.ready() || robotMode < 0 ? 0x101830 : MODE_COLORS[robotMode]);
    }

    private void showPadHelp() {
        String status = pad.connected() ? "Đang dùng: " + pad.name() + "\n\n" : "Chưa có tay cầm.\n\n";
        dialog("Tay cầm PS5")
                .setMessage(status
                        + "Kết nối: giữ nút PS + Create (nút nhỏ bên trái touchpad) tới khi đèn nháy nhanh, rồi vào Cài đặt Bluetooth "
                        + "của điện thoại chọn \"DualSense Wireless Controller\". Lần sau chỉ cần bấm nút PS.\n\n"
                        + "Kiểu Joystick / Nghiêng (lái như ô tô):\n"
                        + "  R2 tiến · L2 lùi (bóp nhẹ = chậm)\n"
                        + "  Cần trái: đánh lái (đẩy lên/xuống cũng tiến/lùi)\n"
                        + "  Cần phải: quay camera\n"
                        + "Kiểu Hai bên (xe tăng): cần trái = bánh trái, cần phải = bánh phải\n\n"
                        + "✕ còi · ○ DỪNG · □ đèn pha · △ đèn flash\n"
                        + "L1 / R1: xoay tại chỗ trái / phải\n"
                        + "D-pad ↑↓ tốc độ · ←→ quay camera · R3 camera thẳng\n"
                        + "L3: đổi kiểu lái ô tô ↔ xe tăng\n"
                        + "Options: chế độ kế tiếp · Create: quét radar\n\n"
                        + "Thanh đèn tay cầm mang màu chế độ hiện tại. Tay cầm rung khi kết nối robot, khi xe tự phanh "
                        + "vì vật cản, khi bị kẹt và khi canh gác báo động.")
                .setPositiveButton("Đóng", null)
                .show();
    }

    // ------------------------------------------------------------------ link callbacks

    @Override public void onLinkState(int state, String message) {
        subtitle.setText(message);
        int c = state == BleLink.READY ? Ui.GREEN : state == BleLink.OFF ? Ui.MUTED : Ui.YELLOW;
        linkText.setText(state == BleLink.READY ? "Đã kết nối" : state == BleLink.OFF ? "Ngắt" : "Đang tìm");
        linkText.setTextColor(c);
        linkIcon.getDrawable().setTint(c);
        padColor();
        if (state == BleLink.READY) {
            pad.rumble(120, 0, 120);
            sendConfig();
            sendHeadlight();
            sendPan();
            if (camLed) sendFlash();
            sendHornAuto();
            sendLights();
        } else {
            camera.clearHud();
            telemetry.clear();
            showMode(-1);
            robotMode = -1;
            lastFlags = 0;
        }
    }

    @Override public void onLine(String line) {
        if (line.startsWith("#R")) {   // radar reading: offset,cm
            String[] q = line.substring(2).split(",");
            try { radar.add(Integer.parseInt(q[0].trim()), Integer.parseInt(q[1].trim())); } catch (Exception ignored) { }
            return;
        }
        if (line.equals("#E")) { radar.finishScan(); return; }
        if (line.startsWith("#W")) {   // patrol alarm: offset,cm
            String[] q = line.substring(2).split(",");
            try { onAlarm(Integer.parseInt(q[0].trim()), Integer.parseInt(q[1].trim())); } catch (Exception ignored) { }
            return;
        }
        if (!line.startsWith("#T")) return;
        String[] p = line.substring(2).split(",");
        if (p.length < 7) return;
        int[] v = new int[7];
        try { for (int i = 0; i < 7; i++) v[i] = Integer.parseInt(p[i].trim()); }
        catch (NumberFormatException e) { return; }
        int mode = v[0];
        if (mode < 0 || mode >= MODES) return;
        if (mode != robotMode) { robotMode = mode; padColor(); }
        int fresh = v[2] & ~lastFlags;
        lastFlags = v[2];
        if ((fresh & 0x08) != 0 && mode == 0) pad.rumble(200, 0, 150);    // collision guard braked while driving by hand
        if ((fresh & 0x80) != 0) pad.rumble(255, 0, 120, 80, 120);         // stuck
        showMode(mode);
        camera.setHud(MODE_HUD[mode], MODE_COLORS[mode], v[1], v[2], mode);
        telemetry.set(v[3], v[4], v[5], v[6]);
    }

    // ------------------------------------------------------------------ voice commands

    private void toggleListening() {
        if (listening) { if (speech != null) speech.stopListening(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, PERM_VOICE);
            return;
        }
        startListening();
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Máy chưa có dịch vụ nhận giọng nói (cần ứng dụng Google)");
            return;
        }
        if (speech == null) {
            speech = SpeechRecognizer.createSpeechRecognizer(this);
            speech.setRecognitionListener(voiceListener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        speech.startListening(i);
        setListening(true);
        subtitle.setText("Đang nghe... hãy nói lệnh");
    }

    private void setListening(boolean on) {
        listening = on;
        if (voiceTile != null) voiceTile.setActive(on);
    }

    private final RecognitionListener voiceListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float db) { }
        @Override public void onBufferReceived(byte[] buf) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle b) { }
        @Override public void onEvent(int type, Bundle b) { }

        @Override public void onError(int error) {
            setListening(false);
            boolean unclear = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            subtitle.setText(unclear ? "Không nghe rõ · bấm micro để nói lại" : "Lỗi nhận giọng nói (mã " + error + ")");
        }

        @Override public void onResults(Bundle b) {
            setListening(false);
            onVoice(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
        }
    };

    private void onVoice(List<String> heard) {
        if (heard == null || heard.isEmpty()) { subtitle.setText("Không nghe rõ · bấm micro để nói lại"); return; }
        for (String h : heard) {
            int[] c = VoiceCommands.parse(h);
            if (c == null) continue;
            subtitle.setText("Đã nghe: \"" + h + "\"");
            runVoice(c);
            return;
        }
        subtitle.setText("Chưa hiểu \"" + heard.get(0) + "\" · giữ nút Giọng nói để xem các lệnh");
    }

    private void runVoice(int[] c) {
        switch (c[0]) {
            case VoiceCommands.STOP:
                voiceUntil = 0;
                spin = 0;
                stopDance();
                stopTracking();
                panBar.setProgress(80);
                if (link.ready()) link.send("#R");
                break;
            case VoiceCommands.FORWARD:    voiceDrive(60, 0, 1500); break;
            case VoiceCommands.BACK:       voiceDrive(-50, 0, 1200); break;
            case VoiceCommands.LEFT:       voiceDrive(55, 100, 1300); break;
            case VoiceCommands.RIGHT:      voiceDrive(55, -100, 1300); break;
            case VoiceCommands.SPIN_LEFT:  voiceDrive(0, 100, 800); break;   // no throttle + full steer = spin on the spot
            case VoiceCommands.SPIN_RIGHT: voiceDrive(0, -100, 800); break;
            case VoiceCommands.U_TURN:     voiceDrive(0, 100, 1700); break;
            case VoiceCommands.HORN:
                horn(true);
                ui.postDelayed(() -> horn(false), 450);
                break;
            case VoiceCommands.LIGHT_ON:    setFlash(true); break;
            case VoiceCommands.LIGHT_OFF:   setFlash(false); break;
            case VoiceCommands.MODE:        setRobotMode(c[1]); break;
            case VoiceCommands.DANCE:       if (!beats.isRunning()) toggleDance(); break;
            case VoiceCommands.RADAR:       startRadar(); break;
            case VoiceCommands.TRACK_COLOR: if (vision.mode() != Vision.COLOR) toggleTracking(Vision.COLOR); break;
            case VoiceCommands.TRACK_FACE:  if (vision.mode() != Vision.FACE) toggleTracking(Vision.FACE); break;
            case VoiceCommands.SPEED:       selectSpeed(c[1]); break;
        }
    }

    private void voiceDrive(int t, int s, long ms) {
        if (!link.ready()) { toast("Chưa kết nối robot"); return; }
        stopDance();
        stopTracking();
        voiceT = t;
        voiceS = s;
        voiceUntil = SystemClock.uptimeMillis() + ms;
    }

    private void showVoiceHelp() {
        dialog("Lệnh giọng nói")
                .setMessage("Bấm nút Giọng nói rồi nói một lệnh (tiếng Việt):\n\n"
                        + "• tiến / lùi / rẽ trái / rẽ phải\n"
                        + "• xoay trái / xoay phải / quay đầu\n"
                        + "• dừng lại\n"
                        + "• bóp còi · bật đèn · tắt đèn\n"
                        + "• nhanh / vừa / chậm\n"
                        + "• tránh vật · dò line · bám theo · canh gác · lái tay\n"
                        + "• nhảy · quét radar · bám màu · bám mặt\n\n"
                        + "Cần dịch vụ nhận giọng nói của Google trên máy.")
                .setPositiveButton("Đóng", null)
                .show();
    }

    // ------------------------------------------------------------------ dance

    private void toggleDance() {
        if (beats.isRunning()) { stopDance(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, PERM_DANCE);
            return;
        }
        startDance();
    }

    private void startDance() {
        if (!link.ready()) { toast("Chưa kết nối robot"); return; }
        stopTracking();
        voiceUntil = 0;
        if (!beats.start()) { toast("Không mở được micro"); return; }
        link.send("#A0");
        showMode(0);
        danceStep = 0;
        danceTile.setActive(true);
        subtitle.setText("Đang nghe nhạc · mở nhạc to gần điện thoại");
    }

    private void stopDance() {
        if (!beats.isRunning()) return;
        beats.stop();
        danceTile.setActive(false);
    }

    private void onBeat(float strength) {
        if (!link.ready()) return;
        link.send("#X" + DANCE_STEPS[danceStep++ % DANCE_STEPS.length]);
        danceTile.setActive(false);   // flicker the tile on every beat
        ui.postDelayed(() -> danceTile.setActive(beats.isRunning()), 90);
    }

    // ------------------------------------------------------------------ colour / face tracking

    private void toggleTracking(int m) {
        if (vision.mode() == m) { stopTracking(); return; }
        if (!link.ready()) { toast("Chưa kết nối robot"); return; }
        stopDance();
        voiceUntil = 0;
        if (!camera.isRunning()) startCamera();
        target = null;
        targetSeen = 0;
        trackPan = 175 - panBar.getProgress();
        link.send("#A0");
        showMode(0);
        pickColor = false;
        if (m == Vision.COLOR) {
            int rgb = prefs.getInt("track_rgb", 0);
            pickColor = rgb == 0 || !vision.setColor(rgb);
            toast(pickColor ? "Chạm vào vật trên hình camera để chọn màu cần bám"
                            : "Bám màu đã chọn lần trước · chạm vào hình để chọn màu khác");
        } else {
            toast("Đang tìm khuôn mặt · đứng trước camera");
        }
        vision.setMode(m);
        colorTile.setActive(m == Vision.COLOR);
        faceTile.setActive(m == Vision.FACE);
    }

    private void stopTracking() {
        if (vision == null || vision.mode() == Vision.OFF) return;
        vision.setMode(Vision.OFF);
        colorTile.setActive(false);
        faceTile.setActive(false);
        camera.setTrack(null, 0, null);
        target = null;
        stopBurst = 3;
        if (link.ready()) link.send("#S");
    }

    private void pickColorAt(float x, float y) {
        int c = camera.colorAt(x, y);
        if (c == 0) { toast("Chưa có hình camera"); return; }
        if (!vision.setColor(c)) { toast("Màu này nhạt quá · chọn vật có màu rực hơn"); return; }
        prefs.edit().putInt("track_rgb", c).apply();
        pickColor = false;
        target = null;
        toast("Đã chọn màu · robot bắt đầu bám theo");
    }

    private void onVision(Vision.Result r) {
        long now = SystemClock.uptimeMillis();
        if (r.mode == Vision.COLOR && pickColor) return;
        if (!r.found) {
            if (now - targetSeen > 700) camera.setTrack(null, 0, null);
            return;
        }
        boolean fresh = now - targetSeen > 3000;
        target = r;
        targetSeen = now;
        // the camera rides on the pan servo: turn it toward the target (bigger angle = left)
        trackPan = Math.max(25, Math.min(165, trackPan - Math.round((r.cx - 0.5f) * 34)));
        panBar.setProgress(175 - trackPan);
        boolean face = r.mode == Vision.FACE;
        camera.setTrack(r.box, face ? Ui.CYAN : Ui.ORANGE, face ? "Khuôn mặt" : "Mục tiêu");
        if (face && fresh && link.ready()) {   // say hello: hazard lights and "beep beep"
            link.send("#Y4");
            horn(true);
            ui.postDelayed(() -> horn(false), 150);
            ui.postDelayed(() -> horn(true), 300);
            ui.postDelayed(() -> horn(false), 450);
        }
    }

    /** Body follows where the camera looks; distance from the target's apparent size. */
    private String trackCommand(long now) {
        if (target == null || now - targetSeen > 700) return null;   // lost: stop, keep looking that way
        if (now - lastPanCmd > 90) { link.send("#P" + trackPan); lastPanCmd = now; }
        int po = trackPan - 95;                                   // + = target is to the left
        float goal = target.mode == Vision.FACE ? 0.07f : 0.05f;
        float e = (goal - target.size) / goal;                    // + = too far away
        int t = Math.abs(e) < 0.25f ? 0 : Math.max(-45, Math.min(60, Math.round(e * 70)));
        t = t * Math.max(0, 50 - Math.abs(po)) / 50;             // turn toward it first, then drive
        int s = Math.abs(po) < 8 ? 0 : Math.max(-100, Math.min(100, Math.round(po * 2.5f)));
        if (t == 0 && Math.abs(s) <= 35) return null;
        return "#D" + t + "," + s;
    }

    // ------------------------------------------------------------------ patrol alarm

    private static String direction(int off) {
        return Math.abs(off) < 15 ? "phía trước" : (off > 0 ? "bên trái " : "bên phải ") + Math.abs(off) + "°";
    }

    private void onAlarm(int off, int cm) {
        String msg = "Có chuyển động " + direction(off) + ", cách " + cm + " cm";
        subtitle.setText("BÁO ĐỘNG · " + msg);
        toast("Báo động: " + msg);
        Vibrator vib = getSystemService(Vibrator.class);
        if (vib != null) vib.vibrate(VibrationEffect.createWaveform(new long[] { 0, 400, 200, 400, 200, 700 }, -1));
        if (!camera.isRunning()) startCamera();   // the camera is already turned toward it
        pad.rumble(255, 0, 400, 200, 400, 200, 700);

        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ALARM_CHANNEL)
                                                              : new Notification.Builder(this);
        nb.setSmallIcon(R.drawable.ic_shield)
          .setContentTitle("Zin Robot · báo động")
          .setContentText(msg)
          .setAutoCancel(true)
          .setContentIntent(android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(1, nb.build());

        String all = prefs.getString("alarms", "");
        String[] old = all.isEmpty() ? new String[0] : all.split("\n");
        StringBuilder sb = new StringBuilder(System.currentTimeMillis() + ";" + off + ";" + cm);
        for (int i = 0; i < old.length && i < 29; i++) sb.append('\n').append(old[i]);   // newest first, keep 30
        prefs.edit().putString("alarms", sb.toString()).apply();
    }

    private void showAlarmHistory() {
        String all = prefs.getString("alarms", "");
        if (all.isEmpty()) { toast("Chưa có lần báo động nào"); return; }
        String[] e = all.split("\n");
        String[] items = new String[e.length];
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault());
        for (int i = 0; i < e.length; i++) {
            String[] q = e[i].split(";");
            items[i] = fmt.format(new java.util.Date(Long.parseLong(q[0]))) + " · " + direction(Integer.parseInt(q[1])) + " · " + q[2] + " cm";
        }
        dialog("Lịch sử báo động")
                .setItems(items, null)
                .setNeutralButton("Xóa tất cả", (d, w) -> { prefs.edit().remove("alarms").apply(); toast("Đã xóa"); })
                .setNegativeButton("Đóng", null)
                .show();
    }

    // ------------------------------------------------------------------ car lights on the RGB matrix

    private void sendLights() {
        if (!link.ready()) return;
        link.send("#J" + (prefs.getBoolean("sig_lights", true) ? 1 : 0));
        link.send("#L" + prefs.getInt("led_layout", 0) + "," + prefs.getInt("led_flags", 0));
    }

    private LinearLayout pillRow(LinearLayout box, String title, String... names) {
        if (title != null)
            box.addView(Ui.text(this, title, 14, Ui.TEXT, Ui.MEDIUM),
                    margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 16, 0, 6));
        LinearLayout row = segmented();
        for (String n : names) row.addView(pill(0, n), weight(1, 34));
        box.addView(row, margins(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, title == null ? 6 : 0, 0, 0));
        return row;
    }

    private void showLightsSettings() {
        LinearLayout box = vbox();
        box.setPadding(dp(24), dp(8), dp(24), dp(4));
        box.addView(Ui.text(this, "Ma trận LED làm đèn xe: xi-nhan khi rẽ, đèn khẩn cấp khi gặp sự cố, "
                + "đèn phanh khi giảm tốc, đèn lùi khi lùi, đèn cảnh sát khi báo động.", 13, Ui.MUTED, null));
        final LinearLayout onRow = pillRow(box, "Đèn tín hiệu", "Tắt", "Bật");
        final LinearLayout layRow = pillRow(box, "Bố cục ma trận LED", "8 cột × 5 hàng", "5 cột × 8 hàng", "Dải");
        final LinearLayout flagRow = pillRow(box, null, "Đảo trái/phải", "Lật trên/dưới", "Zigzag");
        final LinearLayout testRow = pillRow(box, "Thử đèn (3 giây)", "Trái", "Phải", "Phanh", "Khẩn cấp");
        box.addView(Ui.text(this, "Nếu bấm \"Trái\" mà bên phải nháy thì bật Đảo trái/phải. Nếu đèn phanh không nằm ở hàng dưới "
                + "thì đổi bố cục hoặc Lật trên/dưới. Đèn bị xé hình thì thử Zigzag.", 12, Ui.MUTED, null),
                margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 12, 0, 0));

        final Runnable refresh = () -> {
            boolean on = prefs.getBoolean("sig_lights", true);
            setPill((LinearLayout) onRow.getChildAt(0), !on, Ui.ACCENT);
            setPill((LinearLayout) onRow.getChildAt(1), on, Ui.ACCENT);
            int lay = prefs.getInt("led_layout", 0), fl = prefs.getInt("led_flags", 0);
            for (int i = 0; i < 3; i++) setPill((LinearLayout) layRow.getChildAt(i), i == lay, Ui.ACCENT);
            for (int i = 0; i < 3; i++) setPill((LinearLayout) flagRow.getChildAt(i), (fl & (1 << i)) != 0, Ui.ORANGE);
            for (int i = 0; i < 4; i++) setPill((LinearLayout) testRow.getChildAt(i), false, Ui.ACCENT);
        };
        for (int i = 0; i < 2; i++) {
            final boolean on = i == 1;
            onRow.getChildAt(i).setOnClickListener(v -> { prefs.edit().putBoolean("sig_lights", on).apply(); refresh.run(); sendLights(); });
        }
        for (int i = 0; i < 3; i++) {
            final int lay = i, bit = 1 << i;
            layRow.getChildAt(i).setOnClickListener(v -> { prefs.edit().putInt("led_layout", lay).apply(); refresh.run(); sendLights(); });
            flagRow.getChildAt(i).setOnClickListener(v -> {
                prefs.edit().putInt("led_flags", prefs.getInt("led_flags", 0) ^ bit).apply();
                refresh.run();
                sendLights();
            });
        }
        for (int i = 0; i < 4; i++) {
            final int test = i + 1;
            testRow.getChildAt(i).setOnClickListener(v -> {
                if (!link.ready()) { toast("Chưa kết nối robot"); return; }
                link.send("#Y" + test);
            });
        }
        refresh.run();
        dialog("Đèn xe trên ma trận LED")
                .setView(box)
                .setPositiveButton("Xong", null)
                .show();
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
        TextView padLink = Ui.text(this, "Tay cầm PS5  ›", 14, Ui.CYAN, Ui.MEDIUM);
        padLink.setPadding(0, dp(10), 0, dp(10));
        padLink.setOnClickListener(v -> showPadHelp());
        box.addView(padLink, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 10, 0, 0));
        TextView ledLink = Ui.text(this, "Đèn xi-nhan, phanh & bố cục LED  ›", 14, Ui.CYAN, Ui.MEDIUM);
        ledLink.setPadding(0, dp(10), 0, dp(10));
        ledLink.setOnClickListener(v -> showLightsSettings());
        box.addView(ledLink, margins(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 10, 0, 0));
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
