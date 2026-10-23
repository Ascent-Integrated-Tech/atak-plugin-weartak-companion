package com.weartak.atak.weartak_companion.plugin;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import android.view.View;
import android.widget.TextView;
import android.content.res.Resources;

import org.junit.Test;
import org.mockito.MockMakers;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BlePickerIdentityTest {
    private static final String ADDRESS = "48:E3:6B:A8:BE:CD";
    private static final String ORIGINAL = "ODIN-WEARTAK";
    private static final String ALIAS = "ODIN-ATAK-S9FE";

    @Test public void advertisedNameAndBondedAliasRemainSeparate() {
        WearTakBleClient.DiscoveredDevice d = scan(ORIGINAL, ALIAS, ALIAS, true, null);
        assertEquals(ORIGINAL, d.name);
        assertEquals(ORIGINAL, d.advertisedName);
        assertEquals(ALIAS, d.distinctBondedName());
        assertEquals(ADDRESS, d.address);
        assertEquals(-56, d.rssi);
    }

    @Test public void noPrefixOrHyphenSegmentsAreStripped() {
        assertEquals("WT-ODIN-WEARTAK", scan("WT-ODIN-WEARTAK", ALIAS, ALIAS, true, null).name);
    }

    @Test public void matchingAliasIsNotRepeated() {
        assertNull(scan(ORIGINAL, ORIGINAL, " odin-weartak ", true, null).distinctBondedName());
    }

    @Test public void unavailableAliasUsesAndroidBondedNameAsSecondaryOnly() {
        assertEquals(ALIAS, scan(ORIGINAL, ALIAS, null, true, null).distinctBondedName());
    }

    @Test public void unavailableAdvertisementDoesNotPretendAliasIsOriginal() {
        WearTakBleClient.DiscoveredDevice d = scan(null, ALIAS, ALIAS, true, null);
        assertNull(d.name);
        assertNull(d.advertisedName);
        assertEquals(ALIAS, d.distinctBondedName());
    }

    @Test public void whollyUnavailableNamesRemainUnknown() {
        WearTakBleClient.DiscoveredDevice d = scan(" ", null, "", true, null);
        assertNull(d.name);
        assertNull(d.distinctBondedName());
    }

    @Test public void namelessPacketRetainsObservedAdvertisementForSameAddress() {
        WearTakBleClient.DiscoveredDevice prior = scan(ORIGINAL, ALIAS, ALIAS, true, null);
        WearTakBleClient.DiscoveredDevice d = scan(null, ALIAS, ALIAS, true, prior);
        assertEquals(ORIGINAL, d.name);
        assertEquals(ALIAS, d.distinctBondedName());
    }

    @Test public void advertisementIsNotBorrowedFromAnotherAddress() {
        WearTakBleClient.DiscoveredDevice prior = WearTakBleClient.DiscoveredDevice.fromScan(
                ORIGINAL, ALIAS, ALIAS, true, "00:00:00:00:00:01", -40, null);
        assertNull(scan(null, ALIAS, ALIAS, true, prior).name);
    }

    @Test public void currentAdvertisementAndBondStateReplaceOldObservations() {
        WearTakBleClient.DiscoveredDevice prior = scan(ORIGINAL, ALIAS, ALIAS, true, null);
        WearTakBleClient.DiscoveredDevice d = scan("NEW-WATCH", "cached", ALIAS, false, prior);
        assertEquals("NEW-WATCH", d.name);
        assertNull(d.distinctBondedName());
    }

    @Test public void unbondedCachedNameIsFallbackWithoutBondClaim() {
        WearTakBleClient.DiscoveredDevice d = scan(null, "WEAROS-1075", ALIAS, false, null);
        assertEquals("WEAROS-1075", d.name);
        assertNull(d.distinctBondedName());
    }

    @Test public void actualRowBindingShowsAliasAndClearsItOnReuse() {
        TextView name = mock(TextView.class);
        TextView alias = mock(TextView.class);
        Resources resources = mock(Resources.class);
        when(name.getResources()).thenReturn(resources);
        when(alias.getResources()).thenReturn(resources);
        when(resources.getString(R.string.watch_name_unavailable)).thenReturn("Unknown watch");
        when(resources.getString(R.string.watch_bonded_as, ALIAS)).thenReturn("Bonded as " + ALIAS);

        BleDeviceAdapter.bindNames(name, alias, scan(ORIGINAL, ALIAS, ALIAS, true, null));
        verify(name).setText(ORIGINAL);
        verify(alias).setText("Bonded as " + ALIAS);
        verify(alias).setVisibility(View.VISIBLE);
        clearInvocations(name, alias);

        BleDeviceAdapter.bindNames(name, alias, scan(null, null, null, true, null));
        verify(name).setText("Unknown watch");
        verify(alias).setText("");
        verify(alias).setVisibility(View.GONE);
    }

    @Test public void actualScanCallbackUsesAdvertisementAndKeepsSessionFences() throws Exception {
        Context context = mock(Context.class);
        when(context.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(0);
        when(context.checkSelfPermission(anyString())).thenReturn(0);
        WearTakBleClient client = new WearTakBleClient(context, connected -> { });
        Handler main = mock(Handler.class);
        when(main.post(any(Runnable.class))).thenAnswer(i -> {
            i.<Runnable>getArgument(0).run();
            return true;
        });
        BluetoothAdapter adapter = mock(BluetoothAdapter.class, withSettings().mockMaker(MockMakers.INLINE));
        when(adapter.isEnabled()).thenReturn(true);
        set(client, "appContext", context);
        set(client, "main", main);
        set(client, "bluetoothAdapter", adapter);
        set(client, "started", true);
        List<WearTakBleClient.DiscoveredDevice> found = new ArrayList<>();
        WearTakBleClient.ScanListener listener = new WearTakBleClient.ScanListener() {
            @Override public void onDeviceFound(WearTakBleClient.DiscoveredDevice device) { found.add(device); }
            @Override public void onScanFinished(List<WearTakBleClient.DiscoveredDevice> devices) { }
            @Override public void onScanError(String msg) { fail(msg); }
        };
        startScan(client, listener);
        ScanCallback oldCallback = (ScanCallback) get(client, "activeScanCallback");
        assertNotNull(oldCallback);
        ScanResult result = result(ORIGINAL);
        oldCallback.onScanResult(0, result);
        assertEquals(ORIGINAL, found.get(0).name);
        assertEquals(ALIAS, found.get(0).distinctBondedName());
        oldCallback.onScanResult(0, result(null));
        assertEquals(ORIGINAL, found.get(1).name);
        assertEquals(Collections.singletonList(found.get(1)), ScanDeviceList.visibleRows(
                Collections.singletonList(found.get(1)), ADDRESS, false, () -> null));

        startScan(client, listener);
        ScanCallback current = (ScanCallback) get(client, "activeScanCallback");
        oldCallback.onScanResult(0, result);
        assertEquals(2, found.size());
        current.onScanResult(0, result(null));
        assertNull(found.get(2).name); // No advertisement has been observed in this new scan.
        assertEquals(ALIAS, found.get(2).distinctBondedName());
    }

    private static void startScan(WearTakBleClient client, WearTakBleClient.ScanListener listener)
            throws Exception {
        set(client, "scanning", true);
        set(client, "activeScanCallback", client.createScanCallback(listener));
        ((java.util.Map<?, ?>) get(client, "discovered")).clear();
    }

    private static ScanResult result(String advertisedName) {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getAddress()).thenReturn(ADDRESS);
        when(device.getName()).thenReturn(ALIAS);
        when(device.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        ScanRecord record = mock(ScanRecord.class, withSettings().mockMaker(MockMakers.INLINE));
        when(record.getDeviceName()).thenReturn(advertisedName);
        ParcelUuid service = mock(ParcelUuid.class);
        when(service.getUuid()).thenReturn(UUID.fromString("0000a11a-0000-1000-8000-00805f9b34fb"));
        when(record.getServiceUuids()).thenReturn(Collections.singletonList(service));
        ScanResult result = mock(ScanResult.class, withSettings().mockMaker(MockMakers.INLINE));
        when(result.getDevice()).thenReturn(device);
        when(result.getScanRecord()).thenReturn(record);
        when(result.getRssi()).thenReturn(-56);
        return result;
    }

    private static WearTakBleClient.DiscoveredDevice scan(String adv, String cached, String alias,
                                                          boolean bonded,
                                                          WearTakBleClient.DiscoveredDevice previous) {
        return WearTakBleClient.DiscoveredDevice.fromScan(adv, cached, alias, bonded, ADDRESS, -56, previous);
    }

    private static Object get(WearTakBleClient client, String name) throws Exception {
        Field field = WearTakBleClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(client);
    }

    private static void set(WearTakBleClient client, String name, Object value) throws Exception {
        Field field = WearTakBleClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }
}
