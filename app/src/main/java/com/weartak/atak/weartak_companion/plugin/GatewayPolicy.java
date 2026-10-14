package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Admission and callback ownership, independent of Android BLE. Main-thread confined. */
final class GatewayPolicy {
    static final int CAP = 9;
    private final Map<String, Long> peers = new LinkedHashMap<>();
    private long generation;
    private long nextSession;
    private boolean running;

    long start() { stop(); running = true; return generation; }
    void stop() { running = false; ++generation; peers.clear(); }
    boolean current(long token) { return running && generation == token; }
    long admit(String address, boolean manuallySelected, boolean previouslyReadyBonded) {
        if (!running || address == null || address.isEmpty()
                || (!manuallySelected && !previouslyReadyBonded)
                || peers.containsKey(address) || peers.size() >= CAP) return -1;
        long session = ++nextSession;
        peers.put(address, session);
        return session;
    }
    boolean current(long token, String address, long session) {
        Long active = peers.get(address);
        return current(token) && active != null && active == session;
    }
    void remove(String address) { peers.remove(address); }
    static String pairingPreferences(String address) { return "wtk_gateway_pair_" + address; }
    static String retryPreferences(String address) { return "wtk_gateway_retry_" + address; }

    static boolean allowedOutput(String json) {
        try {
            JSONObject env = new JSONObject(json);
            String type = env.optString("msg_type");
            String session = env.optString("session");
            if (!session.matches("[a-zA-Z0-9-]{1,64}")) return false;
            if ("gateway_hello".equals(type)) {
                return env.length() == 3 && env.optInt("version") == 1;
            }
            return "gateway_cot".equals(type) && env.length() == 3
                    && !env.optString("cot").isEmpty()
                    && env.getString("cot").getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32768;
        } catch (Exception ignored) {
            return false;
        }
    }
}
