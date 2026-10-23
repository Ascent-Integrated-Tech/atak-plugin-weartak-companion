package com.weartak.atak.weartak_companion.plugin;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockMakers;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

/** Drives user-initiated Traditional connects through the production client, adapter and GATT callback. */
public class BleConnectRetryTest {
    private static final String WATCH = "48:E3:6B:A8:BE:CD";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long SETUP_TIMEOUT_MS = 25000;

    private final ArrayDeque<Runnable> posted = new ArrayDeque<>();
    private final List<Runnable> delayed = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final List<Long> retryDelays = new ArrayList<>();
    private final List<Long> requestedDelays = new ArrayList<>();
    private final List<Boolean> status = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> progress = new ArrayList<>();
    private final List<String> trustLost = new ArrayList<>();
    private final List<BluetoothGatt> gatts = new ArrayList<>();
    private int readies;
    private int bond = BluetoothDevice.BOND_BONDED;
    private WearTakBleClient client;
    private BluetoothGattCallback callback;
    private BluetoothGattDescriptor lastCccd;
    private BluetoothDevice device;
    private BluetoothAdapter adapter;

    public static final class LeBondApi {
        int transport = -1;
        boolean accepted;
        public boolean createBond(int transport) {
            this.transport = transport;
            if (!accepted) throw new SecurityException("denied");
            return true;
        }
    }

    @Before public void createStartedTraditionalClient() throws Exception {
        HostContext context = new HostContext();
        client = new WearTakBleClient(context, status::add);
        Handler main = mock(Handler.class);
        when(main.post(any(Runnable.class))).thenAnswer(i -> posted.add(i.getArgument(0)));
        when(main.postDelayed(any(Runnable.class), anyLong())).thenAnswer(i -> {
            delayed.add(i.getArgument(0));
            delays.add(i.getArgument(1));
            requestedDelays.add(i.getArgument(1));
            return true;
        });
        doAnswer(i -> {
            posted.remove(i.<Runnable>getArgument(0));
            int at = delayed.indexOf(i.<Runnable>getArgument(0));
            if (at >= 0) { delayed.remove(at); delays.remove(at); }
            return null;
        }).when(main).removeCallbacks(any(Runnable.class));
        doAnswer(i -> { posted.clear(); delayed.clear(); delays.clear(); return null; })
                .when(main).removeCallbacksAndMessages(any());

        device = mock(BluetoothDevice.class);
        when(device.getAddress()).thenReturn(WATCH);
        when(device.getBondState()).thenAnswer(i -> bond);
        when(device.connectGatt(any(Context.class), anyBoolean(), any(BluetoothGattCallback.class), anyInt()))
                .thenAnswer(i -> {
                    BluetoothGatt gatt = gatt(device);
                    gatts.add(gatt);
                    return gatt;
                });
        adapter = mock(BluetoothAdapter.class, withSettings().mockMaker(MockMakers.INLINE));
        when(adapter.isEnabled()).thenReturn(true);
        when(adapter.getRemoteDevice(anyString())).thenReturn(device);

        set("appContext", context);
        set("main", main);
        set("bluetoothAdapter", adapter);
        set("started", true);
        client.setAutomaticReconnectAllowed(true); // Traditional BLE mode
        client.setJsonListener(new Listener());
        callback = (BluetoothGattCallback) get("gattCallback");
    }

    @Test public void reflectiveBondRequestsOnlyLe() throws Exception {
        LeBondApi peer = new LeBondApi();
        peer.accepted = true;
        assertTrue(WearTakBleClient.invokeLeBond(peer));
        assertEquals(BluetoothDevice.TRANSPORT_LE, peer.transport);
    }

    public static final class RejectedLeBondApi {
        public boolean createBond(int transport) {
            assertEquals(BluetoothDevice.TRANSPORT_LE, transport);
            return false;
        }
    }

    @Test public void reflectiveBondPreservesRejectedResult() throws Exception {
        assertFalse(WearTakBleClient.invokeLeBond(new RejectedLeBondApi()));
    }

    @Test public void unavailableLeOverloadFailsWithoutAutoFallback() throws Exception {
        bond = BluetoothDevice.BOND_NONE; // SDK mock intentionally has no hidden overload.
        userConnect();
        verify(device, never()).createBond();
        assertNull(get("pendingBondDevice"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("AUTO/Classic pairing was not attempted"));
        assertTrue(gatts.isEmpty());
    }

    @Test public void reflectiveBondPropagatesPermissionFailure() throws Exception {
        LeBondApi peer = new LeBondApi();
        try {
            WearTakBleClient.invokeLeBond(peer);
            org.junit.Assert.fail("Permission denial must propagate");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(e.getCause() instanceof SecurityException);
        }
        assertEquals(BluetoothDevice.TRANSPORT_LE, peer.transport);
    }

    @Test public void existingBondConnectsOverLeWithoutRequestingBond() {
        userConnect();
        verify(device, never()).createBond();
        verify(device).connectGatt(any(), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE));
        assertEquals(1, gatts.size());
        assertTrue(errors.isEmpty());
    }

    @Test public void samsungOwnedConnectNeverCreatesMissingBond() throws Exception {
        bond = BluetoothDevice.BOND_NONE;
        client.connectToSelectedDevice(new WearTakBleClient.DiscoveredDevice("w", WATCH, -50), false);
        settle();
        verify(device, never()).createBond();
        assertNull(get("pendingBondDevice"));
        assertEquals(1, errors.size());
        assertTrue(gatts.isEmpty());
    }

    @Test public void transient133ThenSuccessBecomesReadyWithoutError() throws Exception {
        userConnect();
        assertEquals(1, gatts.size());

        fail(gatts.get(0), 133);
        assertEquals(Collections.emptyList(), errors);
        assertEquals(Collections.singletonList("Retrying connection (1/2)\u2026"), progress);
        assertEquals(Collections.singletonList(1000L), retryDelays);
        assertTrue(closed(gatts.get(0)));

        settle();
        assertEquals(2, gatts.size());
        ready(gatts.get(1));

        assertEquals(Collections.emptyList(), errors);
        assertEquals(1, readies);
        assertEquals(Collections.singletonList(true), status);
        assertTrue(client.isConnected());
    }

    @Test public void eachTransientSetupStatusIsRetried() throws Exception {
        for (int code : new int[]{133, 8, 62, 147}) {
            userConnect();
            int before = gatts.size();
            fail(gatts.get(before - 1), code);
            settle();
            assertEquals("status " + code, before + 1, gatts.size());
            client.disconnect();
            settle();
        }
        assertEquals(Collections.emptyList(), errors);
    }

    @Test public void persistent133GivesExactlyOneFinalErrorAfterBoundedRetries() throws Exception {
        userConnect();
        for (int attempt = 0; attempt < 3; attempt++) {
            fail(gatts.get(gatts.size() - 1), 133);
            settle();
        }

        assertEquals(3, gatts.size());
        assertEquals(Arrays.asList("Retrying connection (1/2)\u2026", "Retrying connection (2/2)\u2026"), progress);
        assertEquals(Arrays.asList(1000L, 2000L), retryDelays);
        assertEquals(Collections.singletonList("GATT connection failed: 133"), errors);
        assertTrue(status.isEmpty());
        assertTrue(delayed.isEmpty());
    }

    @Test public void disconnectDuringBackoffCancelsRetry() throws Exception {
        userConnect();
        fail(gatts.get(0), 133);
        client.disconnect();
        settle();

        assertEquals(1, gatts.size());
        assertEquals(Collections.emptyList(), errors);
    }

    @Test public void newScanOrConnectOrStopDuringBackoffCancelsRetry() throws Exception {
        userConnect();
        fail(gatts.get(0), 133);
        client.resetSession("UI scan 9");
        settle();
        assertEquals(1, gatts.size());

        userConnect();
        fail(gatts.get(1), 133);
        userConnect(); // new user connect supersedes the pending retry
        assertEquals(3, gatts.size());
        fail(gatts.get(2), 133);
        assertEquals(Arrays.asList("Retrying connection (1/2)\u2026", "Retrying connection (1/2)\u2026",
                "Retrying connection (1/2)\u2026"), progress);
        settle();
        assertEquals("only the newest connect's single retry runs", 4, gatts.size());

        fail(gatts.get(3), 133);
        client.stop();
        settle();
        assertEquals(4, gatts.size());
    }

    @Test public void bondLossIsNeverRetried() throws Exception {
        userConnect();
        bond = BluetoothDevice.BOND_NONE;
        fail(gatts.get(0), 133);
        settle();

        assertEquals(1, gatts.size());
        assertTrue(progress.isEmpty());
        assertEquals(Collections.singletonList("GATT connection failed: 133"), errors);
    }

    @Test public void failureAfterLinkCameUpIsNeverRetried() throws Exception {
        userConnect();
        callback.onConnectionStateChange(gatts.get(0), BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
        drain();
        fail(gatts.get(0), 8);
        settle();

        assertEquals(1, gatts.size());
        assertTrue(progress.isEmpty());
        assertEquals(Collections.singletonList("GATT connection failed: 8"), errors);
    }

    @Test public void nonTransientStatusAndSamsungOwnedConnectAreNotRetried() throws Exception {
        userConnect();
        fail(gatts.get(0), 5); // insufficient authentication
        settle();
        assertEquals(1, gatts.size());

        client.connectToSelectedDevice(new WearTakBleClient.DiscoveredDevice("w", WATCH, -50), false);
        settle();
        fail(gatts.get(1), 133);
        settle();
        assertEquals(2, gatts.size());
        assertTrue(progress.isEmpty());
        assertEquals(Arrays.asList("GATT connection failed: 5", "GATT connection failed: 133"), errors);
    }

    @Test public void readySessionRestoresFullRetryBudgetForNextUserConnect() throws Exception {
        userConnect();
        fail(gatts.get(0), 133);
        settle();
        fail(gatts.get(1), 133);
        settle();
        ready(gatts.get(2));
        client.disconnect();

        userConnect();
        fail(gatts.get(3), 133);
        settle();
        assertEquals(5, gatts.size());
        assertEquals("Retrying connection (1/2)\u2026", progress.get(progress.size() - 1));
    }

    @Test public void connectWaitsForScanSettleAndPostCloseCooldown() throws Exception {
        set("lastScanStopMs", 0L);
        set("lastGattCloseMs", Long.MIN_VALUE / 4);
        client.connectToSelectedDevice(new WearTakBleClient.DiscoveredDevice("w", WATCH, -50));
        assertTrue(gatts.isEmpty());
        assertEquals(Long.valueOf(300L), delays.get(delays.size() - 1));
        settle();
        assertEquals(1, gatts.size());

        set("lastScanStopMs", Long.MIN_VALUE / 4);
        set("lastGattCloseMs", 0L);
        client.connectToSelectedDevice(new WearTakBleClient.DiscoveredDevice("w", WATCH, -50));
        assertEquals(1, gatts.size());
        assertEquals(Long.valueOf(1000L), delays.get(delays.size() - 1));
    }

    private void userConnect() {
        client.connectToSelectedDevice(new WearTakBleClient.DiscoveredDevice("w", WATCH, -50));
        settle();
    }

    private void fail(BluetoothGatt gatt, int code) {
        int before = requestedDelays.size();
        callback.onConnectionStateChange(gatt, code, BluetoothProfile.STATE_DISCONNECTED);
        drain();
        for (int i = before; i < requestedDelays.size(); i++) {
            if (requestedDelays.get(i) < SETUP_TIMEOUT_MS) retryDelays.add(requestedDelays.get(i));
        }
    }

    private void ready(BluetoothGatt gatt) {
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
        drain();
        callback.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS);
        drain();
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS);
        drain();
        callback.onDescriptorWrite(gatt, lastCccd, BluetoothGatt.GATT_SUCCESS);
        drain();
    }

    /** Runs posted work and every short delay (backoff, cooldown); leaves only the 25 s setup timeout. */
    private void settle() {
        boolean progressMade = true;
        while (progressMade) {
            progressMade = false;
            drain();
            for (int i = 0; i < delayed.size(); i++) {
                if (delays.get(i) < SETUP_TIMEOUT_MS) {
                    Runnable r = delayed.remove(i);
                    delays.remove(i);
                    r.run();
                    progressMade = true;
                    break;
                }
            }
        }
    }

    private void drain() {
        while (!posted.isEmpty()) posted.poll().run();
    }

    private boolean closed(BluetoothGatt gatt) {
        try {
            org.mockito.Mockito.verify(gatt).close();
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    private BluetoothGatt gatt(BluetoothDevice device) {
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        BluetoothGattService service = service();
        when(gatt.getDevice()).thenReturn(device);
        when(gatt.requestMtu(anyInt())).thenReturn(true);
        when(gatt.discoverServices()).thenReturn(true);
        when(gatt.getService(any(UUID.class))).thenAnswer(i ->
                WearTakBleClient.COMPANION_SERVICE_UUID.equals(i.getArgument(0)) ? service : null);
        when(gatt.setCharacteristicNotification(any(BluetoothGattCharacteristic.class), anyBoolean()))
                .thenReturn(true);
        when(gatt.writeDescriptor(any(BluetoothGattDescriptor.class))).thenAnswer(i -> {
            lastCccd = i.getArgument(0);
            return true;
        });
        return gatt;
    }

    private static BluetoothGattService service() {
        BluetoothGattCharacteristic tx = mock(BluetoothGattCharacteristic.class);
        BluetoothGattCharacteristic rx = mock(BluetoothGattCharacteristic.class);
        BluetoothGattDescriptor cccd = mock(BluetoothGattDescriptor.class);
        when(tx.getUuid()).thenReturn(WearTakBleClient.TX_TO_COMPANION_UUID);
        when(tx.getDescriptor(CCCD)).thenReturn(cccd);
        when(rx.getUuid()).thenReturn(WearTakBleClient.RX_FROM_COMPANION_UUID);
        when(cccd.getUuid()).thenReturn(CCCD);
        when(cccd.getCharacteristic()).thenReturn(tx);
        BluetoothGattService service = mock(BluetoothGattService.class);
        when(service.getCharacteristic(WearTakBleClient.TX_TO_COMPANION_UUID)).thenReturn(tx);
        when(service.getCharacteristic(WearTakBleClient.RX_FROM_COMPANION_UUID)).thenReturn(rx);
        return service;
    }

    private Object get(String name) throws Exception {
        Field field = WearTakBleClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(client);
    }

    private void set(String name, Object value) throws Exception {
        Field field = WearTakBleClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private final class Listener implements WearTakBleClient.JsonListener {
        @Override public void onReady() { readies++; }
        @Override public void onJson(String jsonLine) { }
        @Override public void onPaired(String deviceId, String callsign) { }
        @Override public void onError(String msg) { errors.add(msg); }
        @Override public void onTrustLost(String deviceAddress, String reason) { trustLost.add(reason); }
        // No @Override so this harness also compiles against the pre-retry baseline.
        public void onConnectionProgress(String message) { progress.add(message); }
    }

    private static final class HostContext extends ContextWrapper {
        private final Map<String, Map<String, Object>> files = new HashMap<>();

        HostContext() { super(null); }
        @Override public Context getApplicationContext() { return this; }
        @Override public int checkPermission(String permission, int pid, int uid) { return 0; }
        @Override public int checkSelfPermission(String permission) { return 0; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            Map<String, Object> values = files.computeIfAbsent(name, n -> new HashMap<>());
            SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("put")) values.put((String) args[0], args[1]);
                        else if ("remove".equals(method.getName())) values.remove(args[0]);
                        else if ("clear".equals(method.getName())) values.clear();
                        if ("commit".equals(method.getName())) return true;
                        return "apply".equals(method.getName()) ? null : proxy;
                    });
            return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if (method.getName().startsWith("get")) return values.getOrDefault(args[0], args[1]);
                        if ("contains".equals(method.getName())) return values.containsKey(args[0]);
                        if ("edit".equals(method.getName())) return editor;
                        throw new AssertionError(method.getName());
                    });
        }
    }
}
