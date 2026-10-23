package com.weartak.atak.weartak_companion.plugin;

import android.Manifest;
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
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BLE client for WearTAK watch GATT server.
 * Adds chunked writes so large JSON payloads can be reliably sent to the watch.
 */
public class WearTakBleClient {

    private static final String TAG = "WTK/BleClient";

    public static final UUID COMPANION_SERVICE_UUID =
            UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB");
    public static final UUID TX_TO_COMPANION_UUID =
            UUID.fromString("0000A11B-0000-1000-8000-00805F9B34FB"); // NOTIFY watch -> phone
    public static final UUID RX_FROM_COMPANION_UUID =
            UUID.fromString("0000A11C-0000-1000-8000-00805F9B34FB"); // WRITE phone -> watch
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String PREFS = "wtk_pairing";
    private static final String KEY_PAIRED_UID = "paired_uid";
    private static final String KEY_PAIRED_CALLSIGN = "paired_callsign";

    private final Context baseContext;
    private final String pairingPreferences;
    private final String retryPreferences;
    private final boolean gatewayOnly;
    private Context appContext;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;

    private volatile BluetoothGatt bluetoothGatt;
    private Runnable setupTimeout;
    // One in-place Service Changed refresh per GATT connection; onReady never restores the budget.
    private BluetoothGatt serviceRefreshGatt;
    private boolean refreshingServices;
    // Android resolves write callbacks by handle, without an operation token. Drain the single
    // outstanding request before rediscovery so its completion cannot answer a new request.
    private static final int OP_NONE = 0, OP_MTU = 1, OP_DISCOVERY = 2, OP_CCCD = 3, OP_TX = 4;
    private int gattOp = OP_NONE;
    private boolean refreshAfterOp;
    private volatile BluetoothGattCharacteristic txNotifyChar; // A11B
    private volatile BluetoothGattCharacteristic rxWriteChar;  // A11C
    private static final String KEY_PREFERRED_ADDRESS = "preferred_address"; // saved when connected
    private volatile DiscoveredDevice pendingBondDevice;
    private volatile String pendingBondRecoveryName;
    private volatile boolean bondReceiverRegistered = false;

    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean started = false;
    private volatile boolean scanning = false;
    private volatile boolean connected = false;
    private boolean servicesReady;
    private volatile long sessionGeneration;
    private boolean automaticReconnectAllowed;
    private final TraditionalBleReconnect reconnect = new TraditionalBleReconnect(
            new SystemBondedWatchConnection.Scheduler() {
                @Override public void execute(Runnable action) { runOnMain(action); }
                @Override public void later(Runnable action, long delayMs) {
                    main.postDelayed(action, delayMs);
                }
                @Override public void cancel(Runnable action) { main.removeCallbacks(action); }
            }, new TraditionalBleReconnect.Transport() {
                @Override public boolean available() {
                    return started && bluetoothUnavailableReason() == null;
                }
                @Override public boolean busy() {
                    return scanning || pendingBondDevice != null || bluetoothGatt != null;
                }
                @Override public boolean connect(String address) {
                    if (!available()) return false;
                    BluetoothDevice peer;
                    try {
                        peer = bluetoothAdapter.getRemoteDevice(address);
                    } catch (IllegalArgumentException e) {
                        handleTrustLoss(address, "invalid_retry_peer", "Invalid reconnect address");
                        return false;
                    } catch (SecurityException e) {
                        return false;
                    }
                    if (!isBonded(peer)) {
                        handleTrustLoss(address, "bond_lost_before_retry", "Reconnect requires existing bond");
                        return false;
                    }
                    connectToSelectedDeviceInternal(new DiscoveredDevice(null, address, 0), false);
                    return true;
                }
                @Override public boolean probe() {
                    if (getReadySession() < 0) return false;
                    if (!gatewayOnly)
                        return writeJsonLineToWatch("{\"msg_type\":\"request_watch_info\"}");
                    String session = gatewayHealthSession;
                    if (session == null) return false;
                    try {
                        String hello = new JSONObject().put("msg_type", "gateway_hello")
                                .put("version", 1).put("session", session).toString();
                        return writeJsonLineToWatch(hello);
                    } catch (org.json.JSONException e) {
                        error("Gateway health probe could not be built: " + e.getMessage());
                        return false;
                    }
                }
                @Override public void reset() {
                    resetSession("automatic reconnect timeout");
                }
            });

    private final LinkedHashMap<String, DiscoveredDevice> discovered = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> scanLastSeen = new LinkedHashMap<>();
    static final long SCAN_ENTRY_TTL_MS = 10_000;
    private static final long SCAN_EXPIRY_INTERVAL_MS = 1_000;
    // Scan responses are not present in every result; retain identity across scans/resets.
    private final LinkedHashMap<String, String> advertisedCallsigns = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> standaloneCallsigns = new LinkedHashMap<>();

    private ScanCallback activeScanCallback = null;
    private Runnable scanTimeoutRunnable = null;
    private Runnable scanExpiryRunnable;

    // RX framing: newline-delimited JSON
    private final Object rxLock = new Object();
    private final BleJsonFramer rxBuffer = new BleJsonFramer();

    private static final int DESIRED_MTU = 512;
    private volatile boolean mtuNegotiated = false;
    private volatile int negotiatedMtu = 23; // default ATT MTU if unknown

    private static final long RECONNECT_COOLDOWN_MS = 1000;
    private static final long SCAN_SETTLE_MS = 300;
    private static final long NEVER_MS = Long.MIN_VALUE / 4;
    private long lastGattCloseMs = NEVER_MS;
    private long lastScanStopMs = NEVER_MS;

    // Bounded retry of a user-initiated Traditional connect whose link never came up.
    private static final int MAX_CONNECT_RETRIES = 2;
    private static final long[] CONNECT_RETRY_BACKOFF_MS = {1000, 2000};
    private DiscoveredDevice connectRetryDevice;
    private int connectRetries;
    private boolean linkUp;

    // -------- TX queue (chunked writes) --------
    private final Object txLock = new Object();
    private final Deque<byte[]> txQueue = new ArrayDeque<>();
    private volatile boolean txInFlight = false;

    /**
     * [Inference] Conservative chunk size to avoid edge MTU/stack issues.
     * If MTU is negotiated, we compute (mtu - 3) and also clamp to 180.
     */
    private int computeMaxChunk() {
        int mtuPayload = Math.max(20, negotiatedMtu - 3);
        return Math.min(180, mtuPayload);
    }

    public interface JsonListener {
        void onReady();
        void onJson(String jsonLine);
        void onPaired(String deviceId, String callsign);
        void onError(String msg);
        void onTrustLost(String deviceAddress, String reason);
        default void onBluetoothStateChanged(boolean available) { }
        /** Non-error progress, e.g. an automatic retry of a connect whose link never came up. */
        default void onConnectionProgress(String message) { }
    }

    private final AtomicReference<JsonListener> jsonListener = new AtomicReference<>(null);
    public void setJsonListener(JsonListener l) { jsonListener.set(l); }

    public interface StatusListener {
        void onConnectionStatusChanged(boolean connected);

        /** {@code refreshingServices} is captured with the transition (same-connection Service Changed refresh). */
        default void onConnectionStatusChanged(boolean connected, boolean refreshingServices) {
            onConnectionStatusChanged(connected);
        }
    }

    public interface RefreshAwareStatusListener extends StatusListener {
        @Override void onConnectionStatusChanged(boolean connected, boolean refreshingServices);

        @Override default void onConnectionStatusChanged(boolean connected) {
            onConnectionStatusChanged(connected, false);
        }
    }

    private volatile StatusListener statusListener;
    private volatile String gatewayHealthSession;

    public void setStatusListener(StatusListener listener) { statusListener = listener; }

    void setGatewayHealthSession(String session) { gatewayHealthSession = session; }

    public WearTakBleClient(Context context, StatusListener statusListener) {
        this(context, statusListener, PREFS, AutoReconnectPreference.NAME, false);
    }

    WearTakBleClient(Context context, StatusListener statusListener, String peerAddress) {
        this(context, statusListener, GatewayPolicy.pairingPreferences(peerAddress),
                GatewayPolicy.retryPreferences(peerAddress), true);
    }

    private WearTakBleClient(Context context, StatusListener statusListener,
                             String pairingPreferences, String retryPreferences, boolean gatewayOnly) {
        this.baseContext = context;
        this.statusListener = statusListener;
        this.pairingPreferences = pairingPreferences;
        this.retryPreferences = retryPreferences;
        this.gatewayOnly = gatewayOnly;
    }

    private void savePreferredAddress(String addr) {
        SharedPreferences prefs = PluginPrefs.get(baseContext, pairingPreferences);
        prefs.edit().putString(KEY_PREFERRED_ADDRESS, addr).apply();
    }

    private void clearPreferredAddress() {
        SharedPreferences prefs = PluginPrefs.get(baseContext, pairingPreferences);
        prefs.edit().remove(KEY_PREFERRED_ADDRESS).apply();
    }

    public String getPreferredAddress() {
        SharedPreferences prefs = PluginPrefs.get(baseContext, pairingPreferences);
        return prefs.getString(KEY_PREFERRED_ADDRESS, null);
    }

    private void clearPreferredAddressIfMatches(String addr) {
        if (addr == null) return;
        String preferred = getPreferredAddress();
        if (preferred != null && preferred.equals(addr)) {
            clearPreferredAddress();
        }
    }

    private String currentGattAddress() {
        try {
            BluetoothGatt gatt = bluetoothGatt;
            if (gatt == null) return null;
            BluetoothDevice device = gatt.getDevice();
            return device != null ? device.getAddress() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isTrackedDeviceAddress(String address) {
        if (address == null) return false;

        DiscoveredDevice pending = pendingBondDevice;
        if (pending != null && address.equals(pending.address)) return true;

        String active = currentGattAddress();
        if (active != null && active.equals(address)) return true;

        String preferred = getPreferredAddress();
        return preferred != null && preferred.equals(address);
    }

    private void resetSessionAsync(String reason, long token) {
        runOnMain(() -> {
            if (token == sessionGeneration) resetSession(reason);
        });
    }

    private void failSecureSession(String msg, String reason) {
        final long token = sessionGeneration;
        runOnMain(() -> {
            if (token != sessionGeneration || !started) return;
            resetSession(reason);
            reconnect.onLoss();
            error(msg);
        });
    }

    private void handleTrustLoss(String address, String reason, String msg) {
        reconnect.disarm();
        reconnectPreference().disarm();
        final long token = sessionGeneration;
        pendingBondDevice = null;
        clearPreferredAddressIfMatches(address);

        JsonListener l = jsonListener.get();
        if (l != null) l.onTrustLost(address, reason);

        error(msg);
        resetSessionAsync(reason + (address == null ? "" : (" " + address)), token);
    }

    private int bondStateOf(BluetoothDevice device) {
        try {
            return device.getBondState();
        } catch (SecurityException ignored) {
            return BluetoothDevice.BOND_NONE;
        } catch (Throwable ignored) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    private boolean isBonded(BluetoothDevice device) {
        return device != null && bondStateOf(device) == BluetoothDevice.BOND_BONDED;
    }

    private void registerBondReceiver() {
        if (appContext == null || bondReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Bluetooth broadcasts originate from a privileged UID outside the app.
            appContext.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(bondStateReceiver, filter);
        }
        bondReceiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (appContext == null || !bondReceiverRegistered) return;
        try {
            appContext.unregisterReceiver(bondStateReceiver);
        } catch (Throwable ignored) {
        }
        bondReceiverRegistered = false;
    }

    public static class DiscoveredDevice {
        public final String name;
        public final String callsign;
        public final String advertisedCallsign;
        public final String standaloneCallsign;
        public final String advertisedName;
        public final String bondedName;
        public final String productName;
        public final boolean bonded;
        public final String address;
        public final int rssi;

        public DiscoveredDevice(String name, String address, int rssi) {
            this(name, null, null, address, rssi);
        }

        private DiscoveredDevice(String name, String advertisedName, String bondedName,
                                 String address, int rssi) {
            this(name, advertisedName, bondedName, address, rssi, null, null, name, false, null);
        }

        private DiscoveredDevice(String name, String advertisedName, String bondedName,
                                 String address, int rssi, String callsign, String advertisedCallsign,
                                 String productName, boolean bonded, String standaloneCallsign) {
            this.name = name;
            this.callsign = callsign;
            this.advertisedCallsign = advertisedCallsign;
            this.standaloneCallsign = standaloneCallsign;
            this.advertisedName = advertisedName;
            this.bondedName = bondedName;
            this.productName = productName;
            this.bonded = bonded;
            this.address = address;
            this.rssi = rssi;
        }

        static DiscoveredDevice fromScan(String advertisedName, String cachedName, String alias,
                                         boolean bonded, String address, int rssi,
                                         DiscoveredDevice previous) {
            String advertised = nonBlank(advertisedName);
            if (advertised == null && previous != null && address != null
                    && address.equals(previous.address)) {
                advertised = previous.advertisedName;
            }
            String bondedName = bonded ? nonBlank(alias) : null;
            if (bonded && bondedName == null) bondedName = nonBlank(cachedName);
            // A cached bonded name may already be the phone-assigned alias, not the original.
            String remoteName = nonBlank(cachedName);
            if (bonded && (nonBlank(alias) == null || sameName(remoteName, bondedName))) remoteName = null;
            String name = advertised != null ? advertised : remoteName;
            String product = nonBlank(cachedName);
            if (product == null && previous != null && address != null && address.equals(previous.address))
                product = previous.productName;
            return new DiscoveredDevice(name, advertised, bondedName, address, rssi,
                    null, null, product, bonded, null);
        }

        String distinctBondedName() {
            return bondedName != null && !sameName(bondedName, name)
                    && !sameName(bondedName, primaryName())
                    ? bondedName : null;
        }

        String primaryName() {
            if (callsign != null) return callsign;
            if (advertisedCallsign != null) return advertisedCallsign;
            if (nonBlank(name) != null) return name;
            return bondedName != null ? bondedName : address;
        }

        String secondaryName() {
            return name != null && !sameName(primaryName(), name) ? name : null;
        }

        private static boolean sameName(String a, String b) {
            return a != null && b != null && a.equalsIgnoreCase(b);
        }

        private static String nonBlank(String value) {
            return value == null || value.trim().isEmpty() ? null : value.trim();
        }

        @Override public String toString() {
            String n = primaryName() != null ? primaryName() : "Unknown";
            return n + " (" + address + ")";
        }
    }

    static String parseAdvertisedCallsign(byte[] serviceData) {
        if (serviceData == null || serviceData.length < 1 || serviceData.length > 13
                || serviceData[0] != 1) return null;
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(serviceData, 1, serviceData.length - 1)).toString();
            if (value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
                return null;
            return DiscoveredDevice.nonBlank(value);
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private String sessionWatchCallsign;
    private long callsignSession = -1;

    synchronized DiscoveredDevice withSessionIdentity(DiscoveredDevice device) {
        String callsign = null;
        boolean bonded = device.bonded;
        String product = device.productName;
        if (bluetoothAdapter != null && device.address != null && hasConnectPermission()) {
            try {
                BluetoothDevice peer = bluetoothAdapter.getRemoteDevice(device.address);
                if (peer != null) {
                    bonded = isBonded(peer);
                    String currentName = DiscoveredDevice.nonBlank(safeGetDeviceName(peer));
                    if (currentName != null) product = currentName;
                }
            } catch (IllegalArgumentException | SecurityException e) {
                bonded = false;
                logW("Unable to check display bond: " + e.getMessage());
            }
        }
        if (getReadySession() >= 0 && callsignSession == sessionGeneration
                && device.address != null
                && device.address.equals(bluetoothGatt.getDevice().getAddress())) {
            callsign = sessionWatchCallsign;
        }
        return new DiscoveredDevice(device.name, device.advertisedName, device.bondedName,
                device.address, device.rssi, callsign,
                advertisedCallsigns.containsKey(device.address)
                        ? advertisedCallsigns.get(device.address) : device.advertisedCallsign,
                product, bonded, standaloneCallsigns.containsKey(device.address)
                        ? standaloneCallsigns.get(device.address) : device.standaloneCallsign);
    }

    public interface ScanListener {
        void onDeviceFound(DiscoveredDevice device);
        default void onDevicesExpired(List<String> addresses) { }
        void onScanFinished(List<DiscoveredDevice> devices);
        void onScanError(String msg);
    }

    public interface BondedCompanionListener {
        void onFound(DiscoveredDevice device);
        void onUnavailable(String reason);
    }

    public int timeoutSeconds = 30;

    // -------------------- lifecycle --------------------

    public synchronized void start() {
        if (started) return;
        if (baseContext == null) return;

        appContext = baseContext.getApplicationContext();
        if (appContext == null) appContext = baseContext;

        BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return;

        bluetoothAdapter = bm.getAdapter();
        if (bluetoothAdapter == null) return;

        registerBondReceiver();
        started = true;
        AutoReconnectPreference savedReconnect = reconnectPreference();
        reconnect.start(automaticReconnectAllowed, savedReconnect.armedAddress());
        logI("start(): initialized (Traditional BLE automatic reconnect allowed="
                + automaticReconnectAllowed + ")");
    }

    public synchronized void stop() {
        cancelConnectRetry();
        reconnect.stop();
        started = false;
        stopScanInternal("stop()");
        closeGattInternal("stop()");
        unregisterBondReceiver();
        pendingBondDevice = null;
        pendingBondRecoveryName = null;
        started = false;
        bluetoothAdapter = null;
        scanner = null;
        setConnected(false);
        main.removeCallbacksAndMessages(null);
    }

    public synchronized void resetSession(String reason) {
        logI("resetSession(): " + reason);
        cancelConnectRetry();
        stopScanInternal("resetSession: " + reason);
        closeGattInternal("resetSession: " + reason);

        synchronized (rxLock) { rxBuffer.clear(); }
        discovered.clear();
        scanLastSeen.clear();
        pendingBondDevice = null;
        if (reason == null || !reason.startsWith("bond_recovery ")) {
            pendingBondRecoveryName = null;
        }
        setConnected(false);
    }

    // -------------------- scanning --------------------

    public synchronized void scanForDevices(final ScanListener listener) {
        if (!started) start();
        cancelConnectRetry();

        String unavailable = bluetoothUnavailableReason();
        if (unavailable != null) {
            failScan(listener, unavailable);
            return;
        }
        stopScanInternal("scanForDevices(): pre-stop");

        if (!hasScanPermission()) {
            failScan(listener, "Missing Bluetooth scan or location permission");
            return;
        }
        try {
            scanner = bluetoothAdapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            failScan(listener, "Missing Bluetooth permission");
            return;
        }
        if (scanner == null) {
            failScan(listener, "BLE scanner unavailable");
            return;
        }

        discovered.clear();
        scanLastSeen.clear();
        scanning = true;

        ScanSettings.Builder settings = new ScanSettings.Builder();
        settings.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);

        activeScanCallback = createScanCallback(listener);

        try {
            scanner.startScan(null, settings.build(), activeScanCallback);
            logI("SCAN: started");
        } catch (SecurityException e) {
            scanning = false;
            activeScanCallback = null;
            clearScanTimeout();
            failScan(listener, "Bluetooth scan permission denied: " + e.getMessage());
            return;
        } catch (Throwable t) {
            scanning = false;
            activeScanCallback = null;
            clearScanTimeout();
            failScan(listener, "startScan threw: " + t.getMessage());
            return;
        }

        clearScanTimeout();
        scanTimeoutRunnable = () -> {
            List<DiscoveredDevice> out = stopScanInternal("scan timeout");
            if (listener != null) listener.onScanFinished(out);
        };
        main.postDelayed(scanTimeoutRunnable, Math.max(1, timeoutSeconds) * 1000L);
        scheduleScanExpiry(listener);
    }

    private void scheduleScanExpiry(ScanListener listener) {
        final ScanCallback owner = activeScanCallback;
        scanExpiryRunnable = new Runnable() {
            @Override public void run() {
                if (!scanning || owner != activeScanCallback || scanExpiryRunnable != this) return;
                List<String> expired = expireScanDevices(android.os.SystemClock.elapsedRealtime());
                if (!expired.isEmpty() && listener != null) listener.onDevicesExpired(expired);
                if (scanning && owner == activeScanCallback && scanExpiryRunnable == this)
                    main.postDelayed(this, SCAN_EXPIRY_INTERVAL_MS);
            }
        };
        main.postDelayed(scanExpiryRunnable, SCAN_EXPIRY_INTERVAL_MS);
    }

    synchronized List<String> expireScanDevices(long nowMs) {
        List<String> expired = new ArrayList<>();
        Iterator<Map.Entry<String, Long>> entries = scanLastSeen.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Long> entry = entries.next();
            if (nowMs - entry.getValue() < SCAN_ENTRY_TTL_MS) continue;
            discovered.remove(entry.getKey());
            expired.add(entry.getKey());
            entries.remove();
        }
        return expired;
    }

    ScanCallback createScanCallback(ScanListener listener) {
        return new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                main.post(() -> handleResult(result));
            }

            private void handleResult(ScanResult result) {
                if (activeScanCallback != this || !scanning || result == null
                        || result.getDevice() == null) return;

                BluetoothDevice d = result.getDevice();
                String addr = d.getAddress();
                if (addr == null) return;

                int rssi = result.getRssi();

                String name = safeGetDeviceName(d);
                String advName = safeGetAdvName(result);

                boolean hasService = safeHasServiceUuid(result, COMPANION_SERVICE_UUID);
                byte[] serviceData = result.getScanRecord() == null ? null
                        : result.getScanRecord().getServiceData(new ParcelUuid(COMPANION_SERVICE_UUID));

                if (!hasService && serviceData == null) return;

                if (serviceData != null) {
                    String callsign = parseAdvertisedCallsign(serviceData);
                    advertisedCallsigns.put(addr, callsign);
                    if (callsign == null)
                        LogX.d(TAG, "SCAN: A11A callsign unavailable or invalid for " + addr);
                }

                boolean bonded = isBonded(d);
                DiscoveredDevice dd = withSessionIdentity(DiscoveredDevice.fromScan(advName, name,
                        bonded ? safeGetDeviceAlias(d) : null, bonded, addr, rssi,
                        discovered.get(addr)));

                discovered.put(addr, dd);
                scanLastSeen.put(addr, android.os.SystemClock.elapsedRealtime());

                if (listener != null) listener.onDeviceFound(dd);
                LogX.d(TAG, "SCAN: addr=" + addr + " name=" + name + " advName=" + advName
                        + " displayName=" + dd.primaryName() + " hasService=" + hasService + " rssi=" + rssi);
            }

            @Override
            public void onScanFailed(int errorCode) {
                main.post(() -> {
                    if (activeScanCallback != this) return;
                    stopScanInternal("scan failed");
                    if (listener != null) listener.onScanError("Scan failed: " + errorCode);
                });
            }
        };
    }

    private synchronized void clearScanTimeout() {
        if (scanTimeoutRunnable != null) {
            main.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
    }

    private synchronized List<DiscoveredDevice> stopScanInternal(String reason) {
        if (scanning) lastScanStopMs = android.os.SystemClock.uptimeMillis();
        expireScanDevices(android.os.SystemClock.elapsedRealtime());
        scanning = false;

        clearScanTimeout();
        if (scanExpiryRunnable != null) main.removeCallbacks(scanExpiryRunnable);
        scanExpiryRunnable = null;

        try {
            if (scanner != null && activeScanCallback != null) {
                if (hasScanPermission()) {
                    scanner.stopScan(activeScanCallback);
                }
            }
        } catch (SecurityException e) {
            logW("stopScanInternal(): Bluetooth scan permission denied: " + e.getMessage());
        } catch (Throwable t) {
            logW("stopScanInternal(): stopScan threw: " + t.getMessage());
        } finally {
            activeScanCallback = null;
        }

        logI("stopScanInternal(): " + reason + " discoveredCount=" + discovered.size());
        return new ArrayList<>(discovered.values());
    }

    private void failScan(ScanListener listener, String msg) {
        logW("scanForDevices(): " + msg);
        if (listener != null) listener.onScanError(msg);
    }

    public synchronized void findBondedCompanion(BondedCompanionListener listener) {
        if (listener == null) return;
        if (!started) start();
        String unavailable = bluetoothUnavailableReason();
        if (unavailable != null) {
            listener.onUnavailable(unavailable);
            return;
        }

        String preferredAddress = getPreferredAddress();
        if (preferredAddress != null) {
            try {
                BluetoothDevice preferred = bluetoothAdapter.getRemoteDevice(preferredAddress);
                if (isBonded(preferred)) {
                    listener.onFound(withSessionIdentity(DiscoveredDevice.fromScan(null, safeGetDeviceName(preferred),
                            safeGetDeviceAlias(preferred), true, preferredAddress, 0,
                            discovered.get(preferredAddress))));
                    return;
                }
            } catch (IllegalArgumentException e) {
                logW("Saved companion address is invalid");
            }
        }

        AtomicBoolean completed = new AtomicBoolean(false);
        scanForDevices(new ScanListener() {
            @Override
            public void onDeviceFound(DiscoveredDevice device) {
                if (device == null || device.address == null || !isBondedAddress(device.address)
                        || !completed.compareAndSet(false, true)) {
                    return;
                }
                stopScanInternal("bonded companion found");
                savePreferredAddress(device.address);
                listener.onFound(device);
            }

            @Override
            public void onScanFinished(List<DiscoveredDevice> devices) {
                if (!completed.compareAndSet(false, true)) return;
                for (DiscoveredDevice device : devices) {
                    if (device != null && device.address != null && isBondedAddress(device.address)) {
                        savePreferredAddress(device.address);
                        listener.onFound(device);
                        return;
                    }
                }
                listener.onUnavailable("No paired WearTAK watch is reachable");
            }

            @Override
            public void onScanError(String msg) {
                if (completed.compareAndSet(false, true)) listener.onUnavailable(msg);
            }
        });
    }

    /**
     * Previously A11A-identified bond, independent of reachability. Android may report
     * BOND_NONE with its Bluetooth service powered off; retain the last confirmed bond
     * until the radio can verify it again (or an explicit bond-loss broadcast arrives).
     */
    public synchronized DiscoveredDevice getKnownBondedCompanion() {
        if (!started) start();
        if (bluetoothAdapter == null || !hasConnectPermission()) return null;
        String address = getPreferredAddress();
        if (address == null) return null;
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            if (!bluetoothAdapter.isEnabled() || device.getBondState() == BluetoothDevice.BOND_BONDED) {
                return withSessionIdentity(DiscoveredDevice.fromScan(null, safeGetDeviceName(device),
                        safeGetDeviceAlias(device), true, address, 0, discovered.get(address)));
            }
            clearPreferredAddressIfMatches(address);
        } catch (IllegalArgumentException | SecurityException e) {
            logW("Unable to check known bonded WearTAK candidate");
        }
        return null;
    }

    private boolean isBondedAddress(String address) {
        try {
            return bluetoothAdapter != null && isBonded(bluetoothAdapter.getRemoteDevice(address));
        } catch (IllegalArgumentException e) {
            logW("Ignoring invalid Bluetooth address during companion discovery");
            return false;
        }
    }

    // -------------------- selection / connect --------------------

    public synchronized void connectToSelectedDevice(DiscoveredDevice device) {
        connectToSelectedDevice(device, true);
    }

    public synchronized void connectToSelectedDevice(DiscoveredDevice device, boolean allowBondCreation) {
        reconnect.disarm();
        if (automaticReconnectAllowed) reconnectPreference().disarm();
        cancelConnectRetry();
        // Samsung Wearable (allowBondCreation=false) keeps its own owner-driven recovery.
        if (allowBondCreation && device != null && device.address != null) connectRetryDevice = device;
        connectToSelectedDeviceInternal(device, allowBondCreation);
    }

    private synchronized void cancelConnectRetry() {
        connectRetryDevice = null;
        connectRetries = 0;
    }

    /**
     * Retries a user-initiated connect whose link never came up (e.g. HCI 0x3E / GATT 133) on the
     * same still-bonded address. Any reset, Disconnect, scan, new connect, stop, Bluetooth off or
     * trust loss clears the retry state and bumps the session generation, fencing the backoff.
     */
    private boolean retryUnestablishedConnect(BluetoothGatt gatt, int status) {
        DiscoveredDevice target = connectRetryDevice;
        if (target == null || linkUp || connected || connectRetries >= MAX_CONNECT_RETRIES
                || !isTransientConnectStatus(status)) return false;
        BluetoothDevice peer = gatt.getDevice();
        if (peer == null || !target.address.equals(peer.getAddress()) || !isBonded(peer)
                || !hasConnectPermission()) return false;

        int attempt = connectRetries + 1;
        resetSession("gatt_status_" + status + " retry " + attempt + "/" + MAX_CONNECT_RETRIES);
        connectRetryDevice = target;
        connectRetries = attempt;
        final long token = sessionGeneration;
        main.postDelayed(() -> {
            synchronized (WearTakBleClient.this) {
                if (!started || token != sessionGeneration || connectRetryDevice != target) return;
                if (!isBondedAddress(target.address)) {
                    cancelConnectRetry();
                    handleTrustLoss(target.address, "bond_lost_before_retry", "Reconnect requires existing bond");
                    return;
                }
                connectToSelectedDeviceInternal(target, false);
            }
        }, CONNECT_RETRY_BACKOFF_MS[attempt - 1]);
        String message = "Retrying connection (" + attempt + "/" + MAX_CONNECT_RETRIES + ")\u2026";
        logW(message + " after GATT status " + status);
        JsonListener l = jsonListener.get();
        if (l != null) l.onConnectionProgress(message);
        return true;
    }

    private static boolean isTransientConnectStatus(int status) {
        return status == 133 || status == 8 || status == 62 || status == 147;
    }

    private synchronized void connectToSelectedDeviceInternal(DiscoveredDevice device, boolean allowBondCreation) {
        if (device == null || device.address == null) return;
        logI("CONNECT: requested address=" + device.address);
        if (!started) start();

        closeGattInternal("new connection attempt");
        pendingBondDevice = null;
        setConnected(false);
        final long token = sessionGeneration;
        String unavailable = bluetoothUnavailableReason();
        if (unavailable != null) {
            error(unavailable);
            return;
        }

        stopScanInternal("connectToSelectedDevice()");

        BluetoothDevice remote;
        try {
            remote = bluetoothAdapter.getRemoteDevice(device.address);
        } catch (Throwable t) {
            error("Remote device lookup failed for address=" + device.address);
            return;
        }

        if (remote == null) {
            error("Remote device null for address=" + device.address);
            return;
        }

        if (!hasConnectPermission()) {
            error("Missing Bluetooth connect permission");
            return;
        }

        if (!isBonded(remote)) {
            if (!allowBondCreation) {
                error("Samsung Wearable Bond requires an existing Android Bluetooth bond established via Galaxy Wearable.");
                return;
            }
            pendingBondDevice = device;
            boolean bondStarted;
            try {
                bondStarted = remote.createBond();
            } catch (SecurityException e) {
                pendingBondDevice = null;
                error("Unable to start BLE pairing: " + e.getMessage());
                return;
            }
            if (!bondStarted && !isBonded(remote)) {
                pendingBondDevice = null;
                error("Unable to start BLE pairing for " + device.address);
                return;
            }
            if (bondStarted) {
                logI("Bonding started for " + device.address + "; waiting for BOND_BONDED");
                return;
            }
        } else {
            logI("Reusing existing system bond for " + device.address);
        }

        pendingBondDevice = null;
        long now = android.os.SystemClock.uptimeMillis();
        long waitMs = Math.max(0, Math.max(
                RECONNECT_COOLDOWN_MS - (now - lastGattCloseMs),
                SCAN_SETTLE_MS - (now - lastScanStopMs)));

        if (waitMs > 0) {
            final DiscoveredDevice copy = device;
            main.postDelayed(() -> {
                if (started && token == sessionGeneration) connectGattNow(copy);
            }, waitMs);
        } else {
            connectGattNow(device);
        }
    }

    private synchronized void connectGattNow(DiscoveredDevice device) {
        if (device == null || device.address == null) return;

        String unavailable = bluetoothUnavailableReason();
        if (!started || unavailable != null) {
            error(unavailable != null ? unavailable : "BLE client stopped");
            return;
        }

        if (bluetoothGatt != null) {
            closeGattInternal("connectGattNow(): closing existing gatt");
            setConnected(false);
        }

        BluetoothDevice d = bluetoothAdapter.getRemoteDevice(device.address);
        if (d == null) {
            error("Remote device null for address=" + device.address);
            return;
        }
        if (!isBonded(d)) {
            handleTrustLoss(
                    device.address,
                    "bond_lost_before_connect",
                    "Device must be bonded before BLE connect");
            return;
        }

        if (!hasConnectPermission()) {
            error("Missing Bluetooth connect permission");
            return;
        }

        mtuNegotiated = false;
        negotiatedMtu = 23;
        armSetupTimeout();
        try {
            bluetoothGatt = d.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (bluetoothGatt == null) failSecureSession("connectGatt returned null", "connect_failed");
        } catch (SecurityException | IllegalArgumentException e) {
            failSecureSession("BLE connection failed: " + e.getMessage(), "connect_failed");
        }
    }

    private synchronized void armSetupTimeout() {
        if (setupTimeout != null) main.removeCallbacks(setupTimeout);
        final long token = sessionGeneration;
        setupTimeout = () -> {
            if (started && token == sessionGeneration && !connected) {
                failSecureSession("BLE setup timed out before transport ready", "setup_timeout");
            }
        };
        main.postDelayed(setupTimeout, 25000);
    }

    /** Re-subscribes on the same bonded GATT; the new generation fences every old-database callback. */
    private synchronized void refreshServicesInPlace(BluetoothGatt gatt) {
        serviceRefreshGatt = gatt;
        refreshingServices = true;
        reconnect.pause();
        ++sessionGeneration;
        servicesReady = false;
        txNotifyChar = null;
        rxWriteChar = null;
        synchronized (rxLock) { rxBuffer.clear(); }
        synchronized (txLock) { txQueue.clear(); txInFlight = false; }
        armSetupTimeout();
        setConnected(false);
        if (gattOp != OP_NONE) {
            refreshAfterOp = true;
            logW("Watch services changed; rediscovering after the outstanding GATT request");
            return;
        }
        logW("Watch services changed; rediscovering on the same bonded connection");
        rediscover(gatt);
    }

    private void rediscover(BluetoothGatt gatt) {
        try {
            if (!startServiceDiscovery(gatt)) {
                failSecureSession("Service discovery could not start", "discovery_start_failed");
            }
        } catch (SecurityException e) {
            failSecureSession("Bluetooth permission denied: " + e.getMessage(), "discovery_permission_lost");
        }
    }

    private synchronized boolean startServiceDiscovery(BluetoothGatt gatt) {
        if (!gatt.discoverServices()) return false;
        gattOp = OP_DISCOVERY;
        return true;
    }

    /** A pre-change completion starts fresh discovery instead of advancing setup/TX. */
    private synchronized boolean completeGattOp(BluetoothGatt gatt, int kind) {
        if (gattOp != kind) return false;
        gattOp = OP_NONE;
        if (!refreshAfterOp) return true;
        refreshAfterOp = false;
        rediscover(gatt);
        return false;
    }

    private boolean matchesNormalizedName(String expectedName, DiscoveredDevice device) {
        if (expectedName == null || device == null) return false;
        String candidate = normalizeWearTakName(device.name);
        return candidate != null && expectedName.equalsIgnoreCase(candidate);
    }

    private DiscoveredDevice findBestRecoveryTarget(List<DiscoveredDevice> devices, String expectedName) {
        if (devices == null || expectedName == null) return null;
        DiscoveredDevice best = null;
        for (DiscoveredDevice device : devices) {
            if (!matchesNormalizedName(expectedName, device)) continue;
            if (best == null || device.rssi > best.rssi) {
                best = device;
            }
        }
        return best;
    }

    private synchronized boolean scheduleBondRecoveryScan(DiscoveredDevice pending) {
        if (gatewayOnly) return false;
        if (pending == null || pending.address == null) return false;

        String recoveryName = normalizeWearTakName(pending.name);
        if (recoveryName == null) return false;
        if (recoveryName.equalsIgnoreCase(pendingBondRecoveryName)) return false;

        pendingBondRecoveryName = recoveryName;
        resetSession("bond_recovery " + pending.address);
        final long token = sessionGeneration;
        logI("Rescanning once for " + recoveryName + " after bond failure");

        AtomicBoolean reconnectIssued = new AtomicBoolean(false);
        ScanListener recoveryListener = new ScanListener() {
            @Override
            public void onDeviceFound(DiscoveredDevice device) {
                if (!matchesNormalizedName(recoveryName, device)) return;
                if (!reconnectIssued.compareAndSet(false, true)) return;

                List<DiscoveredDevice> snapshot = stopScanInternal("bond recovery match found");
                DiscoveredDevice recovered = findBestRecoveryTarget(snapshot, recoveryName);
                if (recovered == null) recovered = device;
                final DiscoveredDevice recoveredDevice = recovered;
                logI("Recovered fresh advertising address " + recoveredDevice.address + " for " + recoveryName);
                main.post(() -> {
                    if (started && token == sessionGeneration) connectToSelectedDevice(recoveredDevice);
                });
            }

            @Override
            public void onScanFinished(List<DiscoveredDevice> devices) {
                if (!reconnectIssued.compareAndSet(false, true)) return;

                DiscoveredDevice recovered = findBestRecoveryTarget(devices, recoveryName);
                if (recovered != null) {
                    final DiscoveredDevice recoveredDevice = recovered;
                    logI("Bond recovery scan found fresh address " + recoveredDevice.address + " for " + recoveryName);
                    main.post(() -> {
                        if (started && token == sessionGeneration) connectToSelectedDevice(recoveredDevice);
                    });
                    return;
                }

                pendingBondRecoveryName = null;
                handleTrustLoss(
                        pending.address,
                        "bond_recovery_not_found",
                        "Unable to rediscover watch after bond reset");
            }

            @Override
            public void onScanError(String msg) {
                if (!reconnectIssued.compareAndSet(false, true)) return;

                pendingBondRecoveryName = null;
                handleTrustLoss(
                        pending.address,
                        "bond_recovery_scan_error",
                        "Bond recovery scan failed: " + msg);
            }
        };

        scanForDevices(recoveryListener);
        return true;
    }

    public synchronized void disconnect() {
        reconnect.disarm();
        if (automaticReconnectAllowed) reconnectPreference().disarm();
        resetSession("disconnect()");
    }

    synchronized boolean disconnectReadySession(long expectedSession) {
        if (expectedSession < 0 || expectedSession != getReadySession()) return false;
        disconnect();
        return true;
    }

    private AutoReconnectPreference reconnectPreference() {
        return new AutoReconnectPreference(PluginPrefs.get(baseContext, retryPreferences));
    }

    public synchronized void setAutomaticReconnectAllowed(boolean allowed) {
        automaticReconnectAllowed = allowed;
        reconnect.setAllowed(allowed);
    }

    public boolean isAutoReconnecting() { return reconnect.isRetrying(); }

    public synchronized void resumeAutoReconnect() { reconnect.resume(); }

    public synchronized void pauseAutoReconnect() { reconnect.pause(); }

    // -------------------- NEW: outbound JSON write (chunked) --------------------

    public synchronized boolean writeJsonLineToWatch(String jsonLine) {
        if (jsonLine == null) return false;
        if (gatewayOnly && !GatewayPolicy.allowedOutput(jsonLine)) {
            error("Gateway rejected phone-to-watch content/settings output");
            return false;
        }
        if (gatewayOnly) {
            try {
                JSONObject output = new JSONObject(jsonLine);
                if ("gateway_cot".equals(output.optString("msg_type")))
                    GatewayCot.validate(output.getString("cot"));
            } catch (org.json.JSONException | IllegalArgumentException e) {
                error("Gateway map output rejected: " + e.getMessage());
                return false;
            }
        }
        if (!started) start();

        if (bluetoothGatt == null || !connected) {
            error("writeJsonLineToWatch: not connected");
            return false;
        }
        if (rxWriteChar == null) {
            error("writeJsonLineToWatch: RX write characteristic (A11C) missing");
            return false;
        }

        if (!hasConnectPermission()) {
            error("Missing Bluetooth connect permission");
            return false;
        }
        BluetoothDevice device = bluetoothGatt.getDevice();
        if (!isBonded(device)) {
            handleTrustLoss(
                    device != null ? device.getAddress() : null,
                    "bond_lost_before_write",
                    "writeJsonLineToWatch: bonded device required");
            return false;
        }

        String payload = jsonLine.endsWith("\n") ? jsonLine : (jsonLine + "\n");
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        int maxChunk = computeMaxChunk();
        synchronized (txLock) {
            if (gatewayOnly && txQueue.size() + ((bytes.length + maxChunk - 1) / maxChunk) > 4096) {
                error("Gateway BLE output queue full; retrying bounded handoff");
                return false;
            }
            // split into chunks
            int off = 0;
            while (off < bytes.length) {
                int n = Math.min(maxChunk, bytes.length - off);
                byte[] chunk = new byte[n];
                System.arraycopy(bytes, off, chunk, 0, n);
                txQueue.addLast(chunk);
                off += n;
            }

            // kick the queue if idle
            if (!txInFlight) {
                txInFlight = true;
                long session = sessionGeneration;
                main.post(() -> txSendNextLocked(session));
            }
        }

        logI("TX->WATCH queued bytes=" + bytes.length + " chunks=" + ((bytes.length + maxChunk - 1) / maxChunk));
        return true;
    }

    synchronized boolean writeDiagnosticJsonLineToWatch(String jsonLine, long expectedSession) {
        if (gatewayOnly || jsonLine == null || expectedSession < 0 ||
                expectedSession != getReadySession()) return false;
        try {
            JSONObject envelope = new JSONObject(jsonLine);
            String type = envelope.optString("msg_type", "");
            if (!"location_diagnostics_capability".equals(type) &&
                    !"location_diagnostics_snapshot".equals(type)) return false;
            if (!LocationDiagnosticsProtocol.isValidSession(envelope.optString("session", null)) ||
                    envelope.getInt("version") != LocationDiagnosticsProtocol.VERSION ||
                    jsonLine.getBytes(StandardCharsets.UTF_8).length >
                            LocationDiagnosticsProtocol.MAX_MESSAGE_BYTES) return false;
        } catch (org.json.JSONException | IllegalArgumentException error) {
            error("Invalid bounded location diagnostics message");
            return false;
        }
        if (bluetoothGatt == null || !connected || rxWriteChar == null || !hasConnectPermission()) return false;
        BluetoothDevice device = bluetoothGatt.getDevice();
        if (device == null || !isBonded(device)) return false;
        byte[] bytes = (jsonLine.endsWith("\n") ? jsonLine : jsonLine + "\n")
                .getBytes(StandardCharsets.UTF_8);
        int maxChunk = computeMaxChunk();
        int chunkCount = (bytes.length + maxChunk - 1) / maxChunk;
        if (chunkCount > 32) return false;
        synchronized (txLock) {
            if (txInFlight || !txQueue.isEmpty()) return false;
            for (int offset = 0; offset < bytes.length; offset += maxChunk) {
                int size = Math.min(maxChunk, bytes.length - offset);
                byte[] chunk = new byte[size];
                System.arraycopy(bytes, offset, chunk, 0, size);
                txQueue.addLast(chunk);
            }
            txInFlight = true;
            long session = sessionGeneration;
            main.post(() -> txSendNextLocked(session));
        }
        return true;
    }

    synchronized long getReadySession() {
        return connected && servicesReady && bluetoothGatt != null ? sessionGeneration : -1;
    }

    public synchronized boolean isRefreshingServices() { return refreshingServices; }

    synchronized boolean gatewayTxIdle() {
        synchronized (txLock) { return !txInFlight && txQueue.isEmpty(); }
    }

    synchronized boolean writeJsonLineToWatch(String jsonLine, long expectedSession) {
        if (expectedSession < 0 || expectedSession != getReadySession()) return false;
        return writeJsonLineToWatch(jsonLine);
    }

    private synchronized void txSendNextLocked(long expectedSession) {
        if (expectedSession != sessionGeneration) return;
        byte[] next;
        synchronized (txLock) {
            next = txQueue.pollFirst();
            if (next == null) {
                txInFlight = false;
                return;
            }
        }

        if (bluetoothGatt == null || rxWriteChar == null) {
            synchronized (txLock) {
                txQueue.clear();
                txInFlight = false;
            }
            error("TX aborted: gatt/char null");
            return;
        }

        if (!hasConnectPermission()) {
            synchronized (txLock) {
                txQueue.clear();
                txInFlight = false;
            }
            error("TX aborted: missing Bluetooth connect permission");
            return;
        }

        BluetoothDevice device = bluetoothGatt.getDevice();
        if (!isBonded(device)) {
            synchronized (txLock) {
                txQueue.clear();
                txInFlight = false;
            }
            handleTrustLoss(
                    device != null ? device.getAddress() : null,
                    "bond_lost_during_tx",
                    "TX aborted: bonded device required");
            return;
        }

        rxWriteChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        rxWriteChar.setValue(next);

        boolean ok;
        try {
            ok = bluetoothGatt.writeCharacteristic(rxWriteChar);
        } catch (SecurityException e) {
            failSecureSession("TX Bluetooth permission denied: " + e.getMessage(), "tx_permission_lost");
            return;
        }
        if (!ok) {
            synchronized (txLock) {
                txQueue.clear();
                txInFlight = false;
            }
            failSecureSession("TX writeCharacteristic returned false", "tx_write_start_failed");
        } else {
            gattOp = OP_TX;
        }
    }

    public synchronized boolean isConnected() { return connected; }

    private void postTxNext() {
        final long token = sessionGeneration;
        main.post(() -> txSendNextLocked(token));
    }

    public synchronized boolean isBondedConnected() {
        return connected && bluetoothGatt != null && rxWriteChar != null
                && isBonded(bluetoothGatt.getDevice());
    }

    private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!started || intent == null) return;
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    reconnect.pause();
                    resetSession("bluetooth_off");
                    error("Bluetooth disabled");
                    JsonListener listener = jsonListener.get();
                    if (listener != null) listener.onBluetoothStateChanged(false);
                } else if (state == BluetoothAdapter.STATE_ON) {
                    reconnect.resume();
                    JsonListener listener = jsonListener.get();
                    if (listener != null) listener.onBluetoothStateChanged(true);
                }
                return;
            }
            if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || device.getAddress() == null) return;

            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            int prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
            String address = device.getAddress();
            logI("Bond state changed for " + address + ": " + prevBondState + " -> " + bondState);

            DiscoveredDevice pending = pendingBondDevice;
            boolean pendingMatch = pending != null && pending.address != null
                    && pending.address.equals(address);
            if (bondState == BluetoothDevice.BOND_BONDED && pendingMatch) {
                pendingBondDevice = null;
                pendingBondRecoveryName = null;
                final long token = sessionGeneration;
                main.post(() -> {
                    if (started && token == sessionGeneration) connectToSelectedDevice(pending, true);
                });
                return;
            }

            if (bondState != BluetoothDevice.BOND_NONE || prevBondState == BluetoothDevice.BOND_NONE) {
                return;
            }

            if (pendingMatch && prevBondState == BluetoothDevice.BOND_BONDING) {
                if (scheduleBondRecoveryScan(pending)) {
                    return;
                }
                pendingBondRecoveryName = null;
                handleTrustLoss(address, "bond_cancelled", "BLE bond failed or was cancelled");
                return;
            }

            if (isTrackedDeviceAddress(address)) {
                handleTrustLoss(address, "bond_lost", "BLE bond lost for " + address);
            }
        }
    };

    // -------------------- GATT --------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override public void onServiceChanged(BluetoothGatt gatt) {
            dispatchGatt(gatt, () -> {
                BluetoothDevice peer = gatt.getDevice();
                if (!hasConnectPermission() || !isBonded(peer)) {
                    failSecureSession("Watch services changed without an existing bond", "service_changed");
                    return;
                }
                String address = peer.getAddress();
                if (serviceRefreshGatt != gatt) {
                    refreshServicesInPlace(gatt);
                    return;
                }
                resetSession("service_changed");
                if (reconnect.onServiceChanged(address)) {
                    error("Watch services changed; retrying the same bonded watch");
                } else {
                    reconnect.onLoss();
                    error("Watch services changed; connection setup stopped. Retry Connect.");
                }
            });
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            dispatchGatt(gatt, () -> handleConnectionStateChange(gatt, status, newState));
        }

        private void handleConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            logI("GATT connection state=" + newState + " status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (retryUnestablishedConnect(gatt, status)) return;
                failSecureSession("GATT connection failed: " + status, "gatt_status_" + status);
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                linkUp = true;
                if (!hasConnectPermission()) {
                    failSecureSession("Missing Bluetooth connect permission", "connect_permission_lost");
                    return;
                }

                try {
                    if (gatt.requestMtu(DESIRED_MTU)) {
                        gattOp = OP_MTU;
                    } else if (!startServiceDiscovery(gatt)) {
                        failSecureSession("Service discovery could not start", "discovery_start_failed");
                    }
                } catch (SecurityException e) {
                    failSecureSession("Bluetooth permission denied: " + e.getMessage(), "connect_permission_lost");
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!connected) {
                    failSecureSession("Bonded watch disconnected during BLE setup", "setup_disconnected");
                    return;
                }
                closeGattInternal("STATE_DISCONNECTED status=" + status);
                setConnected(false);
                reconnect.onLoss();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            dispatchGatt(gatt, () -> handleMtuChanged(gatt, mtu, status));
        }

        private void handleMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            mtuNegotiated = (status == BluetoothGatt.GATT_SUCCESS);
            if (mtuNegotiated) negotiatedMtu = mtu;
            if (!completeGattOp(gatt, OP_MTU)) return;

            if (!hasConnectPermission()) {
                failSecureSession("Missing Bluetooth connect permission", "mtu_permission_lost");
                return;
            }

            try {
                if (!startServiceDiscovery(gatt)) {
                    failSecureSession("Service discovery could not start", "discovery_start_failed");
                }
            } catch (SecurityException e) {
                failSecureSession("Bluetooth permission denied: " + e.getMessage(), "discovery_permission_lost");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            dispatchGatt(gatt, () -> handleServicesDiscovered(gatt, status));
        }

        private void handleServicesDiscovered(BluetoothGatt gatt, int status) {
            if (!completeGattOp(gatt, OP_DISCOVERY)) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failSecureSession("Service discovery failed: " + status, "service_discovery_failed_" + status);
                return;
            }
            BluetoothGattService service = gatt.getService(COMPANION_SERVICE_UUID);
            if (service == null) {
                failSecureSession("Companion service A11A missing", "service_missing");
                return;
            }
            txNotifyChar = service.getCharacteristic(TX_TO_COMPANION_UUID);
            rxWriteChar = service.getCharacteristic(RX_FROM_COMPANION_UUID);
            if (txNotifyChar == null || rxWriteChar == null) {
                failSecureSession("Companion characteristics missing", "characteristic_missing");
                return;
            }
            enableTxNotifications(gatt, txNotifyChar);
        }

        private void enableTxNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic tx) {
            if (!hasConnectPermission()) {
                failSecureSession("Missing Bluetooth connect permission", "notify_permission_lost");
                return;
            }

            boolean ok;
            try {
                ok = gatt.setCharacteristicNotification(tx, true);
            } catch (SecurityException e) {
                failSecureSession("Bluetooth permission denied: " + e.getMessage(), "notify_permission_lost");
                return;
            }
            if (!ok) {
                failSecureSession("setCharacteristicNotification failed", "set_notification_failed");
                return;
            }

            BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                failSecureSession("CCCD missing on A11B", "cccd_missing");
                return;
            }

            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean wrote;
            try {
                wrote = gatt.writeDescriptor(cccd);
            } catch (SecurityException e) {
                failSecureSession("Bluetooth permission denied: " + e.getMessage(), "cccd_permission_lost");
                return;
            }
            if (!wrote) failSecureSession("writeDescriptor(CCCD) failed", "cccd_write_start_failed");
            else gattOp = OP_CCCD;
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            dispatchGatt(gatt, () -> handleDescriptorWrite(gatt, descriptor, status));
        }

        private void handleDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor == null) return;
            if (!CCCD_UUID.equals(descriptor.getUuid())) return;
            if (!completeGattOp(gatt, OP_CCCD)) return;
            if (descriptor.getCharacteristic() != txNotifyChar || connected) return;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (!hasConnectPermission() || !isBonded(gatt.getDevice())) {
                    failSecureSession("Existing system bond required", "bond_lost_before_ready");
                    return;
                }
                if (setupTimeout != null) main.removeCallbacks(setupTimeout);
                setupTimeout = null;
                savePreferredAddress(gatt.getDevice().getAddress());
                servicesReady = true;
                refreshingServices = false;
                if (automaticReconnectAllowed) reconnectPreference().arm(gatt.getDevice().getAddress());
                reconnect.onReady(gatt.getDevice().getAddress());
                cancelConnectRetry();
                setConnected(true);
                JsonListener l = jsonListener.get();
                if (l != null) l.onReady();
            } else {
                failSecureSession("CCCD write failed: " + status, "cccd_write_failed_" + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic != null ? characteristic.getValue() : null;
            onCharacteristicChanged(gatt, characteristic, value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic, byte[] value) {
            final byte[] data = value != null ? value.clone() : null;
            dispatchGatt(gatt, () -> handleCharacteristicChanged(gatt, characteristic, data));
        }

        private void handleCharacteristicChanged(BluetoothGatt gatt,
                                                 BluetoothGattCharacteristic characteristic, byte[] data) {
            long receiveSession = getReadySession();
            if (receiveSession < 0) return;
            if (!isBonded(gatt.getDevice())) {
                handleTrustLoss(gatt.getDevice().getAddress(), "bond_lost_before_receive",
                        "RX rejected: bonded device required");
                return;
            }
            if (characteristic == null) return;
            if (txNotifyChar == null) return;
            if (!characteristic.getUuid().equals(txNotifyChar.getUuid())) return;
            if (data == null || data.length == 0) return;

            List<String> lines;
            synchronized (rxLock) {
                if (gatt != bluetoothGatt || receiveSession != sessionGeneration) return;
                try {
                    lines = rxBuffer.accept(data);
                } catch (IllegalArgumentException e) {
                    failSecureSession(e.getMessage(), "rx_frame_too_large");
                    return;
                }
            }
            for (String line : lines) {
                if (gatt != bluetoothGatt || receiveSession != getReadySession()) return;
                handleJsonLine(line);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            dispatchGatt(gatt, () -> handleCharacteristicWrite(gatt, characteristic, status));
        }

        private void handleCharacteristicWrite(BluetoothGatt gatt,
                                               BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic == null) return;
            if (!RX_FROM_COMPANION_UUID.equals(characteristic.getUuid())) return;
            if (!completeGattOp(gatt, OP_TX)) return;
            if (rxWriteChar == null) return;
            if (characteristic != rxWriteChar) return;

            if (status != BluetoothGatt.GATT_SUCCESS) {
                synchronized (txLock) {
                    txQueue.clear();
                    txInFlight = false;
                }
                failSecureSession("TX chunk write failed status=" + status, "tx_chunk_write_failed_" + status);
                return;
            }

            // send next chunk
            postTxNext();
        }
    };

    private void handleJsonLine(String line) {
        final long token = sessionGeneration;
        final JsonListener listener = jsonListener.get();
        // GATT callbacks hold the client monitor. Never invoke application code under it.
        if (!main.post(() -> {
            synchronized (WearTakBleClient.this) {
                if (!started || token != sessionGeneration || listener != jsonListener.get()) return;
            }
            deliverJsonLine(line, token);
        })) {
            logW("Could not schedule received watch message");
        }
    }

    private void deliverJsonLine(String line, long receiveSession) {
        try {
            JSONObject env = new JSONObject(line);
            String type = env.optString("msgType");
            JSONObject identity = env.optJSONObject("payload");
            if (identity != null && ("watch_info".equals(type)
                    || "settings_request".equals(type) || "settings_changed".equals(type))) {
                String key = "watch_info".equals(type) ? "cs" : "callsign";
                synchronized (this) {
                    if (receiveSession >= 0 && getReadySession() == receiveSession) {
                        if (!identity.isNull(key) && identity.has(key)) {
                            sessionWatchCallsign = DiscoveredDevice.nonBlank(identity.optString(key));
                            callsignSession = receiveSession;
                        }
                        if (identity.has("standaloneCallsign")) {
                            Object value = identity.opt("standaloneCallsign");
                            if (value instanceof String) {
                                String address = bluetoothGatt.getDevice().getAddress();
                                if (address != null) standaloneCallsigns.put(address,
                                        DiscoveredDevice.nonBlank((String) value));
                            } else {
                                logW("Ignoring non-string standaloneCallsign");
                            }
                        }
                    }
                }
            }
            if ("phone_management_ping".equals(env.optString("msgType"))) {
                if (gatewayOnly || getReadySession() != receiveSession) return;
                JSONObject response = PhoneManagementHeartbeat.response(env);
                if (!writeJsonLineToWatch(response.toString(), receiveSession))
                    logW("Could not enqueue phone management heartbeat");
                return;
            }
            if ("watch_info".equals(env.optString("msgType"))
                    && env.optJSONObject("payload") != null) reconnect.onHealthResponse();
            if (gatewayOnly && "gateway_ready".equals(env.optString("msgType"))
                    && gatewayHealthSession != null
                    && gatewayHealthSession.equals(env.optString("session")))
                reconnect.onHealthResponse();
        } catch (org.json.JSONException failure) {
            logW("Invalid watch control message: " + failure.getMessage());
        }
        JsonListener l = jsonListener.get();
        if (l != null) l.onJson(line);
        if (!started || receiveSession != sessionGeneration) return;

        // existing pairing support
        try {
            JSONObject env = new JSONObject(line);
            String msgType = env.optString("msgType", "");
            if (!"watch_info".equals(msgType)) return;

            JSONObject p = env.optJSONObject("payload");
            if (p == null) return;

            String uid = p.optString("uid", "");
            String cs  = p.optString("cs", "");

            if (uid != null && !uid.isEmpty()) {
                savePairedUid(uid, cs);
                if (l != null) l.onPaired(uid, cs);
            }
        } catch (Throwable ignored) { }
    }

    // -------------------- pairing storage --------------------

    public String getPairedUid() {
        SharedPreferences prefs = PluginPrefs.get(baseContext, pairingPreferences);
        return prefs.getString(KEY_PAIRED_UID, null);
    }

    private void savePairedUid(String uid, String callsign) {
        SharedPreferences prefs = PluginPrefs.get(baseContext, pairingPreferences);
        prefs.edit()
                .putString(KEY_PAIRED_UID, uid)
                .putString(KEY_PAIRED_CALLSIGN, callsign)
                .apply();
    }

    // -------------------- helpers --------------------

    private synchronized void closeGattInternal(String reason) {
        sessionWatchCallsign = null;
        ++sessionGeneration;
        if (setupTimeout != null) main.removeCallbacks(setupTimeout);
        setupTimeout = null;
        BluetoothGatt closing = bluetoothGatt;
        bluetoothGatt = null;
        linkUp = false;
        servicesReady = false;
        serviceRefreshGatt = null;
        refreshingServices = refreshAfterOp = false;
        gattOp = OP_NONE;
        if (closing != null) {
            if (hasConnectPermission()) {
                try {
                    closing.disconnect();
                } catch (SecurityException e) {
                    logW("Bluetooth permission denied while disconnecting: " + e.getMessage());
                } catch (RuntimeException e) {
                    logW("Unable to disconnect GATT: " + e.getMessage());
                }
            }
            try {
                closing.close();
            } catch (SecurityException e) {
                logW("Bluetooth permission denied while closing GATT: " + e.getMessage());
            } catch (RuntimeException e) {
                logW("Unable to close GATT: " + e.getMessage());
            }
        }

        bluetoothGatt = null;
        txNotifyChar = null;
        rxWriteChar = null;

        synchronized (rxLock) { rxBuffer.clear(); }
        synchronized (txLock) { txQueue.clear(); txInFlight = false; }

        if (closing != null) lastGattCloseMs = android.os.SystemClock.uptimeMillis();
        logI("closeGatt(): " + reason);
    }

    private void setConnected(boolean value) {
        if (connected != value) {
            connected = value;
            StatusListener listener = statusListener;
            if (listener != null) listener.onConnectionStatusChanged(connected, refreshingServices);
        }
    }

    private boolean hasConnectPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
        return appContext != null && ActivityCompat.checkSelfPermission(
                appContext, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        if (appContext == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADMIN)
                == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String bluetoothUnavailableReason() {
        if (bluetoothAdapter == null) return "Bluetooth adapter unavailable";
        if (!hasConnectPermission()) return "Missing Bluetooth connect permission";
        try {
            return bluetoothAdapter.isEnabled() ? null : "Bluetooth disabled";
        } catch (SecurityException e) {
            return "Missing Bluetooth connect permission";
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run();
        else main.post(action);
    }

    private void dispatchGatt(BluetoothGatt gatt, Runnable action) {
        main.post(() -> {
            synchronized (WearTakBleClient.this) {
                if (!started || gatt == null || gatt != bluetoothGatt) return;
                try {
                    action.run();
                } catch (SecurityException e) {
                    failSecureSession("Bluetooth permission unavailable: " + e.getMessage(), "permission_lost");
                }
            }
        });
    }

    private void storeConnectedDeviceName() {}

    private void error(String msg) {
        logW("ERROR: " + msg);
        JsonListener l = jsonListener.get();
        if (l != null) l.onError(msg);
    }

    private boolean safeHasServiceUuid(ScanResult r, UUID uuid) {
        try {
            if (r.getScanRecord() == null) return false;
            List<ParcelUuid> uuids = r.getScanRecord().getServiceUuids();
            if (uuids == null) return false;
            for (ParcelUuid pu : uuids) {
                if (pu != null && uuid.equals(pu.getUuid())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private String safeGetAdvName(ScanResult r) {
        try {
            if (r.getScanRecord() == null) return null;
            return r.getScanRecord().getDeviceName();
        } catch (Throwable ignored) {}
        return null;
    }

    private String safeGetDeviceName(BluetoothDevice d) {
        try {
            if (!hasConnectPermission()) return null;
            return d.getName();
        } catch (SecurityException e) {
            logW("Bluetooth permission denied while reading device name: " + e.getMessage());
        } catch (RuntimeException e) {
            logW("Unable to read Bluetooth device name: " + e.getMessage());
        }
        return null;
    }

    private String safeGetDeviceAlias(BluetoothDevice d) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        try {
            if (!hasConnectPermission()) return null;
            return d.getAlias();
        } catch (SecurityException e) {
            logW("Bluetooth permission denied while reading device alias: " + e.getMessage());
        } catch (RuntimeException e) {
            logW("Unable to read Bluetooth device alias: " + e.getMessage());
        }
        return null;
    }

    /** User-assigned name, Android device/model name, and address for a bonded watch. */
    public synchronized String describeBondedWatch(String address) {
        String alias = null;
        String model = null;
        if (bluetoothAdapter != null && address != null) {
            try {
                BluetoothDevice d = bluetoothAdapter.getRemoteDevice(address);
                alias = safeGetDeviceAlias(d);
                model = safeGetDeviceName(d);
            } catch (IllegalArgumentException ignored) {}
        }
        return SystemBondedWatchConnection.describeBondedWatch(alias, model, address);
    }

    private String normalizeWearTakName(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("WT-")) trimmed = trimmed.substring(3);
        return trimmed.trim().isEmpty() ? null : trimmed.trim();
    }

    private void logI(String msg) { LogX.i(TAG, msg); }
    private void logW(String msg) { LogX.w(TAG, msg); }
}
