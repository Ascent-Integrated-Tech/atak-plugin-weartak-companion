package com.weartak.atak.weartak_companion.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-session bounded latest-revision handoff. Rejected writes stay queued for paced retry. */
final class GatewayFanout {
    static final int LIMIT = 128;
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Map<String, String> sent = new LinkedHashMap<>();
    interface Writer { boolean write(String xml); }
    boolean offer(String uid, String xml) {
        if (xml.equals(sent.get(uid)) || xml.equals(pending.get(uid))) return true;
        if (!pending.containsKey(uid) && pending.size() >= LIMIT) return false;
        pending.put(uid, xml);
        return true;
    }
    boolean drain(Writer writer) {
        if (pending.isEmpty()) return true;
        Map.Entry<String, String> next = pending.entrySet().iterator().next();
        if (!writer.write(next.getValue())) return false;
        sent.put(next.getKey(), next.getValue());
        pending.remove(next.getKey());
        while (sent.size() > LIMIT) sent.remove(sent.keySet().iterator().next());
        return true;
    }
    int size() { return pending.size(); }
    void clear() { pending.clear(); sent.clear(); }
}
