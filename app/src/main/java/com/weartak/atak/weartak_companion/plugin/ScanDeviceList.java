package com.weartak.atak.weartak_companion.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** Rows shown in the Traditional BLE scan list. */
final class ScanDeviceList {
    private ScanDeviceList() { }

    /**
     * Hides the connected watch only while its link is ready, because the connected card then
     * represents it. A connect that failed or was reset before ready emits no disconnected status,
     * so {@code connectedAddress} can still name that watch and must not hide later scan results.
     */
    static List<WearTakBleClient.DiscoveredDevice> visibleRows(
            List<WearTakBleClient.DiscoveredDevice> scanned, String connectedAddress,
            boolean linkReady, Supplier<String> preferredAddress) {
        String connectedRow = linkReady ? connectedAddress : null;
        ArrayList<WearTakBleClient.DiscoveredDevice> copy = new ArrayList<>();
        for (WearTakBleClient.DiscoveredDevice d : scanned) {
            if (d == null || d.address == null) continue;
            if (connectedRow != null && connectedRow.equals(d.address)) continue;
            copy.add(d);
        }

        Collections.sort(copy, (a, b) -> {
            // 1) connected first
            boolean aConn = a.address.equals(connectedAddress);
            boolean bConn = b.address.equals(connectedAddress);
            if (aConn != bConn) return aConn ? -1 : 1;

            // 2) preferred next
            String pref = preferredAddress.get();
            boolean aPref = pref != null && pref.equals(a.address);
            boolean bPref = pref != null && pref.equals(b.address);
            if (aPref != bPref) return aPref ? -1 : 1;

            // 3) name then address (stable)
            String an = a.primaryName() == null ? "" : a.primaryName();
            String bn = b.primaryName() == null ? "" : b.primaryName();
            int c = an.compareToIgnoreCase(bn);
            if (c != 0) return c;
            return a.address.compareToIgnoreCase(b.address);
        });
        return copy;
    }
}
