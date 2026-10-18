package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class LocationDiagnosticsProtocol {
    static final int VERSION = 1;
    static final int MAX_MESSAGE_BYTES = 512;
    static final String HELLO = "location_diagnostics_hello";
    static final String CAPABILITY = "location_diagnostics_capability";
    static final String SNAPSHOT = "location_diagnostics_snapshot";
    static final String STOP = "location_diagnostics_stop";
    private static final Pattern SESSION =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> SNAPSHOT_FIELDS = new HashSet<>(Arrays.asList(
            "source", "ageMs", "accuracyM", "speedMps", "speedAccuracyMps", "bearingAccuracyDeg",
            "satellites", "used", "meanCn0DbHz", "maxAgcDb", "satelliteStatusSupported",
            "measurementsSupported", "providerAvailable", "quality", "reason", "error"));
    private static final Set<String> ENVELOPE_FIELDS = new HashSet<>(Arrays.asList(
            "msgType", "msg_type", "version", "session", "supported", "payload"));

    private LocationDiagnosticsProtocol() { }

    static boolean isValidSession(String session) {
        return session != null && SESSION.matcher(session).matches();
    }

    static JSONObject parse(JSONObject envelope, String type, String session) {
        if (envelope == null || !isValidSession(session) ||
                envelope.toString().getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES ||
                envelope.optInt("version", -1) != VERSION ||
                !type.equals(envelope.optString("msgType", envelope.optString("msg_type", ""))) ||
                !session.equals(envelope.optString("session", "")) ||
                containsPrivateFields(envelope) || !hasOnlyFields(envelope, ENVELOPE_FIELDS)) {
            return null;
        }
        if ((HELLO.equals(type) || STOP.equals(type)) &&
                !hasOnlyFields(envelope, new HashSet<>(Arrays.asList(
                        "msgType", "msg_type", "version", "session")))) return null;
        if (CAPABILITY.equals(type) &&
                !hasOnlyFields(envelope, new HashSet<>(Arrays.asList(
                        "msgType", "msg_type", "version", "session", "supported")))) return null;
        if (SNAPSHOT.equals(type) &&
                !hasOnlyFields(envelope, new HashSet<>(Arrays.asList(
                        "msgType", "msg_type", "version", "session", "payload")))) return null;
        if (SNAPSHOT.equals(type) && !validSnapshot(envelope.optJSONObject("payload"))) return null;
        if (CAPABILITY.equals(type) && !(envelope.opt("supported") instanceof Boolean)) return null;
        return envelope;
    }

    private static boolean validSnapshot(JSONObject payload) {
        if (payload == null || !hasOnlyFields(payload, SNAPSHOT_FIELDS)) return false;
        String source = payload.optString("source", "");
        if (source.length() > 32 || !source.matches("[A-Za-z0-9 _-]*")) return false;
        for (String key : Arrays.asList("reason", "error")) {
            if (payload.optString(key, "").length() > 80) return false;
        }
        if (!validNumber(payload, "ageMs", 86_400_000d) ||
                !validNumber(payload, "accuracyM", 1_000_000d) ||
                !validNumber(payload, "speedMps", 1_000d) ||
                !validNumber(payload, "speedAccuracyMps", 1_000d) ||
                !validNumber(payload, "bearingAccuracyDeg", 360d) ||
                !validNumber(payload, "satellites", 256d) ||
                !validNumber(payload, "used", 256d) ||
                !validNumber(payload, "meanCn0DbHz", 100d) ||
                !validNumber(payload, "maxAgcDb", -200d, 1_000d)) return false;
        for (String key : Arrays.asList("satelliteStatusSupported", "measurementsSupported",
                "providerAvailable")) {
            Object value = payload.opt(key);
            if (value != null && value != JSONObject.NULL && !(value instanceof Boolean)) return false;
        }
        return true;
    }

    private static boolean validNumber(JSONObject object, String key, double max) {
        return validNumber(object, key, 0d, max);
    }

    private static boolean validNumber(JSONObject object, String key, double min, double max) {
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return true;
        if (!(value instanceof Number)) return false;
        double number = ((Number) value).doubleValue();
        return !Double.isNaN(number) && !Double.isInfinite(number) && number >= min && number <= max;
    }

    private static boolean hasOnlyFields(JSONObject object, Set<String> allowed) {
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) return false;
        return true;
    }

    static JSONObject capability(String session, boolean supported) throws JSONException {
        if (!isValidSession(session)) throw new IllegalArgumentException("Invalid diagnostics session");
        return new JSONObject().put("msg_type", CAPABILITY).put("version", VERSION)
                .put("session", session).put("supported", supported);
    }

    static JSONObject snapshot(String session, JSONObject payload) throws JSONException {
        if (!isValidSession(session)) throw new IllegalArgumentException("Invalid diagnostics session");
        if (!validSnapshot(payload)) throw new IllegalArgumentException("Invalid diagnostics snapshot");
        JSONObject envelope = new JSONObject().put("msg_type", SNAPSHOT).put("version", VERSION)
                .put("session", session).put("payload", payload);
        if (envelope.toString().getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Diagnostics snapshot exceeds protocol size limit");
        }
        return envelope;
    }

    static String quality(JSONObject payload) {
        if (payload == null || !payload.optBoolean("providerAvailable", false) ||
                payload.has("error") && !payload.optString("error").isEmpty()) {
            return "UNAVAILABLE";
        }
        if (!payload.optBoolean("satelliteStatusSupported", false) ||
                !payload.has("ageMs") || payload.isNull("ageMs") ||
                !payload.has("accuracyM") || payload.isNull("accuracyM") ||
                !payload.has("used") || payload.isNull("used") ||
                !payload.optBoolean("measurementsSupported", false) ||
                !payload.has("meanCn0DbHz") || payload.isNull("meanCn0DbHz") ||
                !payload.has("maxAgcDb") || payload.isNull("maxAgcDb")) {
            return "UNKNOWN";
        }
        long age = payload.optLong("ageMs", Long.MAX_VALUE);
        double accuracy = payload.optDouble("accuracyM", Double.POSITIVE_INFINITY);
        int used = payload.optInt("used", 0);
        if (age > 30_000L || accuracy > 100d || used < 4) return "DEGRADED";
        if (age <= 10_000L && accuracy <= 20d && payload.optInt("satellites", 0) >= 4 &&
                used >= 4 && payload.optDouble("meanCn0DbHz", 0d) >= 20d) return "GOOD";
        return "DEGRADED";
    }

    private static boolean containsPrivateFields(JSONObject object) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String normalized = key.toLowerCase(Locale.ROOT);
            if (normalized.equals("lat") || normalized.equals("latitude") ||
                    normalized.equals("lon") || normalized.equals("lng") ||
                    normalized.equals("longitude") || normalized.equals("uid") ||
                    normalized.contains("deviceid") || normalized.equals("address") ||
                    normalized.equals("mac")) return true;
            Object value = object.opt(key);
            if (value instanceof JSONObject && containsPrivateFields((JSONObject) value)) return true;
        }
        return false;
    }
}
