package com.example.g1text;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

/** Small Android BLE transport bridge. UI and text packet generation live in Python. */
public final class G1BluetoothBridge {
    public interface Listener {
        void onStatus(String message);
        void onDeviceFound(String side, String name, String address);
        void onConnected();
    }

    private static final UUID UART_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_TX = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_RX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private Peer left;
    private Peer right;
    private boolean connectedCallbackSent = false;
    private boolean scanning = false;
    private int heartbeatSequence = 0;
    private boolean heartbeatStarted = false;

    public G1BluetoothBridge(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
    }

    private void status(String message) {
        try { listener.onStatus(message); } catch (Exception ignored) { }
    }

    public synchronized void startScan() {
        if (adapter == null) { status("This phone does not support Bluetooth."); return; }
        if (!adapter.isEnabled()) { status("Turn on Bluetooth, then tap Scan & Connect."); return; }
        if (scanner == null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { status("Bluetooth LE scanner is unavailable."); return; }
        if (scanning) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        }
        left = null;
        right = null;
        connectedCallbackSent = false;
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                if (result != null && result.getDevice() != null) handleDevice(result.getDevice(), result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName());
            }
            @Override public void onBatchScanResults(java.util.List<ScanResult> results) {
                if (results == null) return;
                for (ScanResult result : results) {
                    if (result != null && result.getDevice() != null) handleDevice(result.getDevice(), result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName());
                }
            }
            @Override public void onScanFailed(int errorCode) { status("BLE scan failed (" + errorCode + ")."); }
        };
        try {
            scanner.startScan(scanCallback);
            scanning = true;
            status("Scanning for G1 left and right lenses…");
            handler.postDelayed(() -> {
                synchronized (G1BluetoothBridge.this) {
                    if (scanning) {
                        try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
                        scanning = false;
                        if (!(isReady(left) && isReady(right))) status("Scan ended. Make sure the glasses are awake and already paired in Even G1, then retry.");
                    }
                }
            }, 25000);
        } catch (SecurityException e) {
            status("Bluetooth permission denied. Allow Nearby devices and retry.");
        } catch (Exception e) {
            status("Could not start scan: " + e.getMessage());
        }
    }

    private String classify(String name) {
        if (name == null) return null;
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.contains("_L_") || upper.endsWith("_L") || upper.contains("LEFT")) return "left";
        if (upper.contains("_R_") || upper.endsWith("_R") || upper.contains("RIGHT")) return "right";
        return null;
    }

    private synchronized void handleDevice(BluetoothDevice device, String advertisedName) {
        try {
            String name = device.getName();
            if (name == null || name.isEmpty()) name = advertisedName;
            String side = classify(name);
            if (side == null) return;
            Peer existing = side.equals("left") ? left : right;
            if (existing != null) return;
            listener.onDeviceFound(side, name, device.getAddress());
            status("Found " + side + " lens: " + name + ". Connecting…");
            Peer peer = new Peer(side, device, name);
            if (side.equals("left")) left = peer; else right = peer;
            peer.connect();
            if (left != null && right != null && scanning) {
                try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
                scanning = false;
            }
        } catch (SecurityException e) {
            status("Bluetooth permission denied while scanning.");
        } catch (Exception e) {
            status("Could not connect to a lens: " + e.getMessage());
        }
    }

    private boolean isReady(Peer p) { return p != null && p.ready; }

    private synchronized void checkBothReady() {
        if (isReady(left) && isReady(right)) {
            if (!connectedCallbackSent) {
                connectedCallbackSent = true;
                listener.onConnected();
                status("Both lenses are connected and ready.");
                if (!heartbeatStarted) {
                    heartbeatStarted = true;
                    handler.postDelayed(heartbeatTask, 5000);
                }
            }
        } else if (left != null && right != null) {
            status("Connecting to both lenses…");
        }
    }

    /** Accepts a hexadecimal packet generated by Python and queues it to both lenses. */
    public synchronized void sendPacket(String hex) {
        if (!isReady(left) || !isReady(right)) {
            status("Both lenses must be connected before sending text.");
            return;
        }
        try {
            byte[] data = hexToBytes(hex);
            left.enqueue(data);
            right.enqueue(data);
        } catch (Exception e) {
            status("Invalid packet: " + e.getMessage());
        }
    }

    private byte[] hexToBytes(String value) {
        String hex = value == null ? "" : value.replaceAll("\\s", "");
        if (hex.length() == 0 || (hex.length() & 1) != 0) throw new IllegalArgumentException("hex length must be even");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private final Runnable heartbeatTask = new Runnable() {
        @Override public void run() {
            byte[] heartbeat = new byte[] { 0x25, (byte) (heartbeatSequence++ & 0xff) };
            if (isReady(left)) left.enqueue(heartbeat);
            if (isReady(right)) right.enqueue(heartbeat);
            handler.postDelayed(this, 5000);
        }
    };

    public void close() {
        handler.removeCallbacks(heartbeatTask);
        synchronized (this) {
            if (scanner != null && scanning && scanCallback != null) {
                try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
            }
            scanning = false;
            if (left != null) left.close();
            if (right != null) right.close();
            left = null; right = null;
            heartbeatStarted = false;
        }
    }

    private final class Peer {
        final String side;
        final BluetoothDevice device;
        final String name;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic tx;
        BluetoothGattCharacteristic rx;
        volatile boolean ready = false;
        int mtu = 23;
        boolean notifySetupStarted = false;
        final Queue<byte[]> writes = new ArrayDeque<>();
        boolean writeInProgress = false;

        Peer(String side, BluetoothDevice device, String name) { this.side = side; this.device = device; this.name = name; }

        void connect() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    gatt = device.connectGatt(context, false, callback);
                }
            } catch (SecurityException e) { status("Permission denied connecting to " + side + " lens."); }
        }

        final BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    status(side + " lens GATT error " + statusCode + ". Retry scan if needed.");
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status("Connected to " + side + " lens. Discovering services…");
                    try { g.discoverServices(); } catch (SecurityException e) { status("Permission denied discovering services."); }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    ready = false;
                    status(side + " lens disconnected.");
                }
            }

            @Override public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) { status("Could not discover services on " + side + " lens."); return; }
                android.bluetooth.BluetoothGattService service = g.getService(UART_SERVICE);
                if (service == null) { status("G1 UART service not found on " + side + " lens."); return; }
                tx = service.getCharacteristic(UART_TX);
                rx = service.getCharacteristic(UART_RX);
                if (tx == null || rx == null) { status("G1 UART characteristics missing on " + side + " lens."); return; }
                try {
                    boolean requested = g.requestMtu(251);
                    if (!requested) setupNotifications();
                } catch (SecurityException e) { setupNotifications(); }
            }

            @Override public void onMtuChanged(BluetoothGatt g, int newMtu, int statusCode) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
                setupNotifications();
            }

            private void setupNotifications() {
                if (notifySetupStarted) return;
                notifySetupStarted = true;
                try {
                    gatt.setCharacteristicNotification(rx, true);
                    BluetoothGattDescriptor descriptor = rx.getDescriptor(CCCD);
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        boolean ok = gatt.writeDescriptor(descriptor);
                        if (!ok) markReady();
                    } else {
                        markReady();
                    }
                } catch (SecurityException e) { status("Notification permission denied for " + side + " lens."); }
            }

            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int statusCode) {
                if (CCCD.equals(descriptor.getUuid())) markReady();
            }

            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
                // RX is enabled for link health/debug replies; text sending doesn't require parsing them.
            }

            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
                // Android 13+ callback overload.
            }

            @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int statusCode) {
                synchronized (Peer.this) {
                    writeInProgress = false;
                    if (statusCode != BluetoothGatt.GATT_SUCCESS) status("BLE write error on " + side + " lens: " + statusCode);
                    pumpWrites();
                }
            }
        };

        private void markReady() {
            ready = true;
            status(side + " lens ready (MTU " + mtu + ").");
            synchronized (G1BluetoothBridge.this) { checkBothReady(); }
            synchronized (this) { pumpWrites(); }
        }

        synchronized void enqueue(byte[] data) {
            if (!ready || data == null || data.length == 0) return;
            if (data.length > mtu - 3) {
                status("Negotiated BLE MTU for " + side + " lens is too small (" + mtu + "). Reconnect and retry.");
                return;
            }
            writes.add(data.clone());
            pumpWrites();
        }

        synchronized void pumpWrites() {
            if (!ready || writeInProgress || writes.isEmpty() || gatt == null || tx == null) return;
            byte[] next = writes.poll();
            writeInProgress = true;
            try {
                tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                tx.setValue(next);
                boolean started = gatt.writeCharacteristic(tx);
                if (!started) {
                    writeInProgress = false;
                    status("Could not queue BLE write for " + side + " lens.");
                    pumpWrites();
                }
            } catch (SecurityException e) {
                writeInProgress = false;
                status("Bluetooth permission missing while writing to " + side + " lens.");
            }
        }

        void close() {
            try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Exception ignored) { }
            ready = false;
        }
    }
}
