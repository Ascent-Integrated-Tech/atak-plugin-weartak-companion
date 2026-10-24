package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Session-scoped navigation state; point/PLI relay policies are intentionally separate. */
final class PhoneRouteNavigationRelay {
    static final int MAX_FRAME_BYTES = 60 * 1024;
    static final int MAX_POINTS = 10_000;

    interface Writer {
        boolean write(String json, long connection);
    }

    private String routeUid;
    private String navigationSession;
    private long sequence;
    private long sentConnection = -1;
    private String sentFingerprint;
    private boolean stopping;
    private boolean completed;

    void start(String uid) {
        start(uid, UUID.randomUUID().toString());
    }

    void start(String uid, String id) {
        if (uid == null || uid.trim().isEmpty() || uid.length() > 256) {
            throw new IllegalArgumentException("Invalid navigation route UID");
        }
        routeUid = uid;
        if (!RouteNavigationControl.validUuid(id)) {
            throw new IllegalArgumentException("Invalid navigation session");
        }
        navigationSession = id;
        sequence = 0;
        sentConnection = -1;
        sentFingerprint = null;
        stopping = false;
        completed = false;
    }

    String sessionId() { return navigationSession; }
    String routeUid() { return routeUid; }

    boolean update(String uid, int target, int pointCount, String cot, String fingerprint,
                   long connection, Writer writer) throws JSONException {
        if (navigationSession == null || stopping || !routeUid.equals(uid) || connection < 0) {
            return false;
        }
        if (target < 0 || target >= pointCount || pointCount < 2 || pointCount > MAX_POINTS
                || cot == null || fingerprint == null) {
            throw new IllegalArgumentException("Invalid route navigation snapshot");
        }
        String state = target + "|" + fingerprint;
        if (connection == sentConnection && state.equals(sentFingerprint)) return false;
        JSONObject payload = payload(connection == sentConnection ? "progress" : "start")
                .put("targetPointIndex", target).put("routeCot", cot);
        if (!send(payload, connection, writer)) return false;
        sentConnection = connection;
        sentFingerprint = state;
        return true;
    }

    void stop(boolean arrived) {
        if (navigationSession == null) return;
        stopping = true;
        completed = arrived;
    }

    boolean flushStop(long connection, Writer writer) throws JSONException {
        if (!stopping || navigationSession == null || connection < 0) return false;
        // A disconnected watch discards phone navigation; never stop an unrelated new peer.
        if (connection != sentConnection) {
            clear();
            return false;
        }
        if (!send(payload("stop").put("completed", completed), connection, writer)) return false;
        clear();
        return true;
    }

    private JSONObject payload(String action) throws JSONException {
        return new JSONObject().put("version", 1).put("sessionId", navigationSession)
                .put("sequence", sequence + 1).put("action", action).put("routeUid", routeUid);
    }

    static void validateVertices(List<String> vertices, int pointCount) {
        if (pointCount < 2 || pointCount > MAX_POINTS || vertices.size() != pointCount) {
            throw new IllegalArgumentException("Route export changed vertex count");
        }
        for (String vertex : vertices) {
            String[] coordinates = vertex == null ? new String[0] : vertex.split(",", -1);
            if (coordinates.length < 2) {
                throw new IllegalArgumentException("Invalid route link point");
            }
            double lat = Double.parseDouble(coordinates[0]);
            double lon = Double.parseDouble(coordinates[1]);
            if (!Double.isFinite(lat) || Math.abs(lat) > 90
                    || !Double.isFinite(lon) || Math.abs(lon) > 180) {
                throw new IllegalArgumentException("Invalid route link coordinates");
            }
        }
    }

    private boolean send(JSONObject payload, long connection, Writer writer) throws JSONException {
        String envelope = new JSONObject().put("msg_type", "phone_route_navigation")
                .put("payload", payload).toString();
        if (envelope.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Route navigation exceeds 60 KiB frame bound");
        }
        if (!writer.write(envelope, connection)) return false;
        sequence++;
        return true;
    }

    void clear() {
        routeUid = null;
        navigationSession = null;
        sentConnection = -1;
        sentFingerprint = null;
        stopping = false;
    }
}
