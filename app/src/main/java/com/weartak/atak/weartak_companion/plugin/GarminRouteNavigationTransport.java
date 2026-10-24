package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

/** Uses the existing capacity-accounted Garmin route cache, never XML on the watch. */
final class GarminRouteNavigationTransport implements RouteNavigationTransport {
    private final Supplier<GarminEntityForwarder.Link> target;
    private final GarminRouteForwarder routes;
    private GarminEntityForwarder.Link previous;
    private String session;
    private long generation;
    private String navigationSession;
    private String navigationServer;

    GarminRouteNavigationTransport(Supplier<GarminEntityForwarder.Link> target,
            GarminRouteForwarder routes) {
        this.target = target;
        this.routes = routes;
    }

    private GarminEntityForwarder.Link link() {
        GarminEntityForwarder.Link link = target.get();
        return link != null && link.isCurrent() && link.routeVersion() == 1
                && link.routeNavigationVersion() == 1 && !link.relaySession().isEmpty() ? link : null;
    }

    @Override public long readyConnection() {
        GarminEntityForwarder.Link link = link();
        if (link == null) {
            previous = null;
            return -1;
        }
        if (link != previous || !link.relaySession().equals(session)) {
            previous = link;
            session = link.relaySession();
            generation++;
        }
        return generation;
    }

    String inbound(JSONObject envelope, GarminEntityForwarder.Link source) throws org.json.JSONException {
        JSONObject payload = envelope.getJSONObject("payload");
        if (source == null || source != link()
                || !source.relaySession().equals(payload.optString("relaySession"))) {
            throw new IllegalArgumentException("Navigation command from stale Garmin session");
        }
        return new JSONObject().put("msg_type", "watch_route_navigation")
                .put("payload", payload).toString();
    }

    @Override public boolean canImport() { return false; }

    @Override public void validateStart(JSONObject command, String uid, String cot) throws Exception {
        if (!(command.opt("serverId") instanceof String)) {
            throw new IllegalArgumentException("Missing Garmin route server");
        }
        routes.navigationRoute(link(), uid, command.getString("serverId"), cot);
    }

    @Override public boolean send(String json, long connection) {
        if (connection < 0 || connection != readyConnection()) return false;
        GarminEntityForwarder.Link link = link();
        if (link == null) return false;
        try {
            JSONObject envelope = new JSONObject(json);
            String type = envelope.getString("msg_type");
            JSONObject payload = envelope.getJSONObject("payload");
            if ("phone_route_navigation".equals(type) && !"stop".equals(payload.getString("action"))) {
                GarminRouteProtocol.Route route = routes.navigationRoute(link,
                        payload.getString("routeUid"), null, payload.getString("routeCot"));
                if (!link.sendForSession("route", route.payload(), session)) return false;
                payload.put("serverId", route.serverId);
                payload.remove("routeCot");
            } else if ("phone_route_navigation".equals(type)
                    && payload.getString("sessionId").equals(navigationSession)) {
                payload.put("serverId", navigationServer);
            }
            payload.remove("routeCot");
            payload.put("relaySession", session);
            Map<String, Object> values = new HashMap<>();
            Iterator<String> keys = payload.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                values.put(key, payload.get(key));
            }
            boolean sent = link.sendForSession(type, values, session);
            if (sent && "phone_route_navigation".equals(type)) {
                navigationSession = payload.getString("sessionId");
                navigationServer = payload.optString("serverId");
            }
            return sent;
        } catch (Exception e) {
            throw new IllegalArgumentException("Garmin navigation message rejected: " + e.getMessage(), e);
        }
    }
}
