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
        assertEquals(1, peer.discoveries);
        callback.onMtuChanged(peer.gatt, 247, BluetoothGatt.GATT_SUCCESS);
        drain();
        assertEquals(1, peer.discoveries);

        completeDiscovery(peer);
        assertTrue(client.getReadySession() >= 0);
        assertEquals(1, readies);
        assertTrue(errors.isEmpty());
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
            peer.service = service();
            return true;
        });
        when(gatt.getService(any(UUID.class))).thenAnswer(i ->
                WearTakBleClient.COMPANION_SERVICE_UUID.equals(i.getArgument(0)) ? peer.service : null);
        when(gatt.setCharacteristicNotification(any(BluetoothGattCharacteristic.class), anyBoolean()))
                .thenReturn(true);
        when(gatt.writeDescriptor(any(BluetoothGattDescriptor.class))).thenAnswer(i -> {
            peer.lastCccd = i.getArgument(0);
            return true;
        });
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
        int mtuRequests, discoveries;
        boolean closed;
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
