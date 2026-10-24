package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class GarminRouteNavigationTransportTest {
    private static final String COT = "<event uid='r' type='b-m-r'><detail>"
            + "<link point='38,-77'/><link point='38.1,-77.1'/></detail></event>";

    private static final class Link implements GarminEntityForwarder.Link {
        String session = "relay-1";
        boolean current = true;
        boolean accept = true;
        int capability = 1;
        final List<String> types = new ArrayList<>();
        final List<Map<String, Object>> frames = new ArrayList<>();
        @Override public boolean isCurrent() { return current; }
        @Override public int routeVersion() { return 1; }
        @Override public int routeNavigationVersion() { return capability; }
        @Override public String relaySession() { return session; }
        @Override public boolean send(String type, Map<String, Object> payload) {
            types.add(type);
            frames.add(new HashMap<>(payload));
            return accept;
        }
    }

    private static GarminRouteForwarder forwarder(Link link) {
        GarminRouteProtocol.Route route = GarminRouteProtocol.route("server", "r", "Route", null, null,
                Arrays.asList(new GarminRouteProtocol.Point(38, -77, null),
                        new GarminRouteProtocol.Point(38.1, -77.1, null)));
        GarminRouteForwarder.Source source = new GarminRouteForwarder.Source() {
            @Override public void subscribe(Consumer<GarminRouteProtocol.Route> changed,
                    Consumer<String> removed, Consumer<String> rejected) { }
            @Override public void unsubscribe() { }
            @Override public List<GarminRouteProtocol.Route> snapshot() { return Collections.singletonList(route); }
        };
        GarminRouteForwarder forwarder = new GarminRouteForwarder(source,
                () -> Collections.singletonList(link), Runnable::run, () -> true);
        forwarder.start();
        link.types.clear();
        link.frames.clear();
        return forwarder;
    }

    private String navigation(String action) throws Exception {
        return new JSONObject().put("msg_type", "phone_route_navigation")
                .put("payload", new JSONObject().put("version", 1).put("action", action)
                        .put("routeUid", "r").put("sessionId", "nav-session").put("sequence", 1)
                        .put("targetPointIndex", 1).put("routeCot", COT).put("completed", false)).toString();
    }

    @Test public void geometryPrecedesStateAndNoXmlCrossesGarminTransport() throws Exception {
        Link link = new Link();
        GarminRouteNavigationTransport transport = new GarminRouteNavigationTransport(() -> link, forwarder(link));
        long connection = transport.readyConnection();
        assertTrue(transport.send(navigation("start"), connection));
        assertEquals(Arrays.asList("route", "phone_route_navigation"), link.types);
        Map<String, Object> state = link.frames.get(1);
        assertEquals("server", state.get("serverId"));
        assertEquals("r", state.get("routeUid"));
        assertEquals("relay-1", state.get("relaySession"));
        assertFalse(state.containsKey("routeCot"));
        assertTrue(transport.send(navigation("stop"), connection));
        assertEquals("server", link.frames.get(2).get("serverId"));
    }

    @Test public void acknowledgementIsRelayScopedAndCapabilitiesFenceOldPeers() throws Exception {
        Link link = new Link();
        GarminRouteNavigationTransport transport = new GarminRouteNavigationTransport(() -> link, forwarder(link));
        long connection = transport.readyConnection();
        String ack = new JSONObject().put("msg_type", "route_navigation_ack")
                .put("payload", new JSONObject().put("requestId", "request").put("sessionId", "id")
                        .put("ok", false).put("error", "Rejected")).toString();
        assertTrue(transport.send(ack, connection));
        assertEquals("relay-1", link.frames.get(0).get("relaySession"));
        link.session = "relay-2";
        assertFalse(transport.send(ack, connection));
        assertTrue(transport.readyConnection() > connection);
        link.capability = 0;
        assertEquals(-1, transport.readyConnection());
        assertFalse(transport.send(ack, connection));
    }

    @Test public void sdkRejectionNeverSendsStateWithoutGeometryAndServerMustMatch() throws Exception {
        Link link = new Link();
        GarminRouteNavigationTransport transport = new GarminRouteNavigationTransport(() -> link, forwarder(link));
        long connection = transport.readyConnection();
        assertThrows(IllegalArgumentException.class, () ->
                transport.validateStart(new JSONObject().put("serverId", "wrong"), "r", COT));
        transport.validateStart(new JSONObject().put("serverId", "server"), "r", COT);
        link.accept = false;
        assertFalse(transport.send(navigation("start"), connection));
        assertEquals(Collections.singletonList("route"), link.types);
        assertFalse(transport.canImport());
    }

    @Test public void incomingControlsMustMatchTheNegotiatedExclusiveRelaySession() throws Exception {
        Link link = new Link();
        GarminRouteNavigationTransport transport = new GarminRouteNavigationTransport(() -> link, forwarder(link));
        JSONObject request = new JSONObject().put("msgType", "watch_route_navigation")
                .put("payload", new JSONObject().put("relaySession", link.session).put("version", 1));
        assertEquals("watch_route_navigation", new JSONObject(transport.inbound(request, link)).getString("msg_type"));
        assertThrows(IllegalArgumentException.class, () -> transport.inbound(request, new Link()));
        request.getJSONObject("payload").put("relaySession", "old");
        assertThrows(IllegalArgumentException.class, () -> transport.inbound(request, link));
    }
}
