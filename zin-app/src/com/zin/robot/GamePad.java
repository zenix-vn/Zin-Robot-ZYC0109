package com.zin.robot;

import android.content.Context;
import android.hardware.input.InputManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * A Bluetooth gamepad (PS5 DualSense, also PS4 / Xbox) paired with the phone, not with the robot: the robot's
 * BT05 is BLE only, so the phone reads the pad and drives the robot over the existing link.
 * Android 12+ drives the DualSense natively (hid-playstation), which also gives rumble and the light bar.
 */
class GamePad implements InputManager.InputDeviceListener {
    interface Listener {
        void onPadConnected(String name);   // null = disconnected
        void onPadButton(int keyCode, boolean down);   // face / shoulder / menu buttons and D-pad (as KEYCODE_DPAD_*)
    }

    private static final float DEAD = 0.12f;
    private static final int SONY = 0x054C;

    private final InputManager im;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int deviceId = -1;
    private float lx, ly, rx, ry, l2, r2;
    private int hatX, hatY;
    private Object lights;   // LightsManager.LightsSession on API 31+

    GamePad(Context c, Listener l) {
        im = c.getSystemService(InputManager.class);
        listener = l;
    }

    void start() {
        im.registerInputDeviceListener(this, ui);
        deviceId = -1;
        for (int id : im.getInputDeviceIds()) if (isPad(InputDevice.getDevice(id))) { attach(id); break; }
    }

    void stop() {
        im.unregisterInputDeviceListener(this);
        closeLights();
        reset();
    }

    boolean connected() { return deviceId != -1; }

    String name() {
        InputDevice d = InputDevice.getDevice(deviceId);
        if (d == null) return null;
        if (d.getVendorId() == SONY && (d.getProductId() == 0x0CE6 || d.getProductId() == 0x0DF2)) return "DualSense";
        return d.getName();
    }

    // ------------------------------------------------------------------ state, -100..100

    /** Any stick or trigger off centre: the pad is driving. */
    boolean active() {
        return lx != 0 || ly != 0 || rx != 0 || ry != 0 || l2 != 0 || r2 != 0;
    }

    /** Car driving: R2 forward, L2 reverse; left stick up / down works too when the triggers are free. */
    int throttle() { return l2 != 0 || r2 != 0 ? pct(r2 - l2) : pct(-ly); }

    /** + = left, as the robot's #D expects. */
    int steer() { return pct(-lx); }

    int leftY() { return pct(-ly); }
    int rightY() { return pct(-ry); }
    int rightX() { return pct(rx); }
    boolean triggers() { return l2 != 0 || r2 != 0; }

    private static int pct(float v) { return Math.round(Math.max(-1f, Math.min(1f, v)) * 100); }

    // ------------------------------------------------------------------ input

    boolean handleMotion(MotionEvent e) {
        if (e.getDeviceId() != deviceId || e.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        InputDevice d = e.getDevice();
        lx = axis(e, d, MotionEvent.AXIS_X);
        ly = axis(e, d, MotionEvent.AXIS_Y);
        rx = axis(e, d, MotionEvent.AXIS_Z);
        ry = axis(e, d, MotionEvent.AXIS_RZ);
        l2 = Math.max(axis(e, d, MotionEvent.AXIS_LTRIGGER), axis(e, d, MotionEvent.AXIS_BRAKE));
        r2 = Math.max(axis(e, d, MotionEvent.AXIS_RTRIGGER), axis(e, d, MotionEvent.AXIS_GAS));
        // D-pad comes as a hat; turn its edges into key presses
        int hx = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_X)), hy = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (hx != hatX) {
            if (hatX != 0) listener.onPadButton(hatX < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT, false);
            if (hx != 0) listener.onPadButton(hx < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT, true);
            hatX = hx;
        }
        if (hy != hatY) {
            if (hatY != 0) listener.onPadButton(hatY < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, false);
            if (hy != 0) listener.onPadButton(hy < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, true);
            hatY = hy;
        }
        return true;
    }

    /** Dead zone around the centre, rescaled so the output still starts at 0 and reaches 1. */
    private static float axis(MotionEvent e, InputDevice d, int a) {
        if (d == null || d.getMotionRange(a, e.getSource()) == null) return 0;
        float v = e.getAxisValue(a);
        if (Math.abs(v) < DEAD) return 0;
        return Math.signum(v) * (Math.abs(v) - DEAD) / (1 - DEAD);
    }

    boolean handleKey(KeyEvent e) {
        if (e.getDeviceId() != deviceId) return false;
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BUTTON_MODE || k == KeyEvent.KEYCODE_HOME) return false;   // PS button stays with the system
        if (k == KeyEvent.KEYCODE_BUTTON_L2 || k == KeyEvent.KEYCODE_BUTTON_R2) return true;  // read as analog axes
        if (e.getRepeatCount() == 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) listener.onPadButton(k, true);
            else if (e.getAction() == KeyEvent.ACTION_UP) listener.onPadButton(k, false);
        }
        return true;   // also keeps Circle (BUTTON_B) from acting as BACK
    }

    private void reset() {
        lx = ly = rx = ry = l2 = r2 = 0;
        hatX = hatY = 0;
    }

    // ------------------------------------------------------------------ devices

    private static boolean isPad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            && (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void attach(int id) {
        deviceId = id;
        reset();
        openLights();
        listener.onPadConnected(name());
    }

    @Override public void onInputDeviceAdded(int id) {
        if (deviceId == -1 && isPad(InputDevice.getDevice(id))) attach(id);
    }

    @Override public void onInputDeviceRemoved(int id) {
        if (id != deviceId) return;
        deviceId = -1;
        reset();   // stops driving at once: active() is false
        closeLights();
        listener.onPadConnected(null);
        for (int other : im.getInputDeviceIds()) if (isPad(InputDevice.getDevice(other))) { attach(other); break; }
    }

    @Override public void onInputDeviceChanged(int id) { }

    // ------------------------------------------------------------------ feedback

    /** Rumble: timings in ms (off, on, off, on...), strength 1..255. */
    void rumble(int strength, long... timings) {
        InputDevice d = InputDevice.getDevice(deviceId);
        if (d == null) return;
        int[] amps = new int[timings.length];
        for (int i = 0; i < amps.length; i++) amps[i] = i % 2 == 1 ? strength : 0;
        VibrationEffect fx = VibrationEffect.createWaveform(timings, amps, -1);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = d.getVibratorManager();
                if (vm.getVibratorIds().length > 0) vm.vibrate(CombinedVibration.createParallel(fx));
            } else {
                Vibrator v = d.getVibrator();
                if (v.hasVibrator()) v.vibrate(fx);
            }
        } catch (RuntimeException ignored) { }   // pads without (accessible) motors
    }

    /** Light bar colour (DualSense / DualShock 4, Android 12+). */
    void setColor(int argb) {
        if (Build.VERSION.SDK_INT < 31 || lights == null) return;
        InputDevice d = InputDevice.getDevice(deviceId);
        if (d == null) return;
        try {
            LightsRequest.Builder b = new LightsRequest.Builder();
            for (Light l : d.getLightsManager().getLights()) {
                if (l.hasRgbControl()) b.addLight(l, new LightState.Builder().setColor(argb | 0xFF000000).build());
                else if (l.getType() == Light.LIGHT_TYPE_PLAYER_ID) b.addLight(l, new LightState.Builder().setPlayerId(1).build());
            }
            ((LightsManager.LightsSession) lights).requestLights(b.build());
        } catch (RuntimeException ignored) { }
    }

    private void openLights() {
        if (Build.VERSION.SDK_INT < 31) return;
        InputDevice d = InputDevice.getDevice(deviceId);
        try { if (d != null) lights = d.getLightsManager().openSession(); } catch (RuntimeException ignored) { }
    }

    private void closeLights() {
        if (Build.VERSION.SDK_INT < 31 || lights == null) return;
        try { ((LightsManager.LightsSession) lights).close(); } catch (RuntimeException ignored) { }
        lights = null;
    }
}
