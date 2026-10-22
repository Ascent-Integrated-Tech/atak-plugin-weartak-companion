package com.weartak.atak.weartak_companion.plugin;

import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class GatewayOriginsTest {
    private SharedPreferences preferences(AtomicReference<String> stored) {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    if ("putString".equals(method.getName())) {
                        stored.set((String) args[1]);
                        return proxy;
                    }
                    if ("commit".equals(method.getName())) return true;
                    return null;
                });
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName())) return stored.get();
                    if ("edit".equals(method.getName())) return editor;
                    return null;
                });
    }

    @Test public void watchUidCannotBecomeNetworkEchoOrBeClaimedByDifferentPeer() {
        GatewayOrigins origins = new GatewayOrigins();
        origins.claim("position", "watch1");
        origins.claim("marker", "watch1");
        assertTrue(origins.contains("position"));
        assertTrue(origins.contains("marker"));
        origins.claim("marker", "watch1");
        assertThrows(IllegalArgumentException.class, () -> origins.claim("marker", "watch2"));
        assertFalse(origins.contains("network-only"));
    }
    @Test public void ledgerFailsClosedInsteadOfEvictingEchoProtectionAndStopClearsIt() {
        GatewayOrigins origins = new GatewayOrigins();
        for (int i = 0; i < 2048; i++) origins.claim("uid" + i, "watch");
        assertThrows(IllegalStateException.class, () -> origins.claim("overflow", "watch"));
        assertTrue(origins.contains("uid0"));
        origins.clear();
        assertFalse(origins.contains("uid0"));
        origins.claim("overflow", "other-session");
    }
    @Test public void phoneEmergencyRevisionsRemainAllowedButWatchUidCollisionsFailClosed() {
        GatewayOrigins origins = new GatewayOrigins();
        String phoneAlertUid = "phone-alert";
        String[] types = {"b-a-o", "b-a-o", "b-a-o-can"};
        for (int revision = 0; revision < types.length; revision++) {
            String time = "2026-10-22T13:0" + revision + ":00Z";
            String xml = "<event version='2.0' uid='" + phoneAlertUid + "' type='" + types[revision]
                    + "' time='" + time + "' start='" + time + "'"
                    + " stale='2026-10-22T13:15:00Z' how='h-g-i-g-o'>"
                    + "<point lat='" + revision + "' lon='0' hae='0' ce='1' le='1'/>"
                    + "<detail/></event>";
            assertTrue(GatewayCot.isEmergencyType(
                    GatewayCot.readReplayEvent(xml).type));
            assertTrue(origins.allowsPhoneEvent(phoneAlertUid));
        }
        origins.claim(phoneAlertUid, "watch-address");
        assertFalse(origins.allowsPhoneEvent(phoneAlertUid));
        assertThrows(IllegalArgumentException.class,
                () -> origins.claim(phoneAlertUid, "another-watch"));
    }

    @Test public void persistedPhoneAlertSentinelsAreMigratedWithoutRemovingWatchOwners() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>(
                "{\"legacy-alert\":\"phone-alert\",\"watch-uid\":\"watch-address\"}");
        SharedPreferences preferences = preferences(stored);
        GatewayOrigins origins = new GatewayOrigins(preferences);

        assertTrue(origins.allowsPhoneEvent("legacy-alert"));
        assertFalse(origins.contains("legacy-alert"));
        assertTrue(origins.contains("watch-uid"));
        assertFalse(origins.allowsPhoneEvent("watch-uid"));
        assertFalse(stored.get().contains("legacy-alert"));
        assertTrue(stored.get().contains("watch-uid"));
    }
}
