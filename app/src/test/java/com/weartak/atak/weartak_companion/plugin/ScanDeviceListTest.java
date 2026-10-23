package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ScanDeviceListTest {
    private static final String WATCH = "48:E3:6B:A8:BE:CD";
    private static final WearTakBleClient.DiscoveredDevice SCANNED =
            new WearTakBleClient.DiscoveredDevice("agorsuch-atak", WATCH, -84);

    @Test public void connectThatEndedBeforeReadyDoesNotHideLaterScanResult() {
        // connectToDevice() recorded WATCH; GATT 147 / setup reset produced no disconnected status.
        assertEquals(Collections.singletonList(WATCH),
                addresses(ScanDeviceList.visibleRows(list(SCANNED), WATCH, false, () -> null)));
    }

    @Test public void readyWatchIsShownByConnectedCardNotScanList() {
        assertEquals(Collections.emptyList(),
                addresses(ScanDeviceList.visibleRows(list(SCANNED), WATCH, true, () -> null)));
    }

    @Test public void readyLinkHidesOnlyItsOwnRow() {
        assertEquals(Collections.singletonList(WATCH), addresses(ScanDeviceList.visibleRows(
                list(SCANNED), "AA:BB:CC:DD:EE:01", true, () -> null)));
    }

    @Test public void orderingAndNullFilteringAreUnchanged() {
        WearTakBleClient.DiscoveredDevice b = new WearTakBleClient.DiscoveredDevice("bravo", "00:00:00:00:00:02", -50);
        WearTakBleClient.DiscoveredDevice a = new WearTakBleClient.DiscoveredDevice("alpha", "00:00:00:00:00:03", -50);
        WearTakBleClient.DiscoveredDevice pref = new WearTakBleClient.DiscoveredDevice("zulu", "00:00:00:00:00:04", -50);
        WearTakBleClient.DiscoveredDevice pending = new WearTakBleClient.DiscoveredDevice("yankee", "00:00:00:00:00:05", -50);
        List<WearTakBleClient.DiscoveredDevice> scanned = list(b, null,
                new WearTakBleClient.DiscoveredDevice("noaddr", null, 0), a, pref, pending);

        assertEquals(Arrays.asList(pending.address, pref.address, a.address, b.address), addresses(
                ScanDeviceList.visibleRows(scanned, pending.address, false, () -> pref.address)));
        assertEquals(6, scanned.size());
    }

    private static List<WearTakBleClient.DiscoveredDevice> list(WearTakBleClient.DiscoveredDevice... devices) {
        return new ArrayList<>(Arrays.asList(devices));
    }

    private static List<String> addresses(List<WearTakBleClient.DiscoveredDevice> rows) {
        List<String> out = new ArrayList<>();
        for (WearTakBleClient.DiscoveredDevice d : rows) out.add(d.address);
        return out;
    }
}
