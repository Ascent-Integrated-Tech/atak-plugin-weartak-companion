package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class WatchSettingsSnapshotTest {
    @Test public void independentlySparseDeltasPreserveTheOtherObservedField() throws Exception {
        JSONObject observed = snapshot().put("team", "Cyan").put("role", "Medic");
        observed = WatchSettingsSnapshot.observe(observed,
                new JSONObject().put("team", "Green"), false);
        assertEquals("Green", observed.getString("team"));
        assertEquals("Medic", observed.getString("role"));
        observed = WatchSettingsSnapshot.observe(observed,
                new JSONObject().put("role", "Team Lead"), false);
        assertEquals("Green", observed.getString("team"));
        assertEquals("Team Lead", observed.getString("role"));
    }

    @Test public void snapshotsAndIdentityOnlyDeltasRetainOnlyCurrentSessionObservations() throws Exception {
        SettingsSessionGuard session = new SettingsSessionGuard();
        long token = session.token();
        JSONObject observed = WatchSettingsSnapshot.observe(null,
                new JSONObject().put("team", "Red"), false);
        session.acceptSnapshot(token, false);
        assertEquals("Red", observed.getString("team"));
        assertFalse(observed.has("role"));
        assertFalse(session.canSend());

        JSONObject full = snapshot().put("role", "Medic");
        observed = WatchSettingsSnapshot.observe(observed, full, true);
        session.acceptSnapshot(token, true);
        assertTrue(session.canSend());
        assertEquals("Red", observed.getString("team"));
        assertEquals("Medic", observed.getString("role"));
        observed = WatchSettingsSnapshot.observe(observed,
                new JSONObject().put("team", "Green").put("role", "Team Lead"), false);
        assertEquals("Green", observed.getString("team"));
        assertEquals("Team Lead", observed.getString("role"));
        assertEquals(30, observed.getInt("reportIntSecs"));
        assertEquals(0, observed.getJSONArray("takServerList").length());
        assertTrue(session.canSend());

        // Mirrors clearSessionSettings on disconnect, peer switch and mode teardown.
        session.clear();
        observed = null;
        assertFalse(session.acceptSnapshot(token, true));
        assertFalse(session.canSend());
        observed = WatchSettingsSnapshot.observe(observed, snapshot(), true);
        assertFalse(observed.has("team"));
        assertFalse(observed.has("role"));
    }

    @Test public void missingNullBlankAndInvalidFieldsDoNotInventOrEraseIdentity() throws Exception {
        JSONObject observed = snapshot().put("team", "Cyan").put("role", "Medic");
        JSONObject[] changes = {
                new JSONObject(), new JSONObject().put("callsign", "NEW"),
                new JSONObject().put("team", JSONObject.NULL).put("role", ""),
                new JSONObject().put("team", " ").put("role", 17)
        };
        for (JSONObject change : changes) {
            observed = WatchSettingsSnapshot.observe(observed, change, false);
            assertEquals("Cyan", observed.getString("team"));
            assertEquals("Medic", observed.getString("role"));
        }
        observed = WatchSettingsSnapshot.observe(observed, snapshot(), true);
        assertEquals("Cyan", observed.getString("team"));
        assertEquals("Medic", observed.getString("role"));
        JSONObject oldPeer = WatchSettingsSnapshot.observe(null, snapshot(), true);
        assertFalse(oldPeer.has("team"));
        assertFalse(oldPeer.has("role"));
    }

    @Test public void observationsAreCopiedAndDoNotBecomeOutgoingUnmanagedSettings() throws Exception {
        JSONObject incoming = snapshot().put("team", " Red ").put("role", " Medic ");
        JSONObject observed = WatchSettingsSnapshot.observe(null, incoming, true);
        assertEquals("Red", observed.getString("team"));
        incoming.put("team", "MUTATED");
        assertEquals("Red", observed.getString("team"));
        JSONObject changed = WatchSettingsSnapshot.observe(observed,
                new JSONObject().put("role", "Team Lead"), false);
        assertEquals("Medic", observed.getString("role"));
        assertEquals("Team Lead", changed.getString("role"));
        JSONObject outgoing = SettingsPayloadBuilder.build(java.util.Collections.emptyList(),
                30, "WATCH", null, null, false).getJSONObject("payload");
        assertFalse(outgoing.has("team"));
        assertFalse(outgoing.has("role"));
    }

    private static JSONObject snapshot() throws Exception {
        return new JSONObject().put("takServerList", new JSONArray()).put("reportIntSecs", 30)
                .put("callsign", "WATCH");
    }
}
