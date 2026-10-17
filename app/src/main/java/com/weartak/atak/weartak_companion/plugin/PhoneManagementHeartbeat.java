package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

final class PhoneManagementHeartbeat {
    private PhoneManagementHeartbeat() {}

    static JSONObject response(JSONObject message) throws JSONException {
        if (message.getInt("version") != 1)
            throw new JSONException("Unsupported management heartbeat version");
        String token = message.getString("token");
        if (!token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new JSONException("Invalid management heartbeat token");
        return new JSONObject().put("msg_type", "phone_management_pong")
                .put("version", 1).put("token", token);
    }
}
