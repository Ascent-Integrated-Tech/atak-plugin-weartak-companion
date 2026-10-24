package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Shared by BLE and Connect IQ ingress, not reset when either transport reconnects. */
final class WatchMessageIds {
    private final Set<String> received = new LinkedHashSet<>();

    static String id(JSONObject envelope) {
        JSONObject payload = envelope.optJSONObject("payload");
        String id = payload == null ? "" : payload.optString("messageId", "");
        if (id.isEmpty()) id = envelope.optString("messageId", "");
        if (id.isEmpty()) id = envelope.optString("msg_id", "");
        if (id.isEmpty()) id = envelope.optString("msgUid", "");
        return id;
    }

    synchronized boolean first(JSONObject envelope) {
        String id = id(envelope);
        if (id.isEmpty()) return true; // Older WearOS envelopes have no retransmission identifier.
        if (!received.add(id)) return false;
        while (received.size() > 4096) received.remove(received.iterator().next());
        return true;
    }

    synchronized boolean seen(JSONObject envelope) {
        String id = id(envelope);
        return !id.isEmpty() && received.contains(id);
    }

    synchronized void forget(JSONObject envelope) {
        String id = id(envelope);
        if (!id.isEmpty()) received.remove(id);
    }

    static Map<String, Object> result(JSONObject envelope, boolean accepted) {
        JSONObject payload = envelope.optJSONObject("payload");
        Map<String, Object> result = new HashMap<>();
        result.put("messageId", id(envelope));
        result.put("relaySession", payload == null ? "" : payload.optString("relaySession", ""));
        result.put("ok", accepted);
        return result;
    }
}
