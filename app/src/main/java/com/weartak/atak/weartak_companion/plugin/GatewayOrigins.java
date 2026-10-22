package com.weartak.atak.weartak_companion.plugin;

import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Do not evict origin protection and accidentally turn an old watch event into a new network event. */
final class GatewayOrigins {
    private final Map<String, String> owners = new LinkedHashMap<>();
    private final SharedPreferences preferences;

    GatewayOrigins() { this(null); }

    GatewayOrigins(SharedPreferences preferences) {
        this.preferences = preferences;
        if (preferences == null) return;
        try {
            JSONObject saved = new JSONObject(preferences.getString("origins", "{}"));
            if (saved.length() > 2048) throw new IllegalStateException("Gateway origin ledger exceeds limit");
            boolean migratedPhoneAlerts = false;
            for (java.util.Iterator<String> keys = saved.keys(); keys.hasNext();) {
                String uid = keys.next();
                String owner = saved.getString(uid);
                if ("phone-alert".equals(owner)) {
                    migratedPhoneAlerts = true;
                } else {
                    owners.put(uid, owner);
                }
            }
            if (migratedPhoneAlerts) persist();
        } catch (JSONException e) {
            throw new IllegalStateException("Gateway origin ledger is corrupt", e);
        }
    }

    void claim(String uid, String peer) {
        String previous = owners.get(uid);
        if (previous != null && !previous.equals(peer))
            throw new IllegalArgumentException("Watch UID conflicts with another peer");
        if (previous == null && owners.size() >= 2048)
            throw new IllegalStateException("Gateway origin ledger full");
        if (previous == null) {
            owners.put(uid, peer);
            persist();
        }
    }
    boolean contains(String uid) { return owners.containsKey(uid); }
    boolean allowsPhoneEvent(String uid) { return !contains(uid); }
    void forgetPeer(String peer) {
        boolean changed = false;
        java.util.Iterator<Map.Entry<String, String>> iterator = owners.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().equals(peer)) {
                iterator.remove();
                changed = true;
            }
        }
        if (changed) persist();
    }
    void clear() { owners.clear(); }

    private void persist() {
        if (preferences == null) return;
        JSONObject saved = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : owners.entrySet())
                saved.put(entry.getKey(), entry.getValue());
        } catch (JSONException e) {
            throw new IllegalStateException("Could not persist Gateway origin ledger", e);
        }
        preferences.edit().putString("origins", saved.toString()).apply();
    }
}
