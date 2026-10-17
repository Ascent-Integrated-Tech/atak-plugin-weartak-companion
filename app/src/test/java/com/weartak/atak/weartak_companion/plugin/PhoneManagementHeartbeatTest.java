package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class PhoneManagementHeartbeatTest {
    @Test public void responseEchoesOnlyVersionAndChallenge() throws Exception {
        String token = "01234567-89ab-cdef-0123-456789abcdef";
        JSONObject response = PhoneManagementHeartbeat.response(new JSONObject()
                .put("version", 1).put("token", token).put("extra", "not echoed"));
        assertEquals("phone_management_pong", response.getString("msg_type"));
        assertEquals(1, response.getInt("version"));
        assertEquals(token, response.getString("token"));
        assertEquals(3, response.length());
    }

    @Test public void rejectsUnsupportedVersionAndInvalidChallenge() throws Exception {
        assertThrows(JSONException.class, () -> PhoneManagementHeartbeat.response(
                new JSONObject().put("version", 2).put("token", "1")));
        assertThrows(JSONException.class, () -> PhoneManagementHeartbeat.response(
                new JSONObject().put("version", 1).put("token", "1-1-1-1-1")));
        assertThrows(JSONException.class, () -> PhoneManagementHeartbeat.response(new JSONObject()));
    }
}
