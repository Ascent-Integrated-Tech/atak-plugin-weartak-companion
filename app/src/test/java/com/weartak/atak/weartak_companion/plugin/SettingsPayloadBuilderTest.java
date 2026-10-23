package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class SettingsPayloadBuilderTest {
    @Test public void eachLiveChangeSendsOnlyItsMatchingCommandAndField() throws Exception {
        java.util.List<String> before = java.util.Arrays.asList("OLD", "Blue", "Team Member");
        String[] fields = {"callsign", "team", "role"};
        String[] replacements = {"NEW", "Red", "Medic"};
        for (int i = 0; i < fields.length; i++) {
            java.util.List<String> after = new java.util.ArrayList<>(before);
            after.set(i, replacements[i]);
            java.util.List<JSONObject> commands = SettingsPayloadBuilder.identityChanges(
                    before, after.get(0), after.get(1), after.get(2));
            assertEquals(1, commands.size());
            JSONObject command = commands.get(0);
            assertEquals(3, command.length());
            assertEquals(1, command.getInt("vers"));
            assertEquals("set_" + fields[i], command.getString("msg_type"));
            JSONObject payload = command.getJSONObject("payload");
            assertEquals(1, payload.length());
            assertEquals(replacements[i], payload.getString(fields[i]));
            for (String absent : new String[]{"takServerList", "reportIntSecs", "p12Cert",
                    "p12CertPassword", "phoneManagedSettings", "standaloneCallsign"})
                assertFalse(payload.has(absent));
        }
    }

    @Test public void unchangedIdentitySendsNothingAndCoalescedChangesStaySeparate() throws Exception {
        java.util.List<String> before = java.util.Arrays.asList("OLD", "Blue", "Team Member");
        assertTrue(SettingsPayloadBuilder.identityChanges(before, "OLD", "Blue", "Team Member").isEmpty());
        java.util.List<JSONObject> commands = SettingsPayloadBuilder.identityChanges(before, "NEW", "Red", "Medic");
        assertEquals(3, commands.size());
        assertEquals("set_callsign", commands.get(0).getString("msg_type"));
        assertEquals("set_team", commands.get(1).getString("msg_type"));
        assertEquals("set_role", commands.get(2).getString("msg_type"));
        for (JSONObject command : commands) assertEquals(1, command.getJSONObject("payload").length());
        assertEquals("OLD", before.get(0));
    }

    @Test public void newSessionCanSendCurrentValuesWithoutPriorPeerIdentity() throws Exception {
        assertEquals(3, SettingsPayloadBuilder.identityChanges(null, "PHONE", "Blue", "Medic").size());
    }

    @Test public void invalidLiveIdentityIsRejectedBeforeAnyCommandsAreReturned() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> SettingsPayloadBuilder.identityChanges(null, "", "Blue", "Medic"));
        assertThrows(IllegalArgumentException.class,
                () -> SettingsPayloadBuilder.identityChanges(null, "PHONE", null, "Medic"));
        assertThrows(IllegalArgumentException.class,
                () -> SettingsPayloadBuilder.identityChanges(null, "PHONE", "Blue", " "));
    }

    @Test
    public void oneTimeUploadPreservesP12AndCustomWatchCallsignWithoutPhoneManagement() throws Exception {
        TakServerItem server = TakServerItem.fromJson(new JSONObject()
                .put("name", "TAK").put("address", "tak.example").put("port", 8089)
                .put("isEnabled", true).put("isP12Cert", true)
                .put("p12Cert", "dGVzdC1jZXJ0").put("p12CertPassword", "test-password"));
        JSONObject envelope = SettingsPayloadBuilder.build(Collections.singletonList(server),
                30, "ODIN-WEARTAK", "Blue", "Medic", false);
        assertEquals("set_settings", envelope.getString("msg_type"));
        JSONObject payload = envelope.getJSONObject("payload");
        assertFalse(payload.getBoolean("phoneManagedSettings"));
        assertEquals("ODIN-WEARTAK", payload.getString("callsign"));
        assertFalse(payload.has("team"));
        assertFalse(payload.has("role"));
        TakServerItem sent = TakServerItem.fromJson(payload.getJSONArray("takServerList").getJSONObject(0));
        assertTrue(sent.isP12Cert);
        assertEquals(server.p12Cert, sent.p12Cert);
        assertEquals(server.p12CertPassword, sent.p12CertPassword);
    }

    @Test
    public void oneTimeProvisioningDoesNotRequirePhoneIdentityOrReporting() throws Exception {
        JSONObject payload = SettingsPayloadBuilder.build(Collections.emptyList(),
                null, null, null, null, false).getJSONObject("payload");
        assertFalse(payload.getBoolean("phoneManagedSettings"));
        assertFalse(payload.has("callsign"));
        assertFalse(payload.has("reportIntSecs"));
    }

    @Test
    public void managementMustBeExplicitAndIncludesPhoneIdentity() throws Exception {
        JSONObject payload = SettingsPayloadBuilder.build(Collections.emptyList(),
                5, "PHONE", "Dark Green", "K9", true).getJSONObject("payload");
        assertTrue(payload.getBoolean("phoneManagedSettings"));
        assertEquals("PHONE", payload.getString("callsign"));
        assertEquals("Dark Green", payload.getString("team"));
        assertEquals("K9", payload.getString("role"));
        assertThrows(IllegalArgumentException.class, () -> SettingsPayloadBuilder.build(
                Collections.emptyList(), null, null, null, null, true));
    }

    @Test
    public void rejectsMissingP12AndInvalidReportingBeforeSending() throws Exception {
        TakServerItem server = TakServerItem.fromJson(new JSONObject()
                .put("name", "TAK").put("isP12Cert", true).put("p12Cert", ""));
        assertThrows(IllegalArgumentException.class, () -> SettingsPayloadBuilder.build(
                Collections.singletonList(server), 30, "WATCH", null, null, false));
        assertThrows(IllegalArgumentException.class, () -> SettingsPayloadBuilder.build(
                Collections.emptyList(), -1, "WATCH", null, null, false));
    }
}
