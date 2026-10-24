package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Process-local ownership gate for best-effort deletion of Garmin-created points. */
final class GarminMarkerDelete {
    static final int CAPABILITY_VERSION = 1;
    private static final int MAX_OWNERS = 256;
    private static final long CREATED_AT_SKEW_SECONDS = 300;

    static final class Owner {
        final String watchUid;
        final String type;
        final double lat, lon, hae, ce, le;

        Owner(String watchUid, String type, double lat, double lon, double hae, double ce, double le) {
            this.watchUid = watchUid;
            this.type = type;
            this.lat = lat;
            this.lon = lon;
            this.hae = hae;
            this.ce = ce;
            this.le = le;
        }
    }

    private final Map<String, Owner> owners = new LinkedHashMap<>();

    synchronized boolean record(String uid, String watchUid, String type,
                                double lat, double lon, double hae, double ce, double le) {
        if (!validUid(uid) || !validIdentity(watchUid) || !validIdentity(type)
                || !PhoneFixValidator.isUsable(lat, lon)
                || !Double.isFinite(hae) || !Double.isFinite(ce) || !Double.isFinite(le))
            return false;
        Owner existing = owners.get(uid);
        if (existing != null && !existing.watchUid.equals(watchUid)) return false;
        owners.put(uid, new Owner(watchUid, type, lat, lon, hae, ce, le));
        while (owners.size() > MAX_OWNERS) owners.remove(owners.keySet().iterator().next());
        return true;
    }

    synchronized Owner authorize(JSONObject payload, String currentWatchUid, String currentSession,
                                 long nowEpochSeconds) {
        if (payload == null || !validIdentity(currentWatchUid) || !validIdentity(currentSession))
            return null;
        String uid = payload.optString("uid", "");
        String messageId = payload.optString("messageId", "");
        Object createdAt = payload.opt("createdAt");
        if (!validUid(uid) || !validMessageId(messageId) || !validEpochSeconds(createdAt, nowEpochSeconds)
                || !currentSession.equals(payload.optString("relaySession", ""))) return null;
        Owner owner = owners.get(uid);
        return owner != null && owner.watchUid.equals(currentWatchUid)
                ? owner : null;
    }

    synchronized void remove(String uid, Owner owner) {
        if (owners.get(uid) == owner) owners.remove(uid);
    }

    static String deleteCot(String uid, Owner owner, long now) {
        if (!validUid(uid) || owner == null) throw new IllegalArgumentException("Invalid Garmin marker delete");
        Instant time = Instant.ofEpochMilli(now);
        String timestamp = time.toString();
        String stale = time.plusSeconds(60).toString();
        return "<event version=\"2.0\" uid=\"garmin-marker-delete-" + UUID.randomUUID()
                + "\" type=\"t-x-d-d\" time=\"" + timestamp + "\" start=\"" + timestamp
                + "\" stale=\"" + stale + "\" how=\"h-g-i-g-o\"><point lat=\"" + owner.lat + "\" lon=\"" + owner.lon
                + "\" hae=\"" + owner.hae + "\" ce=\"" + owner.ce + "\" le=\"" + owner.le
                + "\"/><detail><link uid=\"" + xml(uid)
                + "\" relation=\"none\" type=\"none\"/><__forcedelete/></detail></event>";
    }

    private static boolean validUid(String uid) {
        return uid != null && uid.startsWith("garmin-marker-")
                && uid.length() > "garmin-marker-".length() && validIdentity(uid);
    }

    private static boolean validIdentity(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }

    private static boolean validMessageId(String value) {
        return value != null && !value.trim().isEmpty() && value.length() <= 256
                && noControlCharacters(value);
    }

    private static boolean noControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }

    private static boolean validEpochSeconds(Object value, long nowEpochSeconds) {
        if (!(value instanceof Number)) return false;
        double seconds = ((Number) value).doubleValue();
        return Double.isFinite(seconds) && seconds > 0 && seconds == Math.rint(seconds)
                && Math.abs(seconds - nowEpochSeconds) <= CREATED_AT_SKEW_SECONDS;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
