package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.UUID;

/** Bounded, replay-safe watch requests. All methods run on the native navigation thread. */
final class RouteNavigationControl {
    interface Port {
        String sessionId();
        String routeUid();
        void start(JSONObject command) throws Exception;
        void stop() throws Exception;
    }

    private final Port port;
    private final LinkedHashMap<String, Receipt> receipts = new LinkedHashMap<>();
    private final LinkedHashSet<String> retired = new LinkedHashSet<>();
    private long connection = -1;

    private static final class Receipt {
        final String command;
        final JSONObject result;
        Receipt(String command, JSONObject result) {
            this.command = command;
            this.result = result;
        }
    }

    RouteNavigationControl(Port port) { this.port = port; }

    static boolean validUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException e) { return false; }
    }

    void ended(String id) {
        if (id == null) return;
        retired.add(id);
        while (retired.size() > 128) retired.remove(retired.iterator().next());
    }

    JSONObject receive(JSONObject command, long readyConnection) throws JSONException {
        if (readyConnection < 0) throw new IllegalArgumentException("Watch connection is not ready");
        if (connection != readyConnection) {
            connection = readyConnection;
            receipts.clear();
        }
        String request = command.optString("requestId", "");
        String id = command.optString("sessionId", "");
        String uid = command.optString("routeUid", "");
        String action = command.optString("action", "");
        String canonical = command.toString();
        JSONObject result = new JSONObject().put("requestId", request).put("sessionId", id);
        if (!validUuid(request) || !validUuid(id) || uid.trim().isEmpty() || uid.length() > 256
                || !(command.opt("version") instanceof Number)
                || ((Number) command.get("version")).doubleValue() != 1
                || (!"start".equals(action) && !"stop".equals(action))
                || canonical.getBytes(StandardCharsets.UTF_8).length
                    > PhoneRouteNavigationRelay.MAX_FRAME_BYTES) {
            return result.put("ok", false).put("error", "Invalid route navigation command");
        }
        Receipt receipt = receipts.get(request);
        if (receipt != null) {
            if (!receipt.command.equals(canonical)) {
                return result.put("ok", false).put("error", "Request ID reused with different command");
            }
            return new JSONObject(receipt.result.toString());
        }
        try {
            if ("start".equals(action)) {
                Object index = command.opt("targetPointIndex");
                if (!(index instanceof Number) || !Double.isFinite(((Number) index).doubleValue())
                        || ((Number) index).doubleValue() != ((Number) index).intValue()
                        || ((Number) index).intValue() < 0
                        || ((Number) index).intValue() >= PhoneRouteNavigationRelay.MAX_POINTS) {
                    throw new IllegalArgumentException("Invalid route objective index");
                }
                if (retired.contains(id)) throw new IllegalArgumentException("Navigation session already ended");
                if (id.equals(port.sessionId())) {
                    if (!uid.equals(port.routeUid())) throw new IllegalArgumentException("Session route mismatch");
                } else {
                    port.start(command);
                    if (!id.equals(port.sessionId()) || !uid.equals(port.routeUid())) {
                        throw new IllegalStateException("ATAK did not accept route navigation");
                    }
                }
            } else if (retired.contains(id)) {
                // A delayed start cannot resurrect a stop-before-start session.
            } else if (id.equals(port.sessionId()) && uid.equals(port.routeUid())) {
                port.stop();
                ended(id);
            } else {
                if (!id.equals(port.sessionId())) ended(id);
                throw new IllegalArgumentException("Stale or unrelated navigation stop");
            }
            result.put("ok", true);
        } catch (Exception e) {
            result.put("ok", false).put("error", e.getMessage() == null
                    ? "Native route navigation failed" : e.getMessage());
        }
        receipts.put(request, new Receipt(canonical, new JSONObject(result.toString())));
        while (receipts.size() > 64) receipts.remove(receipts.keySet().iterator().next());
        return result;
    }
}
