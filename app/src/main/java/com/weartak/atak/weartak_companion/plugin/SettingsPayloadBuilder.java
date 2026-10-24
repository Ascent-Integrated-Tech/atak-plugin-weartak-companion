package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

final class SettingsPayloadBuilder {
    private SettingsPayloadBuilder() { }

    static List<JSONObject> identityChanges(List<String> previous, String callsign, String team, String role)
            throws JSONException {
        String[] values = {callsign, team, role};
        String[] keys = {"callsign", "team", "role"};
        List<JSONObject> commands = new java.util.ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            if (values[i] == null || values[i].trim().isEmpty())
                throw new IllegalArgumentException("Live identity sync requires nonblank phone identity.");
            if (previous == null || !values[i].equals(previous.get(i))) {
                JSONObject envelope = new JSONObject();
                envelope.put("vers", 1);
                envelope.put("msg_type", "set_" + keys[i]);
                envelope.put("payload", new JSONObject().put(keys[i], values[i]));
                commands.add(envelope);
            }
        }
        return commands;
    }

    static JSONObject build(List<TakServerItem> servers, Integer interval, String callsign,
                            String team, String role, boolean managed) throws JSONException {
        return build(servers, interval, callsign, team, role, managed, null);
    }

    static JSONObject build(List<TakServerItem> servers, Integer interval, String callsign,
                            String team, String role, boolean managed,
                            java.util.Map<String, String> units) throws JSONException {
        if (interval != null && interval <= 0) {
            throw new IllegalArgumentException("Reporting interval must be positive.");
        }
        if (managed && (interval == null || callsign == null || callsign.trim().isEmpty()
                || team == null || team.trim().isEmpty() || role == null || role.trim().isEmpty())) {
            throw new IllegalArgumentException("Phone management requires a reporting interval and phone identity.");
        }
        JSONArray list = new JSONArray();
        for (int i = 0; i < servers.size(); i++) {
            TakServerItem server = servers.get(i);
            if (server.isP12Cert && (server.p12Cert == null || server.p12Cert.trim().isEmpty())) {
                String name = server.name == null || server.name.trim().isEmpty()
                        ? "Server " + (i + 1) : server.name;
                throw new IllegalArgumentException(name + " is set to P12 auth but has no certificate selected.");
            }
            list.put(server.toJson());
        }
        JSONObject payload = new JSONObject();
        payload.put("takServerList", list);
        if (interval != null) payload.put("reportIntSecs", interval);
        if (callsign != null && !callsign.trim().isEmpty()) payload.put("callsign", callsign.trim());
        if (managed) {
            payload.put("team", team);
            payload.put("role", role);
        }
        payload.put("phoneManagedSettings", managed);
        if (units != null) payload.put("unitPrefs", new JSONObject(units));
        JSONObject envelope = new JSONObject();
        envelope.put("msg_type", "set_settings");
        envelope.put("payload", payload);
        return envelope;
    }
}
