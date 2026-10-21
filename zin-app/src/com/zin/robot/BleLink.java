package com.zin.robot;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BLE link to the robot's BT05 (HM-10 style UART bridge, service FFE0 / characteristic FFE1).
 * Finds the robot by remembered address, by name ("Zin...") or by the FFE0 service, connects,
 * reconnects on its own, queues writes (with response) and splits notifications into lines.
 */
public class BleLink {
    public interface Listener {
        void onLinkState(int state, String message);
        void onLine(String line);
    }

    public static final int OFF = 0, SEARCHING = 1, CONNECTING = 2, READY = 3;

    static final UUID FFE0 = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    static final UUID FFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String KEY_ADDR = "ble_addr";

    public static class Found {
        public BluetoothDevice device;
        public String name;
        public int rssi;
        public boolean uart;
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private int state = OFF;
    private boolean wanted, scanning;
    private final List<Found> found = new ArrayList<>();
    private final StringBuilder rxBuf = new StringBuilder();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean writing;

    public BleLink(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        prefs = ctx.getSharedPreferences("robot", Context.MODE_PRIVATE);
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = m != null ? m.getAdapter() : null;
    }

    public int state() { return state; }
    public boolean ready() { return state == READY; }
    public List<Found> found() { return found; }

    private void setState(final int s, final String msg) {
        state = s;
        ui.post(new Runnable() { @Override public void run() { listener.onLinkState(s, msg); } });
    }

    // ------------------------------------------------------------------ public control

    /** Keep the robot connected: search, connect and reconnect until stop(). */
    public void start() {
        wanted = true;
        if (state == OFF) scan();
    }

    public void stop() {
        wanted = false;
        stopScan();
        close();
        setState(OFF, "Đã ngắt");
    }

    /** Forget the remembered robot and search again (used by the device picker). */
    public void connectTo(BluetoothDevice d) {
        stopScan();
        close();
        wanted = true;
        connect(d);
    }

    public void forget() { prefs.edit().remove(KEY_ADDR).apply(); }

    // ------------------------------------------------------------------ scanning

    private final Runnable rescan = new Runnable() {
        @Override public void run() { if (wanted && state != READY && state != CONNECTING) scan(); }
    };

    private void scan() {
        if (adapter == null) { setState(OFF, "Máy không có Bluetooth"); return; }
        if (!adapter.isEnabled()) {
            setState(OFF, "Hãy bật Bluetooth");
            ui.postDelayed(rescan, 3000);
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { ui.postDelayed(rescan, 3000); return; }
        found.clear();
        try {
            scanner.startScan(scanCb);
            scanning = true;
            setState(SEARCHING, "Đang tìm Zin Robot...");
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (scanning && state == SEARCHING) {
                        stopScan();
                        setState(SEARCHING, "Chưa thấy robot - bật nguồn robot? Đang thử lại...");
                        ui.postDelayed(rescan, 2000);
                    }
                }
            }, 10000);
        } catch (SecurityException e) {
            setState(OFF, "Thiếu quyền Bluetooth");
        }
    }

    private void stopScan() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
        }
        scanning = false;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
            if (name == null) {
                try { name = r.getDevice().getName(); } catch (SecurityException ignored) { }
            }
            boolean uart = false;
            if (r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null)
                for (ParcelUuid u : r.getScanRecord().getServiceUuids())
                    if (u.getUuid().equals(FFE0)) uart = true;

            Found f = null;
            for (Found x : found) if (x.device.getAddress().equals(r.getDevice().getAddress())) f = x;
            if (f == null) { f = new Found(); f.device = r.getDevice(); found.add(f); }
            f.rssi = r.getRssi();
            if (name != null) f.name = name;
            f.uart |= uart;

            String saved = prefs.getString(KEY_ADDR, null);
            boolean match = saved != null ? f.device.getAddress().equals(saved)
                                          : (f.name != null && f.name.toLowerCase().contains("zin"));
            if (match && wanted && state == SEARCHING) {
                stopScan();
                connect(f.device);
            }
        }

        @Override public void onScanFailed(int code) {
            scanning = false;
            setState(SEARCHING, "Lỗi quét (" + code + "), thử lại...");
            ui.postDelayed(rescan, 3000);
        }
    };

    // ------------------------------------------------------------------ connection

    private void connect(BluetoothDevice d) {
        setState(CONNECTING, "Đang kết nối...");
        try {
            gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            setState(OFF, "Thiếu quyền Bluetooth");
        }
    }

    private void close() {
        queue.clear();
        writing = false;
        tx = null;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                ui.post(new Runnable() {
                    @Override public void run() { try { g.discoverServices(); } catch (SecurityException ignored) { } }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (gatt == g) close();
                        else try { g.close(); } catch (Exception ignored) { }
                        if (wanted) {
                            setState(SEARCHING, "Mất kết nối - đang nối lại...");
                            ui.postDelayed(rescan, 800);
                        } else {
                            setState(OFF, "Đã ngắt");
                        }
                    }
                });
            }
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, int status) {
            BluetoothGattCharacteristic best = null;
            for (BluetoothGattService s : g.getServices())
                for (BluetoothGattCharacteristic c : s.getCharacteristics())
                    if (c.getUuid().equals(FFE1)) best = c;
            if (best == null) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        setState(SEARCHING, "Thiết bị này không phải robot, tìm lại...");
                        forget();
                        close();
                        ui.postDelayed(rescan, 800);
                    }
                });
                return;
            }
            final BluetoothGattCharacteristic ch = best;
            ui.post(new Runnable() {
                @Override public void run() {
                    tx = ch;
                    try {
                        g.setCharacteristicNotification(ch, true);
                        BluetoothGattDescriptor d = ch.getDescriptor(CCCD);
                        if (d != null) writeDescriptor(g, d);
                    } catch (SecurityException ignored) { }
                    prefs.edit().putString(KEY_ADDR, g.getDevice().getAddress()).apply();
                    rxBuf.setLength(0);
                    setState(READY, "Đã kết nối");
                }
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            ui.post(new Runnable() { @Override public void run() { release(); } });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            received(value);
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) received(c.getValue());
        }
    };

    @SuppressWarnings("deprecation")
    private void writeDescriptor(BluetoothGatt g, BluetoothGattDescriptor d) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(d);
        }
    }

    private void received(byte[] value) {
        if (value == null) return;
        final String chunk = new String(value);
        ui.post(new Runnable() {
            @Override public void run() {
                rxBuf.append(chunk);
                int nl;
                while ((nl = rxBuf.indexOf("\n")) >= 0) {
                    String line = rxBuf.substring(0, nl).trim();
                    rxBuf.delete(0, nl + 1);
                    if (!line.isEmpty()) listener.onLine(line);
                }
                if (rxBuf.length() > 256) rxBuf.setLength(0);
            }
        });
    }

    // ------------------------------------------------------------------ writing

    /** Queue one protocol line (without the trailing newline). Must be called on the UI thread. */
    public void send(String line) {
        if (state != READY) return;
        if (queue.size() > 10) queue.poll();   // stale commands are worthless for driving
        queue.add((line + "\n").getBytes());
        pump();
    }

    private final Runnable releaseRun = new Runnable() { @Override public void run() { release(); } };

    private void release() {
        ui.removeCallbacks(releaseRun);
        writing = false;
        pump();
    }

    @SuppressWarnings("deprecation")
    private void pump() {
        if (writing || queue.isEmpty() || gatt == null || tx == null) return;
        byte[] b = queue.poll();
        boolean withResp = (tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        int type = withResp ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(tx, b, type) == 0;
            } else {
                tx.setWriteType(type);
                tx.setValue(b);
                ok = gatt.writeCharacteristic(tx);
            }
        } catch (SecurityException e) {
            ok = false;
        }
        writing = true;
        // with a response the callback frees the queue; otherwise (or on failure) a short timer does
        ui.postDelayed(releaseRun, ok && withResp ? 300 : 30);
    }
}
