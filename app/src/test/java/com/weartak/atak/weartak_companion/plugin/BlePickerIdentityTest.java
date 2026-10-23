package com.weartak.atak.weartak_companion.plugin;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.ParcelUuid;
import android.view.View;
import android.widget.TextView;
import android.content.res.Resources;

import org.junit.Test;
import org.mockito.MockMakers;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
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

    @Test public void unavailableAdvertisementUsesBondedNameWithoutPretendingItIsOriginal() {
        WearTakBleClient.DiscoveredDevice d = scan(null, ALIAS, ALIAS, true, null);
        assertNull(d.name);
        assertNull(d.advertisedName);
        assertEquals(ALIAS, d.primaryName());
        assertNull(d.distinctBondedName());
    }

    @Test public void distinctAndroidRemoteNameIsAvailableWithoutAdvertisement() {
        WearTakBleClient.DiscoveredDevice d = scan(null, "Galaxy Watch Ultra2 (RK2Z)", ALIAS, true, null);
        assertEquals("Galaxy Watch Ultra2 (RK2Z)", d.name);
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
        Resources resources = identityResources();
        when(name.getResources()).thenReturn(resources);
        when(alias.getResources()).thenReturn(resources);
        when(resources.getString(R.string.watch_name_unavailable)).thenReturn("Unknown watch");
        when(resources.getString(R.string.watch_bonded_as, ALIAS)).thenReturn("Bonded as " + ALIAS);

        BleDeviceAdapter.bindNames(name, alias, scan(ORIGINAL, ALIAS, ALIAS, true, null));
        verify(name).setText(expectedIdentity("Unavailable", "Unavailable", ADDRESS, "-56 dBm",
                "Unavailable", ALIAS));
        verify(alias).setText("");
        verify(alias).setVisibility(View.GONE);
        clearInvocations(name, alias);

        BleDeviceAdapter.bindNames(name, alias, scan(null, null, null, true, null));
        verify(name).setText(expectedIdentity("Unavailable", "Unavailable", ADDRESS, "-56 dBm",
                "Unavailable", "Unavailable"));
        verify(alias).setText("");
        verify(alias).setVisibility(View.GONE);
    }

    @Test public void observedCallsignIsBoundToReadyPeerAndGeneration() throws Exception {
        WearTakBleClient client = readyClient();
        WearTakBleClient.DiscoveredDevice device = scan("Galaxy Watch Ultra2 (RK2Z)", ALIAS, ALIAS, true, null);
        assertNull(client.withSessionIdentity(device).callsign);
        receive(client, "{\"msgType\":\"settings_changed\",\"payload\":{\"callsign\":\"ODIN-WEARTAK\"}}", 7);
        WearTakBleClient.DiscoveredDevice observed = client.withSessionIdentity(device);
        assertEquals("ODIN-WEARTAK", observed.primaryName());
        assertEquals("Galaxy Watch Ultra2 (RK2Z)", observed.secondaryName());
        assertEquals(ALIAS, observed.distinctBondedName());
        WearTakBleClient.DiscoveredDevice other =
                new WearTakBleClient.DiscoveredDevice("Other", "00:00:00:00:00:02", -45);
        assertNull(client.withSessionIdentity(other).callsign);

        set(client, "sessionGeneration", 8L);
        assertNull(client.withSessionIdentity(observed).callsign);
        receive(client, "{\"msgType\":\"settings_changed\",\"payload\":{\"callsign\":\"STALE\"}}", 7);
        assertNull(client.withSessionIdentity(device).callsign);
        receive(client, "{\"msgType\":\"watch_info\",\"payload\":{\"cs\":\"NEW\"}}", 8);
        assertEquals("NEW", client.withSessionIdentity(device).callsign);
        set(client, "connected", false);
        assertNull(client.withSessionIdentity(device).callsign);
    }

    @Test public void threeFieldBindingAndRecyclingOmitDuplicatesAndMissingFields() throws Exception {
        WearTakBleClient client = readyClient();
        receive(client, "{\"msgType\":\"watch_info\",\"payload\":{\"cs\":\"ODIN-WEARTAK\"}}", 7);
        TextView name = mock(TextView.class);
        TextView details = mock(TextView.class);
        Resources resources = identityResources();
        when(name.getResources()).thenReturn(resources);
        when(details.getResources()).thenReturn(resources);
        when(resources.getString(R.string.watch_bonded_as, ALIAS)).thenReturn("Bonded as " + ALIAS);
        BleDeviceAdapter.bindNames(name, details, client.withSessionIdentity(
                scan("Galaxy Watch Ultra2 (RK2Z)", ALIAS, ALIAS, true, null)));
        verify(name).setText(expectedIdentity("Unavailable", "Unavailable", ADDRESS, "-56 dBm",
                "Unavailable", ALIAS));
        verify(details).setText("");
        verify(details).setVisibility(View.GONE);
        clearInvocations(name, details);
        BleDeviceAdapter.bindNames(name, details, client.withSessionIdentity(
                scan("ODIN-WEARTAK", "ODIN-WEARTAK", "odin-weartak", true, null)));
        verify(name).setText(expectedIdentity("Unavailable", "Unavailable", ADDRESS, "-56 dBm",
                "Unavailable", "ODIN-WEARTAK"));
        verify(details).setText("");
        verify(details).setVisibility(View.GONE);
        assertNull(client.withSessionIdentity(scan(null, ALIAS, ALIAS, false, null)).distinctBondedName());
        assertNull(client.withSessionIdentity(scan(null, ALIAS, ALIAS, true, null)).secondaryName());
    }

    @Test public void sparseIdentityMessagesRetainCallsignAndExplicitEmptyClearsIt() throws Exception {
        WearTakBleClient client = readyClient();
        WearTakBleClient.DiscoveredDevice d = scan(ORIGINAL, ALIAS, ALIAS, true, null);
        receive(client, "{\"msgType\":\"settings_request\",\"payload\":{\"callsign\":\"ODIN\"}}", 7);
        receive(client, "{\"msgType\":\"settings_changed\",\"payload\":{\"team\":\"Blue\"}}", 7);
        assertEquals("ODIN", client.withSessionIdentity(d).callsign);
        receive(client, "{\"msgType\":\"settings_changed\",\"payload\":{\"callsign\":\"\"}}", 7);
        assertNull(client.withSessionIdentity(d).callsign);
        assertEquals(ORIGINAL, client.withSessionIdentity(d).primaryName());
    }

    @Test public void serviceDataDecodesOnlyVersionOneWithinTwelveBytes() {
        assertEquals("ODIN", WearTakBleClient.parseAdvertisedCallsign(payload("ODIN")));
        assertEquals("ABCD12345678", WearTakBleClient.parseAdvertisedCallsign(payload("ABCD12345678")));
        assertEquals("ODIN", WearTakBleClient.parseAdvertisedCallsign(payload(" ODIN ")));
        assertNull(WearTakBleClient.parseAdvertisedCallsign(null));
        assertNull(WearTakBleClient.parseAdvertisedCallsign(new byte[0]));
        assertNull(WearTakBleClient.parseAdvertisedCallsign(new byte[] {1}));
        assertNull(WearTakBleClient.parseAdvertisedCallsign(payload("   ")));
        assertNull(WearTakBleClient.parseAdvertisedCallsign(payload("\u2003\u00a0")));
        assertNull(WearTakBleClient.parseAdvertisedCallsign(payload("1234567890123")));
        for (byte version : new byte[] {0, 2, (byte) 255}) {
            byte[] data = payload("ODIN");
            data[0] = version;
            assertNull(WearTakBleClient.parseAdvertisedCallsign(data));
        }
    }

    @Test public void completeMultibyteTruncationIsAcceptedButSplitCodePointsAreRejected() {
        assertEquals("12345678\uD83D\uDE80",
                WearTakBleClient.parseAdvertisedCallsign(payload("12345678\uD83D\uDE80")));
        assertEquals("\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9",
                WearTakBleClient.parseAdvertisedCallsign(payload("\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9")));
        byte[] full = payload("123456789\uD83D\uDE80");
        assertNull(WearTakBleClient.parseAdvertisedCallsign(java.util.Arrays.copyOf(full, 13)));
        for (byte[] malformed : new byte[][] {
                {1, (byte) 0x80}, {1, (byte) 0xc0, (byte) 0xaf},
                {1, (byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {1, (byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                {1, (byte) 0xe2, (byte) 0x82}
        }) assertNull(WearTakBleClient.parseAdvertisedCallsign(malformed));
    }

    @Test public void serviceDataOnlyResponseSurvivesPrimaryPacketsAndNewScansAndUpdates() throws Exception {
        WearTakBleClient client = scanningClient();
        List<WearTakBleClient.DiscoveredDevice> found = new ArrayList<>();
        WearTakBleClient.ScanListener listener = collecting(found);
        startScan(client, listener);
        ScanCallback callback = (ScanCallback) get(client, "activeScanCallback");
        ScanResult response = result(null, payload("ALPHA"), false);
        callback.onScanResult(0, response);
        assertEquals("ALPHA", found.get(0).primaryName());
        assertEquals(ADDRESS, found.get(0).address);
        assertEquals(ALIAS, found.get(0).distinctBondedName());
        callback.onScanResult(0, result(null));
        assertEquals("ALPHA", found.get(1).primaryName());
        WearTakBleClient.DiscoveredDevice selected = found.get(0);

        startScan(client, listener);
        ScanCallback current = (ScanCallback) get(client, "activeScanCallback");
        callback.onScanResult(0, result(null, payload("STALE"), false));
        assertEquals(2, found.size());
        current.onScanResult(0, result(null));
        assertEquals("ALPHA", found.get(2).primaryName());
        current.onScanResult(0, result(null, payload("BRAVO"), true));
        assertEquals("BRAVO", found.get(3).primaryName());
        assertEquals("BRAVO", client.withSessionIdentity(selected).primaryName());

        assertEquals("BRAVO", client.withSessionIdentity(scan(null, ALIAS, ALIAS, true, null)).primaryName());
        Context context = (Context) get(client, "baseContext");
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(context.getSharedPreferences("wtk_pairing", Context.MODE_PRIVATE)).thenReturn(prefs);
        when(prefs.getString("preferred_address", null)).thenReturn(ADDRESS);
        BluetoothAdapter adapter = (BluetoothAdapter) get(client, "bluetoothAdapter");
        BluetoothDevice bonded = response.getDevice();
        when(adapter.getRemoteDevice(ADDRESS)).thenReturn(bonded);
        assertEquals("BRAVO", client.getKnownBondedCompanion().primaryName());
        WearTakBleClient.BondedCompanionListener bondedListener =
                mock(WearTakBleClient.BondedCompanionListener.class);
        client.findBondedCompanion(bondedListener);
        verify(bondedListener).onFound(argThat(device ->
                "BRAVO".equals(device.primaryName()) && ADDRESS.equals(device.address)));
        client.resetSession("test reset");
        assertEquals("BRAVO", client.getKnownBondedCompanion().primaryName());
    }

    @Test public void invalidResponseClearsCachedCallsignAndFallsBackWithoutCrossAddressLeak() throws Exception {
        WearTakBleClient client = scanningClient();
        List<WearTakBleClient.DiscoveredDevice> found = new ArrayList<>();
        startScan(client, collecting(found));
        ScanCallback callback = (ScanCallback) get(client, "activeScanCallback");
        callback.onScanResult(0, result(null, payload("ALPHA"), false));
        WearTakBleClient.DiscoveredDevice selected = found.get(0);
        for (byte[] invalid : new byte[][] {{2, 65}, {1, (byte) 0xff}, {1}, {}}) {
            callback.onScanResult(0, result(null, invalid, false));
            assertEquals(ALIAS, found.get(found.size() - 1).primaryName());
            assertEquals(ALIAS, client.withSessionIdentity(selected).primaryName());
        }
        ScanResult unnamed = result(null, payload(" "), true);
        when(unnamed.getDevice().getName()).thenReturn(null);
        callback.onScanResult(0, unnamed);
        assertEquals(ADDRESS, found.get(found.size() - 1).primaryName());

        callback.onScanResult(0, result(null, payload("ALPHA"), false));
        ScanResult other = result(null);
        when(other.getDevice().getAddress()).thenReturn("00:00:00:00:00:02");
        callback.onScanResult(0, other);
        assertEquals(ALIAS, found.get(found.size() - 1).primaryName());
        int count = found.size();
        callback.onScanResult(0, result(null, null, false));
        assertEquals(count, found.size());
    }

    @Test public void advertisedIdentityBindsPrimaryNameAndSessionCallsignStillWins() throws Exception {
        WearTakBleClient client = readyClient();
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> identities =
                (java.util.Map<String, String>) get(client, "advertisedCallsigns");
        identities.put(ADDRESS, "SCAN");
        WearTakBleClient.DiscoveredDevice device = scan(ORIGINAL, ALIAS, ALIAS, true, null);
        TextView name = mock(TextView.class);
        TextView details = mock(TextView.class);
        Resources resources = identityResources();
        when(name.getResources()).thenReturn(resources);
        when(details.getResources()).thenReturn(resources);
        when(resources.getString(R.string.watch_bonded_as, ALIAS)).thenReturn("Bonded as " + ALIAS);
        BleDeviceAdapter.bindNames(name, details, client.withSessionIdentity(device));
        verify(name).setText(expectedIdentity("Unavailable", "Unavailable", ADDRESS, "-56 dBm",
                "SCAN", ALIAS));
        verify(details).setText("");
        receive(client, "{\"msgType\":\"watch_info\",\"payload\":{\"cs\":\"FULL-CALLSIGN\"}}", 7);
        assertEquals("FULL-CALLSIGN", client.withSessionIdentity(device).primaryName());
        set(client, "connected", false);
        assertEquals("SCAN", client.withSessionIdentity(device).primaryName());
    }

    @Test public void singleLineIdentityUsesProductAndLocalAtakCallsignNeverSystemAlias() throws Exception {
        WearTakBleClient client = readyClient();
        receive(client, "{\"msgType\":\"watch_info\",\"payload\":{\"cs\":\"WEAROS-6558\"}}", 7);
        String product = "Galaxy Watch Ultra 2 (RK2Z)";
        Resources resources = identityResources();
        when(resources.getString(eq(R.string.watch_identity_bonded), any(), any()))
                .thenAnswer(i -> i.getArgument(1) + " (bonded as " + i.getArgument(2) + ")");
        WearTakBleClient.DiscoveredDevice bonded = client.withSessionIdentity(
                scan(null, product, "SYSTEM-ALIAS", true, null));
        assertEquals("WEAROS-6558, " + product + " (bonded as ODIN-ATAK-S9FE)",
                BleDeviceAdapter.watchLabel(resources, bonded, "ODIN-ATAK-S9FE"));
        assertEquals("WEAROS-6558, " + product,
                BleDeviceAdapter.watchLabel(resources, bonded, " "));
        WearTakBleClient.DiscoveredDevice unbonded = client.withSessionIdentity(
                scan(null, product, "SYSTEM-ALIAS", false, null));
        assertEquals("WEAROS-6558, " + product,
                BleDeviceAdapter.watchLabel(resources, unbonded, "ODIN-ATAK-S9FE"));
        assertEquals("WEAROS-6558 (bonded as ODIN)",
                BleDeviceAdapter.watchLabel(resources, client.withSessionIdentity(
                        scan(null, null, null, true, null)), "ODIN"));
        assertEquals(product + " (bonded as ODIN)",
                BleDeviceAdapter.watchLabel(resources, scan(null, product, "ALIAS", true, null), "ODIN"));
        assertEquals(ADDRESS, BleDeviceAdapter.watchLabel(resources,
                scan(null, null, null, false, null), "ODIN"));
        assertEquals("PAIRED, " + product + " (bonded as ODIN)",
                BleDeviceAdapter.watchLabel(resources, bonded, "ODIN", "PAIRED"));
        assertEquals("WEAROS-6558", BleDeviceAdapter.watchLabel(resources,
                client.withSessionIdentity(scan(null, "WEAROS-6558", null, false, null)), null));
        WearTakBleClient.DiscoveredDevice sparse = scan(null, null, null, true,
                scan(null, product, "ALIAS", true, null));
        assertEquals(product, sparse.productName);
        assertTrue(sparse.bonded);
        assertFalse(scan(null, product, "ALIAS", false, sparse).bonded);
        assertNull(WearTakBleClient.DiscoveredDevice.fromScan(null, null, null, false,
                "00:00:00:00:00:02", 0, sparse).productName);
    }

    @Test public void rowBindingReadsCurrentHostCallsignAndClearsSuffixOnUnbondedReuse() throws Exception {
        com.atakmap.android.maps.MapView.setTestContext(mock(Context.class));
        try {
            com.atakmap.android.maps.MapView.setTestCallsign("ODIN-ATAK-S9FE");
            TextView name = mock(TextView.class);
            TextView details = mock(TextView.class);
            Resources resources = identityResources();
            when(name.getResources()).thenReturn(resources);
            when(resources.getString(eq(R.string.watch_identity_bonded), any(), any()))
                    .thenAnswer(i -> i.getArgument(1) + " (bonded as " + i.getArgument(2) + ")");
            WearTakBleClient client = readyClient();
            receive(client, "{\"msgType\":\"watch_info\",\"payload\":{\"cs\":\"WEAROS-6558\"}}", 7);
            BleDeviceAdapter.bindNames(name, details, client.withSessionIdentity(
                    scan(null, "Galaxy Watch Ultra 2 (RK2Z)", "WRONG-ALIAS", true, null)), "CONNECTED");
            verify(name).setText(expectedIdentity("ODIN-ATAK-S9FE", "CONNECTED", ADDRESS, "-56 dBm",
                    "Unavailable", "Galaxy Watch Ultra 2 (RK2Z)"));
            com.atakmap.android.maps.MapView.setTestCallsign("NEW-EUD");
            clearInvocations(name);
            BleDeviceAdapter.bindNames(name, details, client.withSessionIdentity(
                    scan(null, null, null, true, null)), "CONNECTED - SYNCING");
            verify(name).setText(expectedIdentity("NEW-EUD", "CONNECTED - SYNCING", ADDRESS, "-56 dBm",
                    "Unavailable", "Unavailable"));
            clearInvocations(name);
            BleDeviceAdapter.bindNames(name, details, client.withSessionIdentity(
                    scan(null, null, "WRONG-ALIAS", false, null)), "NOT CONNECTED");
            verify(name).setText(expectedIdentity("NEW-EUD", "NOT CONNECTED", ADDRESS, "-56 dBm",
                    "Unavailable", "Unavailable"));
            verify(details, times(3)).setVisibility(View.GONE);
        } finally {
            com.atakmap.android.maps.MapView.setTestContext(null);
        }
    }

    private static Resources identityResources() {
        Resources resources = mock(Resources.class);
        when(resources.getString(R.string.watch_identity_unavailable)).thenReturn("Unavailable");
        when(resources.getString(eq(R.string.watch_identity_dbm), any()))
                .thenAnswer(i -> i.getArgument(1) + " dBm");
        when(resources.getString(eq(R.string.watch_identity_lines),
                any(), any(), any(), any(), any(), any())).thenAnswer(i -> expectedIdentity(
                        i.getArgument(1), i.getArgument(2), i.getArgument(3),
                        i.getArgument(4), i.getArgument(5), i.getArgument(6)));
        return resources;
    }

    private static String expectedIdentity(String local, String status, String address,
                                           String strength, String watch, String product) {
        return "ATAK Callsign: " + local + " (" + status + ")\nBluetooth Address: " + address
                + "\nBluetooth Strength: " + strength + "\nWearTAK Callsign: " + watch
                + "\nWearTAK Product Name: " + product;
    }

    @Test public void fiveAlphabeticalLabelsNeverSubstituteProductOrAliasForMissingCallsign() {
        Resources resources = identityResources();
        WearTakBleClient.DiscoveredDevice device = WearTakBleClient.DiscoveredDevice.fromScan(
                "OLD-ADVERTISEMENT", "Galaxy Watch", "WRONG-ALIAS", true, ADDRESS, -52, null);
        assertEquals(expectedIdentity("ODIN-ATAK", "Bonded", ADDRESS, "-52 dBm",
                "Unavailable", "Galaxy Watch"),
                BleDeviceAdapter.identityLines(resources, device, "ODIN-ATAK", "Bonded"));
        assertEquals(expectedIdentity("Unavailable", "Disconnected", "Unavailable", "Unavailable",
                "Unavailable", "Unavailable"),
                BleDeviceAdapter.identityLines(resources,
                        new WearTakBleClient.DiscoveredDevice(null, null, 0), null, "Disconnected"));
    }

    @Test public void standaloneScanCallsignSurvivesManagedSessionIdentityAndDisconnect() throws Exception {
        WearTakBleClient client = scanningClient();
        List<WearTakBleClient.DiscoveredDevice> found = new ArrayList<>();
        startScan(client, collecting(found));
        ((ScanCallback) get(client, "activeScanCallback")).onScanResult(
                0, result(null, payload("WEAROS-6558"), false));
        WearTakBleClient.DiscoveredDevice selected = found.get(0);
        BluetoothDevice peer = mock(BluetoothDevice.class);
        when(peer.getAddress()).thenReturn(ADDRESS);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        when(gatt.getDevice()).thenReturn(peer);
        set(client, "bluetoothGatt", gatt);
        set(client, "connected", true);
        set(client, "servicesReady", true);
        set(client, "sessionGeneration", 7L);
        Resources resources = identityResources();
        String expected = expectedIdentity("ODIN-ATAK", "CONNECTED", ADDRESS, "-56 dBm",
                "WEAROS-6558", ALIAS);
        for (String message : new String[] {
                "{\"msgType\":\"watch_info\",\"payload\":{\"cs\":\"ODIN-ATAK-S9FE\"}}",
                "{\"msgType\":\"settings_request\",\"payload\":{\"callsign\":\"ODIN-ATAK-S9FE\"}}",
                "{\"msgType\":\"settings_changed\",\"payload\":{\"callsign\":\"ODIN-ATAK-S9FE\"}}"
        }) {
            receive(client, message, 7);
            assertEquals(expected, BleDeviceAdapter.identityLines(resources,
                    client.withSessionIdentity(selected), "ODIN-ATAK", "CONNECTED"));
        }
        set(client, "connected", false);
        assertEquals(expectedIdentity("ODIN-ATAK", "NOT CONNECTED", ADDRESS, "-56 dBm",
                "WEAROS-6558", ALIAS), BleDeviceAdapter.identityLines(resources,
                client.withSessionIdentity(selected), "ODIN-ATAK", "NOT CONNECTED"));
        set(client, "sessionGeneration", 8L);
        assertEquals("WEAROS-6558", client.withSessionIdentity(selected).advertisedCallsign);
    }

    @Test public void explicitStandaloneIdentityWinsAndRemainsAddressBoundAfterDisconnect() throws Exception {
        WearTakBleClient client = readyClient();
        WearTakBleClient.DiscoveredDevice selected = scan(null, "Galaxy Watch", ALIAS, true, null);
        Resources resources = identityResources();
        receive(client, "{\"msgType\":\"watch_info\",\"payload\":"
                + "{\"cs\":\"ODIN-ATAK\",\"standaloneCallsign\":\"ODIN-WEARTAK\"}}", 7);
        assertEquals(expectedIdentity("ODIN-ATAK", "CONNECTED", ADDRESS, "-56 dBm",
                "ODIN-WEARTAK", "Galaxy Watch"), BleDeviceAdapter.identityLines(
                resources, client.withSessionIdentity(selected), "ODIN-ATAK", "CONNECTED"));
        receive(client, "{\"msgType\":\"settings_changed\",\"payload\":{\"callsign\":\"MANAGED\"}}", 7);
        assertEquals("ODIN-WEARTAK", client.withSessionIdentity(selected).standaloneCallsign);
        for (String invalid : new String[] {"null", "42", "true", "{}", "[]"}) {
            receive(client, "{\"msgType\":\"settings_changed\",\"payload\":"
                    + "{\"standaloneCallsign\":" + invalid + "}}", 7);
            assertEquals("ODIN-WEARTAK", client.withSessionIdentity(selected).standaloneCallsign);
        }
        receive(client, "{\"msgType\":\"settings_changed\",\"payload\":"
                + "{\"standaloneCallsign\":\"UPDATED\"}}", 7);
        assertEquals("UPDATED", client.withSessionIdentity(selected).standaloneCallsign);
        receive(client, "{\"msgType\":\"settings_request\",\"payload\":"
                + "{\"standaloneCallsign\":\" RESTORED \"}}", 7);
        WearTakBleClient.DiscoveredDevice observed = client.withSessionIdentity(selected);
        assertEquals("RESTORED", observed.standaloneCallsign);
        WearTakBleClient.DiscoveredDevice other = new WearTakBleClient.DiscoveredDevice(
                "Other", "00:00:00:00:00:02", 0);
        assertNull(client.withSessionIdentity(other).standaloneCallsign);
        set(client, "connected", false);
        receive(client, "{\"msgType\":\"settings_changed\",\"payload\":"
                + "{\"standaloneCallsign\":\"NOT-READY\"}}", 7);
        assertEquals("RESTORED", client.withSessionIdentity(selected).standaloneCallsign);
        set(client, "connected", true);
        set(client, "sessionGeneration", 8L);
        receive(client, "{\"msgType\":\"watch_info\",\"payload\":"
                + "{\"standaloneCallsign\":\"STALE\"}}", 7);
        assertEquals("RESTORED", client.withSessionIdentity(observed).standaloneCallsign);
        client.resetSession("standalone identity retained");
        assertEquals("RESTORED", client.withSessionIdentity(selected).standaloneCallsign);
    }

    @Test public void blankStandaloneFallsBackToAdvertisementNeverEffectiveSessionName() throws Exception {
        WearTakBleClient client = readyClient();
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> advertisements =
                (java.util.Map<String, String>) get(client, "advertisedCallsigns");
        advertisements.put(ADDRESS, "SCAN-WATCH");
        Resources resources = identityResources();
        WearTakBleClient.DiscoveredDevice selected = scan(null, "Galaxy Watch", ALIAS, true, null);
        receive(client, "{\"msgType\":\"watch_info\",\"payload\":"
                + "{\"cs\":\"MANAGED\",\"standaloneCallsign\":\"FULL-STANDALONE\"}}", 7);
        assertEquals(expectedIdentity("EUD", "CONNECTED", ADDRESS, "-56 dBm",
                "FULL-STANDALONE", "Galaxy Watch"), BleDeviceAdapter.identityLines(
                resources, client.withSessionIdentity(selected), "EUD", "CONNECTED"));
        receive(client, "{\"msgType\":\"settings_changed\",\"payload\":{\"standaloneCallsign\":\" \"}}", 7);
        assertEquals(expectedIdentity("EUD", "CONNECTED", ADDRESS, "-56 dBm",
                "SCAN-WATCH", "Galaxy Watch"), BleDeviceAdapter.identityLines(
                resources, client.withSessionIdentity(selected), "EUD", "CONNECTED"));
        advertisements.clear();
        assertEquals(expectedIdentity("EUD", "CONNECTED", ADDRESS, "-56 dBm",
                "Unavailable", "Galaxy Watch"), BleDeviceAdapter.identityLines(
                resources, client.withSessionIdentity(selected), "EUD", "CONNECTED"));
    }

    @Test public void selectedAndRestoredRowsRefreshProductAndBondWithoutUsingAlias() throws Exception {
        WearTakBleClient client = scanningClient();
        BluetoothAdapter adapter = (BluetoothAdapter) get(client, "bluetoothAdapter");
        BluetoothDevice peer = mock(BluetoothDevice.class);
        when(adapter.getRemoteDevice(ADDRESS)).thenReturn(peer);
        when(peer.getName()).thenReturn("Galaxy Watch Ultra 2 (RK2Z)");
        when(peer.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        WearTakBleClient.DiscoveredDevice restored = new WearTakBleClient.DiscoveredDevice(
                "Saved name", ADDRESS, 0);
        WearTakBleClient.DiscoveredDevice observed = client.withSessionIdentity(restored);
        assertTrue(observed.bonded);
        assertEquals("Galaxy Watch Ultra 2 (RK2Z)", observed.productName);
        when(peer.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        assertFalse(client.withSessionIdentity(observed).bonded);
    }

    private static byte[] payload(String callsign) {
        byte[] text = callsign.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[text.length + 1];
        data[0] = 1;
        System.arraycopy(text, 0, data, 1, text.length);
        return data;
    }

    private static WearTakBleClient.ScanListener collecting(
            List<WearTakBleClient.DiscoveredDevice> found) {
        return new WearTakBleClient.ScanListener() {
            @Override public void onDeviceFound(WearTakBleClient.DiscoveredDevice device) { found.add(device); }
            @Override public void onScanFinished(List<WearTakBleClient.DiscoveredDevice> devices) { }
            @Override public void onScanError(String msg) { fail(msg); }
        };
    }

    private static WearTakBleClient readyClient() throws Exception {
        WearTakBleClient client = new WearTakBleClient(mock(Context.class), connected -> { });
        BluetoothDevice peer = mock(BluetoothDevice.class);
        when(peer.getAddress()).thenReturn(ADDRESS);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        when(gatt.getDevice()).thenReturn(peer);
        set(client, "bluetoothGatt", gatt);
        set(client, "connected", true);
        set(client, "servicesReady", true);
        set(client, "started", true);
        set(client, "sessionGeneration", 7L);
        return client;
    }

    @Test public void sameAddressRefreshesInPlaceButSharedCallsignsRemainSeparate() throws Exception {
        WearTakBleClient client = scanningClient();
        startScan(client, collecting(new ArrayList<>()));
        ScanCallback callback = (ScanCallback) get(client, "activeScanCallback");
        callback.onScanResult(0, result(null, payload("SHARED"), false));
        lastSeen(client).put(ADDRESS, 0L);
        assertTrue(client.expireScanDevices(WearTakBleClient.SCAN_ENTRY_TTL_MS - 1).isEmpty());
        ScanResult updated = result(null, payload("SHARED"), false);
        when(updated.getRssi()).thenReturn(-31);
        callback.onScanResult(0, updated);
        assertEquals(1, discoveries(client).size());
        assertEquals(-31, discoveries(client).get(ADDRESS).rssi);
        lastSeen(client).put(ADDRESS, 5_000L);
        ScanResult rotated = result(null, payload("SHARED"), false);
        String newAddress = "00:00:00:00:00:02";
        when(rotated.getDevice().getAddress()).thenReturn(newAddress);
        callback.onScanResult(0, rotated);
        lastSeen(client).put(newAddress, 10_000L);
        assertEquals(2, discoveries(client).size());
        assertEquals("SHARED", discoveries(client).get(ADDRESS).primaryName());
        assertEquals("SHARED", discoveries(client).get(newAddress).primaryName());
        WearTakBleClient.DiscoveredDevice selected = discoveries(client).get(ADDRESS);
        set(client, "pendingBondDevice", selected);
        assertEquals(Collections.singletonList(ADDRESS), client.expireScanDevices(15_000));
        assertSame(selected, get(client, "pendingBondDevice"));
        assertEquals(Collections.singleton(newAddress), discoveries(client).keySet());
        assertTrue(client.expireScanDevices(15_000).isEmpty());
        assertEquals(Collections.singletonList(newAddress), client.expireScanDevices(20_000));
        callback.onScanResult(0, result(null));
        assertEquals("SHARED", discoveries(client).get(ADDRESS).primaryName());
    }

    @Test public void expiryTimerRemovesSilentPeersAndIsCancelledAndFencedAcrossScans() throws Exception {
        WearTakBleClient client = scanningClient();
        WearTakBleClient.ScanListener listener = mock(WearTakBleClient.ScanListener.class);
        client.scanForDevices(listener);
        ScanCallback callback = (ScanCallback) get(client, "activeScanCallback");
        callback.onScanResult(0, result(null, payload("ALPHA"), false));
        Runnable expiry = (Runnable) get(client, "scanExpiryRunnable");
        assertNotNull(expiry);
        lastSeen(client).put(ADDRESS, -WearTakBleClient.SCAN_ENTRY_TTL_MS);
        expiry.run();
        verify(listener).onDevicesExpired(Collections.singletonList(ADDRESS));
        assertTrue(discoveries(client).isEmpty());
        expiry.run();
        verify(listener, times(1)).onDevicesExpired(anyList());
        Handler main = (Handler) get(client, "main");
        verify(main, atLeast(2)).postDelayed(eq(expiry), eq(1_000L));

        client.scanForDevices(listener);
        verify(main).removeCallbacks(expiry);
        ScanCallback next = (ScanCallback) get(client, "activeScanCallback");
        next.onScanResult(0, result(null));
        lastSeen(client).put(ADDRESS, -WearTakBleClient.SCAN_ENTRY_TTL_MS);
        expiry.run();
        assertEquals(1, discoveries(client).size());
        callback.onScanResult(0, result("STALE"));
        assertNull(discoveries(client).get(ADDRESS).name);

        Runnable current = (Runnable) get(client, "scanExpiryRunnable");
        client.resetSession("cancel scan");
        verify(main).removeCallbacks(current);
        assertNull(get(client, "scanExpiryRunnable"));
        current.run();
        verify(listener, times(1)).onDevicesExpired(anyList());
        client.scanForDevices(listener);
        current = (Runnable) get(client, "scanExpiryRunnable");
        ((ScanCallback) get(client, "activeScanCallback")).onScanFailed(3);
        verify(main).removeCallbacks(current);
        verify(listener).onScanError("Scan failed: 3");
        assertNull(get(client, "scanExpiryRunnable"));
        current.run();
        verify(listener, times(1)).onDevicesExpired(anyList());
    }

    @Test public void finalScanSnapshotPrunesExpiredPeersWithoutWaitingForNextTimerTick() throws Exception {
        WearTakBleClient client = scanningClient();
        WearTakBleClient.ScanListener listener = mock(WearTakBleClient.ScanListener.class);
        client.scanForDevices(listener);
        ScanCallback callback = (ScanCallback) get(client, "activeScanCallback");
        callback.onScanResult(0, result(null, payload("ALPHA"), false));
        lastSeen(client).put(ADDRESS, -WearTakBleClient.SCAN_ENTRY_TTL_MS);
        Runnable timeout = (Runnable) get(client, "scanTimeoutRunnable");
        timeout.run();
        verify(listener).onScanFinished(Collections.emptyList());
        assertTrue(discoveries(client).isEmpty());
        assertNull(get(client, "scanExpiryRunnable"));
        callback.onScanResult(0, result(null));
        assertTrue(discoveries(client).isEmpty());
        client.scanForDevices(listener);
        assertTrue(lastSeen(client).isEmpty());
        callback = (ScanCallback) get(client, "activeScanCallback");
        callback.onScanResult(0, result(null));
        assertEquals("ALPHA", discoveries(client).get(ADDRESS).primaryName());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Long> lastSeen(WearTakBleClient client) throws Exception {
        return (java.util.Map<String, Long>) get(client, "scanLastSeen");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, WearTakBleClient.DiscoveredDevice> discoveries(
            WearTakBleClient client) throws Exception {
        return (java.util.Map<String, WearTakBleClient.DiscoveredDevice>) get(client, "discovered");
    }

    private static void receive(WearTakBleClient client, String json, long session) throws Exception {
        java.lang.reflect.Method method = WearTakBleClient.class.getDeclaredMethod(
                "deliverJsonLine", String.class, long.class);
        method.setAccessible(true);
        method.invoke(client, json, session);
    }

    @Test public void actualScanCallbackUsesAdvertisementAndKeepsSessionFences() throws Exception {
        WearTakBleClient client = scanningClient();
        List<WearTakBleClient.DiscoveredDevice> found = new ArrayList<>();
        WearTakBleClient.ScanListener listener = collecting(found);
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
        assertEquals(ALIAS, found.get(2).primaryName());
        assertNull(found.get(2).distinctBondedName());
    }

    private static WearTakBleClient scanningClient() throws Exception {
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
        BluetoothLeScanner scanner = mock(BluetoothLeScanner.class, withSettings().mockMaker(MockMakers.INLINE));
        when(adapter.getBluetoothLeScanner()).thenReturn(scanner);
        set(client, "appContext", context);
        set(client, "main", main);
        set(client, "bluetoothAdapter", adapter);
        set(client, "started", true);
        return client;
    }

    private static void startScan(WearTakBleClient client, WearTakBleClient.ScanListener listener)
            throws Exception {
        set(client, "scanning", true);
        set(client, "activeScanCallback", client.createScanCallback(listener));
        ((java.util.Map<?, ?>) get(client, "discovered")).clear();
        lastSeen(client).clear();
    }

    private static ScanResult result(String advertisedName) {
        return result(advertisedName, null, true);
    }

    private static ScanResult result(String advertisedName, byte[] data, boolean hasService) {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(device.getAddress()).thenReturn(ADDRESS);
        when(device.getName()).thenReturn(ALIAS);
        when(device.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        ScanRecord record = mock(ScanRecord.class, withSettings().mockMaker(MockMakers.INLINE));
        when(record.getDeviceName()).thenReturn(advertisedName);
        ParcelUuid service = mock(ParcelUuid.class);
        when(service.getUuid()).thenReturn(UUID.fromString("0000a11a-0000-1000-8000-00805f9b34fb"));
        when(record.getServiceUuids()).thenReturn(hasService ? Collections.singletonList(service) : null);
        when(record.getServiceData(any(ParcelUuid.class))).thenReturn(data);
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
