package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.*;

public class GatewayPolicyTest {
    @Test public void peerPairingAndRetryStoresNeverOverwriteEachOtherOrTraditionalData() {
        assertNotEquals(GatewayPolicy.pairingPreferences("a"), GatewayPolicy.pairingPreferences("b"));
        assertNotEquals(GatewayPolicy.retryPreferences("a"), GatewayPolicy.retryPreferences("b"));
        assertNotEquals("wtk_pairing", GatewayPolicy.pairingPreferences("a"));
        assertNotEquals(AutoReconnectPreference.NAME, GatewayPolicy.retryPreferences("a"));
        assertNotEquals(GatewayPolicy.pairingPreferences("a"), GatewayPolicy.retryPreferences("a"));
    }
    @Test public void manualAdmissionIsCappedAndDeduplicated() {
        GatewayPolicy policy = new GatewayPolicy();
        long owner = policy.start();
        long first = policy.admit("a", true, false);
        assertTrue(policy.current(owner, "a", first));
        assertEquals(-1, policy.admit("a", true, false));
        assertEquals(9, GatewayPolicy.CAP);
        for (int i = 1; i < 9; i++) assertTrue(policy.admit("peer" + i, true, false) >= 0);
        assertEquals(-1, policy.admit("tenth", true, false));
        policy.remove("a");
        assertTrue(policy.admit("tenth", true, false) >= 0);
    }

    @Test public void exactSessionNegotiationAndMapEnvelopeArePermittedButNotExtraSettings() {
        assertTrue(GatewayPolicy.allowedOutput(
                "{\"msg_type\":\"gateway_hello\",\"version\":1,\"session\":\"token-1\"}"));
        assertFalse(GatewayPolicy.allowedOutput(
                "{\"msg_type\":\"gateway_hello\",\"version\":2,\"session\":\"token-1\"}"));
        assertFalse(GatewayPolicy.allowedOutput(
                "{\"msg_type\":\"gateway_hello\",\"version\":1,\"session\":\"token-1\",\"settings\":{}}"));
        assertTrue(GatewayPolicy.allowedOutput(
                "{\"msg_type\":\"gateway_cot\",\"session\":\"token-1\",\"cot\":\"<event/>\"}"));
        assertFalse(GatewayPolicy.allowedOutput(
                "{\"msg_type\":\"gateway_cot\",\"session\":\"\",\"cot\":\"<event/>\"}"));
    }

    @Test public void advertisementsNeverAutomaticallyAdmitNewPeers() {
        GatewayPolicy policy = new GatewayPolicy();
        policy.start();
        assertEquals(-1, policy.admit("new", false, false));
        assertEquals(-1, policy.admit(null, true, false));
        assertTrue(policy.admit("saved-ready-bonded", false, true) >= 0);
    }

    @Test public void stopRestartAndReadmissionRejectQueuedCallbacks() {
        GatewayPolicy policy = new GatewayPolicy();
        long owner = policy.start();
        long session = policy.admit("a", true, false);
        policy.remove("a");
        long replacement = policy.admit("a", true, false);
        assertFalse(policy.current(owner, "a", session));
        assertTrue(policy.current(owner, "a", replacement));
        policy.stop();
        assertFalse(policy.current(owner));
        assertEquals(-1, policy.admit("b", true, false));
        long newOwner = policy.start();
        long newSession = policy.admit("a", false, true);
        assertFalse(policy.current(owner, "a", replacement));
        assertTrue(policy.current(newOwner, "a", newSession));
    }

    @Test public void onlyExactTransportHandshakeCanLeavePhone() {
        assertFalse(GatewayPolicy.allowedOutput("{\"msg_type\":\"request_watch_info\"}"));
        for (String type : new String[]{"settings_request", "settings_set", "chat_received",
                "request_settings", "set_settings", "hello", "gateway_mode", "relay_status",
                "entity_sync", "emergency", "marker", "bloodhound", "marker_delete_ack"}) {
            assertFalse(type, GatewayPolicy.allowedOutput("{\"msg_type\":\"" + type + "\"}"));
        }
        assertFalse(GatewayPolicy.allowedOutput(
                "{\"msg_type\":\"request_watch_info\",\"payload\":{\"settings\":true}}"));
        assertFalse(GatewayPolicy.allowedOutput("{\"msgType\":\"request_watch_info\"}"));
        assertFalse(GatewayPolicy.allowedOutput("bad JSON"));
    }

    @Test public void phoneManagedProfileCannotBeUsedAsGatewayRouteSignal() {
        assertFalse(GatewayPolicy.allowedOutput(
                "{\"msg_type\":\"set_settings\",\"payload\":{\"phoneManagedSettings\":false}}"));
        assertFalse(GatewayPolicy.allowedOutput(
                "{\"msg_type\":\"set_settings\",\"payload\":{\"phoneManagedSettings\":true}}"));
        assertFalse(GatewayPolicy.allowedOutput(
                "{\"msg_type\":\"request_watch_info\",\"phoneManagedSettings\":false}"));
    }
}
