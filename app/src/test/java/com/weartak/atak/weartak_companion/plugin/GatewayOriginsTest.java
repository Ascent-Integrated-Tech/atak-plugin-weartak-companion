package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import static org.junit.Assert.*;

public class GatewayOriginsTest {
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
}
