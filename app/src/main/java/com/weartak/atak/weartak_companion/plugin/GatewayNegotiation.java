package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

/** A transport-ready bond is not a compatible gateway session. */
final class GatewayNegotiation {
    private String token;
    private long bleSession = -1;
    private boolean ready;
    void begin(String token, long bleSession) {
        this.token = token; this.bleSession = bleSession; ready = false;
    }
    boolean acknowledge(JSONObject envelope, long currentBleSession) {
        if (token == null || currentBleSession != bleSession
                || !token.equals(envelope.optString("session"))
                || envelope.optInt("version") != 1 || !envelope.optBoolean("mapPosition")
                || !envelope.optBoolean("emergency") || !envelope.optBoolean("chat")
                || envelope.optString("uid").trim().isEmpty()
                || envelope.optString("callsign").trim().isEmpty()) return false;
        ready = true;
        return true;
    }
    boolean accepts(String token, long currentBleSession) {
        return ready && this.token != null && this.token.equals(token) && bleSession == currentBleSession;
    }
    void clear() { token = null; bleSession = -1; ready = false; }
}
