package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class CompanionSettingsPolicyTest {
    @Test public void bondProvisioningIncludesPhoneManagementAndIdentity() throws Exception {
        boolean managed = CompanionSettingsPolicy.phoneManaged(false, false);
        JSONObject payload = SettingsPayloadBuilder.build(Collections.emptyList(),
                30, "PHONE", "Dark Green", "K9", managed).getJSONObject("payload");
        assertTrue(payload.getBoolean("phoneManagedSettings"));
        assertEquals("PHONE", payload.getString("callsign"));
        assertEquals("Dark Green", payload.getString("team"));
        assertEquals("K9", payload.getString("role"));
    }

    @Test public void configureAndGatewayNeverRequestManagement() {
        assertFalse(CompanionSettingsPolicy.phoneManaged(true, false));
        assertFalse(CompanionSettingsPolicy.phoneManaged(false, true));
        assertFalse(CompanionSettingsPolicy.phoneManaged(true, true));
    }

    @Test public void returningFromConfigureOrGatewayToBondReactivatesManagement() {
        assertFalse(CompanionSettingsPolicy.phoneManaged(true, false));
        assertTrue(CompanionSettingsPolicy.phoneManaged(false, false));
        assertFalse(CompanionSettingsPolicy.phoneManaged(false, true));
        assertTrue(CompanionSettingsPolicy.phoneManaged(false, false));
    }

    @Test public void reconnectRequiresFreshSnapshotEvenWhenBondRequestsManagement() throws Exception {
        SettingsSessionGuard session = new SettingsSessionGuard();
        long oldToken = session.token();
        assertTrue(session.acceptSnapshot(oldToken, true));
        session.clear();
        JSONObject unmanagedSnapshot = new JSONObject()
                .put("phoneManagedSettings", false).put("reportIntSecs", 30)
                .put("takServerList", new org.json.JSONArray());
        assertFalse(session.acceptSnapshot(oldToken, true));
        assertFalse(session.canSend());
        assertTrue(session.acceptSnapshot(session.token(),
                unmanagedSnapshot.has("reportIntSecs")
                        && unmanagedSnapshot.optJSONArray("takServerList") != null));
        assertTrue(session.canSend());
        assertTrue(CompanionSettingsPolicy.phoneManaged(false, false));
        assertFalse(unmanagedSnapshot.getBoolean("phoneManagedSettings"));
    }
}
