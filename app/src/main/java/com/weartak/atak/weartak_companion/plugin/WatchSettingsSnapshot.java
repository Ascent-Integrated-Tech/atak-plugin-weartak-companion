package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

/** Merge optional identity observations without making a delta a complete settings snapshot. */
final class WatchSettingsSnapshot {
    private WatchSettingsSnapshot() {}

    static JSONObject observe(JSONObject previous, JSONObject incoming, boolean complete)
            throws JSONException {
        JSONObject result = new JSONObject(complete ? incoming.toString()
                : previous == null ? "{}" : previous.toString());
        for (String key : new String[]{"team", "role"}) {
            Object value = incoming.opt(key);
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                value = previous == null ? null : previous.opt(key);
            }
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                result.put(key, ((String) value).trim());
            } else {
                result.remove(key);
            }
        }
        return result;
    }
}
