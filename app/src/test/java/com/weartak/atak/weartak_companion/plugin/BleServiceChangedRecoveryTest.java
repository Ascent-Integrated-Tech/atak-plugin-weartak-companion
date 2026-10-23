package com.weartak.atak.weartak_companion.plugin;

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

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Drives the production GATT callback and main-thread dispatch through mocked Bluetooth objects. */
public class BleServiceChangedRecoveryTest {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final ArrayDeque<Runnable> posted = new ArrayDeque<>();
    private final List<Runnable> delayed = new ArrayList<>();
    private final List<Boolean> status = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private int readies;
    private WearTakBleClient client;
    private BluetoothGattCallback callback;

    @Before public void createStartedClient() throws Exception {
        HostContext context = new HostContext();
        client = new WearTakBleClient(context, status::add);
        Handler main = mock(Handler.class);
        when(main.post(any(Runnable.class))).thenAnswer(i -> posted.add(i.getArgument(0)));
        when(main.postDelayed(any(Runnable.class), anyLong())).thenAnswer(i -> delayed.add(i.getArgument(0)));
        doAnswer(i -> {
            posted.remove(i.<Runnable>getArgument(0));
            delayed.remove(i.<Runnable>getArgument(0));
            return null;
        }).when(main).removeCallbacks(any(Runnable.class));
        set("appContext", context);
        set("main", main);
        set("started", true);
        client.setAutomaticReconnectAllowed(false); // Samsung Wearable mode
        client.setJsonListener(new WearTakBleClient.JsonListener() {
            @Override public void onReady() { readies++; }
            @Override public void onJson(String jsonLine) { }
            @Override public void onPaired(String deviceId, String callsign) { }
            @Override public void onError(String msg) { errors.add(msg); }
            @Override public void onTrustLost(String deviceAddress, String reason) { }
        });
        callback = (BluetoothGattCallback) get("gattCallback");
    }

    @Test public void samsungReadyServiceChangeRediscoversOnSameBondedConnection() throws Exception {
        assertReadyServiceChangeRefreshesInPlace();
    }

    @Test public void traditionalReadyServiceChangeUsesTheSameInPlaceRefresh() throws Exception {
        client.setAutomaticReconnectAllowed(true);
        assertReadyServiceChangeRefreshesInPlace();
    }

    @Test public void repeatedServiceChangeOnSameConnectionIsBoundedEvenAfterReady() throws Exception {
        Peer peer = connectReady("AA:BB:CC:DD:EE:01");
        serviceChanged(peer);
        completeDiscovery(peer);
        assertTrue(client.getReadySession() >= 0);

        serviceChanged(peer);

        assertTrue(peer.closed);
        assertNull(get("bluetoothGatt"));
        assertEquals(2, peer.discoveries);
        assertEquals("Watch services changed; connection setup stopped. Retry Connect.", lastError());
        assertFalse(refreshing());
    }

    @Test public void newConnectionGetsItsOwnSingleRefresh() throws Exception {
        Peer first = connectReady("AA:BB:CC:DD:EE:01");
        serviceChanged(first);
        serviceChanged(first);
        assertTrue(first.closed);

        Peer second = connectReady("AA:BB:CC:DD:EE:01");
        serviceChanged(second);

        assertFalse(second.closed);
        assertSame(second.gatt, get("bluetoothGatt"));
        assertEquals(2, second.discoveries);
        assertTrue(refreshing());
    }

    @Test public void refreshTimeoutFailsClosedButCompletedRefreshTimerIsInert() throws Exception {
        Peer peer = connectReady("AA:BB:CC:DD:EE:01");
        serviceChanged(peer);
        assertEquals(1, delayed.size());
        Runnable completedTimer = delayed.get(0);
        completeDiscovery(peer);
        assertTrue(delayed.isEmpty());
        completedTimer.run();
        posted.clear();
        assertFalse(peer.closed);
        assertTrue(client.getReadySession() >= 0);

        Peer foreign = peer("AA:BB:CC:DD:EE:09");
        serviceChanged(foreign);
        assertFalse(peer.closed);
        assertEquals(0, foreign.discoveries);
        assertEquals(2, peer.discoveries);

        Peer next = connectReady("AA:BB:CC:DD:EE:02");
        serviceChanged(next);
        delayed.remove(0).run();
        drain();

        assertTrue(next.closed);
        assertEquals("BLE setup timed out before transport ready", lastError());
        assertFalse(refreshing());
    }

    @Test public void setupServiceChangeBeforeMtuIssuesOneDiscoveryAndCompletes() throws Exception {
        Peer peer = attach("AA:BB:CC:DD:EE:01");
        callback.onConnectionStateChange(peer.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
        drain();
        assertEquals(1, peer.mtuRequests);

        serviceChanged(peer);
        assertFalse(peer.closed);
        assertEquals(0, peer.discoveries); // serialized behind the outstanding MTU request
        callback.onMtuChanged(peer.gatt, 247, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertEquals(1, peer.discoveries);

        completeDiscovery(peer);
        assertTrue(client.getReadySession() >= 0);
        assertEquals(1, readies);
        assertTrue(errors.isEmpty());
    }

    @Test public void pendingPreChangeDiscoveryResultIsConsumedThenRediscovered() throws Exception {
        Peer peer = attach("AA:BB:CC:DD:EE:01");
        callback.onConnectionStateChange(peer.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
        drain();
        callback.onMtuChanged(peer.gatt, 247, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertEquals(1, peer.discoveries);

        serviceChanged(peer);
        assertEquals(1, peer.discoveries);
        callback.onServicesDiscovered(peer.gatt, BluetoothGatt.GATT_SUCCESS); // old-database result
        drain();

        assertEquals(0, peer.cccdWrites); // nothing from the stale result was trusted
        assertEquals(2, peer.discoveries);
        assertEquals(-1, client.getReadySession());
        callback.onServicesDiscovered(peer.gatt, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertEquals(1, peer.cccdWrites);
        callback.onDescriptorWrite(peer.gatt, peer.lastCccd, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertTrue(client.getReadySession() >= 0);
        assertEquals(1, readies);
        assertFalse(peer.closed);
        assertTrue(errors.isEmpty());
        assertTrue(delayed.isEmpty());
    }

    @Test public void staleCccdAckCannotMarkPostChangeSessionReady() throws Exception {
        Peer peer = attach("AA:BB:CC:DD:EE:01");
        callback.onConnectionStateChange(peer.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
        drain();
        callback.onMtuChanged(peer.gatt, 247, BluetoothGatt.GATT_SUCCESS);
        drain();
        callback.onServicesDiscovered(peer.gatt, BluetoothGatt.GATT_SUCCESS);
        drain();
        BluetoothGattDescriptor oldCccd = peer.lastCccd;
        peer.reuseServices = true;

        serviceChanged(peer);
        assertEquals(1, peer.discoveries); // old database stays intact until its CCCD ack arrives
        callback.onDescriptorWrite(peer.gatt, oldCccd, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertEquals(2, peer.discoveries);
        assertEquals(0, readies);

        // Android maps late acks by handle onto the rebuilt database; no CCCD request is outstanding.
        BluetoothGattDescriptor newCccd = peer.service
                .getCharacteristic(WearTakBleClient.TX_TO_COMPANION_UUID).getDescriptor(CCCD);
        assertSame(oldCccd, newCccd);
        callback.onDescriptorWrite(peer.gatt, newCccd, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertEquals(-1, client.getReadySession());
        assertEquals(0, readies);

        completeDiscovery(peer);
        assertSame(newCccd, peer.lastCccd);
        assertTrue(client.getReadySession() >= 0);
        assertEquals(1, readies);
        assertEquals(2, peer.cccdWrites);
        assertTrue(errors.isEmpty());
    }

    @Test public void staleTxAckIsConsumedAndCannotAdvanceNewSessionQueue() throws Exception {
        Peer peer = connectReady("AA:BB:CC:DD:EE:01");
        peer.reuseServices = true;
        String message = json(400); // three 180-byte chunks at MTU 247
        assertTrue(client.writeJsonLineToWatch(message, client.getReadySession()));
        drain();
        assertEquals(1, peer.txWrites);
        BluetoothGattCharacteristic oldRx = peer.service.getCharacteristic(WearTakBleClient.RX_FROM_COMPANION_UUID);

        serviceChanged(peer);
        assertEquals(1, peer.discoveries); // waits for the in-flight chunk's ack
        callback.onCharacteristicWrite(peer.gatt, oldRx, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertEquals(2, peer.discoveries);
        assertEquals(1, peer.txWrites); // the cleared old queue did not resume

        BluetoothGattCharacteristic newRx = peer.service.getCharacteristic(WearTakBleClient.RX_FROM_COMPANION_UUID);
        assertSame(oldRx, newRx);
        callback.onCharacteristicWrite(peer.gatt, newRx, BluetoothGatt.GATT_SUCCESS);
        drain();
        completeDiscovery(peer);
        assertEquals(1, peer.txWrites);
        assertTrue(client.gatewayTxIdle());

        assertTrue(client.writeJsonLineToWatch(message, client.getReadySession()));
        drain();
        assertEquals(2, peer.txWrites);
        callback.onCharacteristicWrite(peer.gatt, newRx, BluetoothGatt.GATT_SUCCESS);
        callback.onCharacteristicWrite(peer.gatt, newRx, BluetoothGatt.GATT_SUCCESS); // duplicate/stale
        drain();
        assertEquals(3, peer.txWrites); // one ack advanced exactly one chunk
        callback.onCharacteristicWrite(peer.gatt, newRx, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertEquals(4, peer.txWrites);
        callback.onCharacteristicWrite(peer.gatt, newRx, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertTrue(client.gatewayTxIdle());
        assertEquals(4, peer.txWrites);
        assertFalse(peer.closed);
        assertTrue(errors.isEmpty());
    }

    @Test public void missingPreChangeCompletionTimesOutWithoutOverlappingDiscovery() throws Exception {
        Peer peer = connectReady("AA:BB:CC:DD:EE:01");
        assertTrue(client.writeJsonLineToWatch(json(400), client.getReadySession()));
        drain();
        BluetoothGattCharacteristic oldRx = peer.service.getCharacteristic(WearTakBleClient.RX_FROM_COMPANION_UUID);
        serviceChanged(peer);
        assertEquals(1, peer.discoveries);
        delayed.remove(0).run();
        drain();
        assertTrue(peer.closed);
        assertEquals(1, peer.discoveries);
        assertEquals("BLE setup timed out before transport ready", lastError());

        Peer next = connectReady("AA:BB:CC:DD:EE:01");
        int readyCount = readies;
        callback.onServicesDiscovered(peer.gatt, BluetoothGatt.GATT_SUCCESS);
        callback.onDescriptorWrite(peer.gatt, peer.lastCccd, BluetoothGatt.GATT_SUCCESS);
        callback.onCharacteristicWrite(peer.gatt, oldRx, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertSame(next.gatt, get("bluetoothGatt"));
        assertEquals(readyCount, readies);
        assertTrue(client.getReadySession() >= 0);
        assertEquals(1, next.discoveries);
        assertEquals(0, next.txWrites);
    }

    @Test public void repeatedChangeWhileDrainingCannotRestartRecoveryBudget() throws Exception {
        Peer peer = connectReady("AA:BB:CC:DD:EE:01");
        assertTrue(client.writeJsonLineToWatch(json(400), client.getReadySession()));
        drain();
        serviceChanged(peer);
        serviceChanged(peer);
        assertTrue(peer.closed);
        assertEquals(1, peer.discoveries);
        assertFalse(refreshing());
        assertTrue(delayed.isEmpty());
        assertEquals(1, errors.size());
    }

    @Test public void samsungOwnerStaysReadyThroughRefreshIncludingFastReadyRace() throws Exception {
        Owner owner = connectOwner();
        Peer peer = owner.peer;

        serviceChanged(peer);
        completeDiscovery(peer); // ready again before the plugin main thread runs
        assertEquals(2, owner.pluginMain.size()); // false(refreshing) then true
        owner.runPluginMain();

        assertEquals(SystemBondedWatchConnection.State.CONNECTED, owner.connection.getState());
        assertEquals(0, owner.resets);
        assertTrue(client.getReadySession() >= 0);

        // An actual link loss after the refresh still reaches the owner as a disconnect.
        callback.onConnectionStateChange(peer.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED);
        drain();
        owner.runPluginMain();
        assertNotEquals(SystemBondedWatchConnection.State.CONNECTED, owner.connection.getState());
        assertEquals(1, owner.resets);
    }

    @Test public void samsungOwnerSeesRefreshTimeoutExactlyOnce() throws Exception {
        Owner owner = connectOwner();
        serviceChanged(owner.peer);
        owner.runPluginMain();
        assertEquals(SystemBondedWatchConnection.State.CONNECTED, owner.connection.getState());

        delayed.remove(0).run();
        drain();
        owner.runPluginMain();

        assertTrue(owner.peer.closed);
        assertEquals(Arrays.asList("BLE setup timed out before transport ready"), errors);
        assertNotEquals(SystemBondedWatchConnection.State.CONNECTED, owner.connection.getState());
        assertEquals(1, owner.resets);
    }

    @Test public void samsungOwnerSeesRefreshDiscoveryErrorExactlyOnce() throws Exception {
        Owner owner = connectOwner();
        serviceChanged(owner.peer);
        callback.onServicesDiscovered(owner.peer.gatt, 133);
        drain();
        owner.runPluginMain();

        assertTrue(owner.peer.closed);
        assertEquals(1, errors.size());
        assertNotEquals(SystemBondedWatchConnection.State.CONNECTED, owner.connection.getState());
        assertEquals(1, owner.resets);
    }

    /** Real client and real Samsung owner joined like WearTakPlugin: status/error hop to plugin main. */
    private Owner connectOwner() throws Exception {
        Owner owner = new Owner();
        owner.connection = new SystemBondedWatchConnection(new SystemBondedWatchConnection.Scheduler() {
            @Override public void execute(Runnable action) { action.run(); }
            @Override public void later(Runnable action, long delayMs) { }
            @Override public void cancel(Runnable action) { }
        }, new SystemBondedWatchConnection.Transport() {
            final WearTakBleClient.DiscoveredDevice device =
                    new WearTakBleClient.DiscoveredDevice("WearTAK", "AA:BB:CC:DD:EE:01", 0);
            @Override public WearTakBleClient.DiscoveredDevice knownBondedCandidate() { return device; }
            @Override public void find(WearTakBleClient.BondedCompanionListener listener) { listener.onFound(device); }
            @Override public void connect(WearTakBleClient.DiscoveredDevice device) { }
            @Override public void reset() { owner.resets++; }
        }, new SystemBondedWatchConnection.Listener() {
            @Override public void onStatus(String message) { }
            @Override public void onConnecting(WearTakBleClient.DiscoveredDevice device) { }
            @Override public void onCandidate(WearTakBleClient.DiscoveredDevice device) { }
        });
        client.setStatusListener((WearTakBleClient.RefreshAwareStatusListener) (connected, refreshing) ->
                owner.pluginMain.add(() -> owner.connection.onConnectionChanged(connected, refreshing)));
        client.setJsonListener(new WearTakBleClient.JsonListener() {
            @Override public void onReady() { readies++; }
            @Override public void onJson(String jsonLine) { }
            @Override public void onPaired(String deviceId, String callsign) { }
            @Override public void onError(String msg) {
                errors.add(msg);
                owner.pluginMain.add(() -> owner.connection.onError(msg));
            }
            @Override public void onTrustLost(String deviceAddress, String reason) { }
        });
        owner.connection.start();
        assertEquals(SystemBondedWatchConnection.State.CONNECTING, owner.connection.getState());
        owner.peer = connectReady("AA:BB:CC:DD:EE:01");
        owner.runPluginMain();
        assertEquals(SystemBondedWatchConnection.State.CONNECTED, owner.connection.getState());
        return owner;
    }

    private static final class Owner {
        final ArrayDeque<Runnable> pluginMain = new ArrayDeque<>();
        SystemBondedWatchConnection connection;
        Peer peer;
        int resets;
        void runPluginMain() {
            while (!pluginMain.isEmpty()) pluginMain.poll().run();
        }
    }

    private static String json(int size) {
        StringBuilder value = new StringBuilder();
        while (value.length() < size) value.append('x');
        return "{\"v\":\"" + value + "\"}";
    }

    @Test public void unbondedServiceChangeStillFailsClosed() throws Exception {
        Peer peer = connectReady("AA:BB:CC:DD:EE:01");
        peer.bond = BluetoothDevice.BOND_NONE;

        serviceChanged(peer);

        assertTrue(peer.closed);
        assertEquals(1, peer.discoveries);
        assertEquals("Watch services changed without an existing bond", lastError());
    }

    private void assertReadyServiceChangeRefreshesInPlace() throws Exception {
        Peer peer = connectReady("AA:BB:CC:DD:EE:01");
        long oldSession = client.getReadySession();
        BluetoothGattDescriptor oldCccd = peer.lastCccd;
        BluetoothGattCharacteristic oldRx = peer.service.getCharacteristic(WearTakBleClient.RX_FROM_COMPANION_UUID);
        assertTrue(oldSession >= 0);

        serviceChanged(peer);
        callback.onCharacteristicWrite(peer.gatt, oldRx, 133); // old-database write failure is fenced
        drain();

        assertFalse(peer.closed);
        assertSame(peer.gatt, get("bluetoothGatt"));
        assertEquals(2, peer.discoveries);
        assertEquals(-1, client.getReadySession());
        assertTrue(refreshing());
        assertEquals(Arrays.asList(true, false), status);
        assertTrue(errors.isEmpty());
        assertFalse(client.writeJsonLineToWatch("{}", oldSession));

        callback.onDescriptorWrite(peer.gatt, oldCccd, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertEquals(-1, client.getReadySession());
        assertEquals(1, readies);

        completeDiscovery(peer);
        assertNotSame(oldCccd, peer.lastCccd);
        assertTrue(client.getReadySession() > oldSession);
        assertEquals(2, readies);
        assertEquals(Arrays.asList(true, false, true), status);
        assertFalse(refreshing());
        assertTrue(delayed.isEmpty());
    }

    @Test public void setupFailureBeforeReadyReportsErrorButNoDisconnectedStatus() throws Exception {
        Peer peer = attach("AA:BB:CC:DD:EE:01");
        callback.onConnectionStateChange(peer.gatt, 147, BluetoothProfile.STATE_DISCONNECTED);
        drain();

        assertEquals("GATT connection failed: 147", lastError());
        assertTrue(peer.closed);
        assertFalse(client.isConnected());
        assertTrue("never-ready session has no connected->disconnected transition", status.isEmpty());
    }

    private Peer connectReady(String address) throws Exception {
        Peer peer = attach(address);
        callback.onConnectionStateChange(peer.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
        drain();
        callback.onMtuChanged(peer.gatt, 247, BluetoothGatt.GATT_SUCCESS);
        drain();
        completeDiscovery(peer);
        return peer;
    }

    private Peer attach(String address) throws Exception {
        Peer peer = peer(address);
        set("bluetoothGatt", peer.gatt);
        return peer;
    }

    private void completeDiscovery(Peer peer) {
        callback.onServicesDiscovered(peer.gatt, BluetoothGatt.GATT_SUCCESS);
        drain();
        callback.onDescriptorWrite(peer.gatt, peer.lastCccd, BluetoothGatt.GATT_SUCCESS);
        drain();
    }

    private void serviceChanged(Peer peer) {
        callback.onServiceChanged(peer.gatt);
        drain();
    }

    private void drain() {
        while (!posted.isEmpty()) posted.poll().run();
    }

    private String lastError() {
        return errors.isEmpty() ? null : errors.get(errors.size() - 1);
    }

    private boolean refreshing() throws Exception {
        return (Boolean) WearTakBleClient.class.getMethod("isRefreshingServices").invoke(client);
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

    /** Bonded peer whose every discovery publishes fresh objects, like Android's rebuilt GATT database. */
    private static Peer peer(String address) {
        Peer peer = new Peer();
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getAddress()).thenReturn(address);
        when(device.getBondState()).thenAnswer(i -> peer.bond);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        when(gatt.getDevice()).thenReturn(device);
        when(gatt.requestMtu(anyInt())).thenAnswer(i -> ++peer.mtuRequests > 0);
        when(gatt.discoverServices()).thenAnswer(i -> {
            peer.discoveries++;
            if (!peer.reuseServices || peer.service == null) peer.service = service();
            return true;
        });
        when(gatt.getService(any(UUID.class))).thenAnswer(i ->
                WearTakBleClient.COMPANION_SERVICE_UUID.equals(i.getArgument(0)) ? peer.service : null);
        when(gatt.setCharacteristicNotification(any(BluetoothGattCharacteristic.class), anyBoolean()))
                .thenReturn(true);
        when(gatt.writeDescriptor(any(BluetoothGattDescriptor.class))).thenAnswer(i -> {
            peer.lastCccd = i.getArgument(0);
            peer.cccdWrites++;
            return true;
        });
        when(gatt.writeCharacteristic(any(BluetoothGattCharacteristic.class))).thenAnswer(i -> ++peer.txWrites > 0);
        doAnswer(i -> peer.closed = true).when(gatt).close();
        peer.gatt = gatt;
        return peer;
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

    private static final class Peer {
        BluetoothGatt gatt;
        BluetoothGattService service;
        BluetoothGattDescriptor lastCccd;
        int bond = BluetoothDevice.BOND_BONDED;
        int mtuRequests, discoveries, cccdWrites, txWrites;
        boolean closed, reuseServices;
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
