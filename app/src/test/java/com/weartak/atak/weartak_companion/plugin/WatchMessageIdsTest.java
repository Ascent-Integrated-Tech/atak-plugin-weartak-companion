package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class WatchMessageIdsTest {
    private JSONObject envelope(String id, String session) throws Exception {
        return new JSONObject().put("msgType", "emergency").put("payload",
                new JSONObject().put("messageId", id).put("relaySession", session));
    }

    @Test public void eighteenRetriesAcrossRoutesWriteOnceAndAllAckCurrentSession() throws Exception {
        WatchMessageIds ids = new WatchMessageIds();
        List<Map<String, Object>> acks = new ArrayList<>();
        int takWrites = 0;
        for (int i = 0; i < 18; i++) {
            JSONObject envelope = envelope("manual-alert", "session-" + i);
            // BLE and Connect IQ share the same gate, regardless of their route/session.
            if (i % 2 == 1) envelope.put("messageId", "manual-alert");
            if (ids.first(envelope)) takWrites++;
            acks.add(WatchMessageIds.result(envelope, true));
        }
        assertEquals(1, takWrites);
        assertEquals(18, acks.size());
        for (int i = 0; i < 18; i++) {
            assertEquals("session-" + i, acks.get(i).get("relaySession"));
            assertEquals("manual-alert", acks.get(i).get("messageId"));
            assertEquals(true, acks.get(i).get("ok"));
        }
        assertTrue(ids.first(envelope("cancel-is-a-new-message", "session-18")));
    }

    @Test public void legacyWearOsWithoutMessageIdsRemainsCompatible() throws Exception {
        WatchMessageIds ids = new WatchMessageIds();
        JSONObject old = new JSONObject().put("msgType", "pli");
        assertTrue(ids.first(old));
        assertTrue(ids.first(old));
        assertTrue(ids.first(new JSONObject().put("msg_id", "legacy-id")));
        assertFalse(ids.first(new JSONObject().put("msgUid", "legacy-id")));
    }

    @Test public void negativeAckRetainsIncomingIdentifierAndSession() throws Exception {
        Map<String, Object> result = WatchMessageIds.result(envelope("bad", "current"), false);
        assertEquals("bad", result.get("messageId"));
        assertEquals("current", result.get("relaySession"));
        assertEquals(false, result.get("ok"));
    }

    @Test public void stablePayloadIdWinsOverRouteSpecificEnvelopeId() throws Exception {
        WatchMessageIds ids = new WatchMessageIds();
        assertTrue(ids.first(envelope("stable", "one").put("messageId", "iq-route-id")));
        assertFalse(ids.first(envelope("stable", "two").put("messageId", "ble-route-id")));
    }

    @Test public void acceptedRetryIsRecognizedEvenAfterOriginalMarkerExpires() throws Exception {
        WatchMessageIds ids = new WatchMessageIds();
        JSONObject marker = envelope("point", "one").put("msgType", "marker");
        marker.getJSONObject("payload").put("tStale", "2099-01-01T00:00:00Z");
        assertTrue(ids.first(marker));
        marker.getJSONObject("payload").put("tStale", "2000-01-01T00:00:00Z");
        assertTrue(ids.seen(marker));
        assertEquals(true, WatchMessageIds.result(marker, true).get("ok"));
    }
}
