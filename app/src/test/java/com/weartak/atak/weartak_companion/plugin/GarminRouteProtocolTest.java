package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GarminRouteProtocolTest {
    @Test public void methodAndCheckpointMetadataPreserveRawControlVertices() {
        String xml = "<event uid='r' type='b-m-r'><detail><link_attr method='Walking'/>"
                + "<link point='0,0' type='b-m-p-w'/><link point='1,1' type='b-m-p-c'/>"
                + "<link point='2,2'/><link point='3,3' type='b-m-p-w'/></detail></event>";
        GarminRouteProtocol.Route route = GarminRouteProtocol.fromCotXml(xml, null, "r");
        assertEquals("Walking", route.payload().get("method"));
        assertEquals(4, route.points.size());
        assertEquals("b-m-p-w", route.points.get(0).payload().get("type"));
        assertEquals("b-m-p-c", route.points.get(1).payload().get("type"));
        assertFalse(route.points.get(2).payload().containsKey("type"));
        assertThrows(IllegalArgumentException.class, () ->
                GarminRouteProtocol.fromCotXml(xml.replace("Walking", "Unknown"), null, "r"));
    }

    @Test public void normalizesAndBuildsAtomicVersionedPayload() {
        GarminRouteProtocol.Route route = GarminRouteProtocol.route("server", "route-1",
                "Patrol", "Mission", "sender", Arrays.asList(
                        new GarminRouteProtocol.Point(35.1, -117.2, "Start"),
                        new GarminRouteProtocol.Point(35.2, -117.3, null)));
        assertEquals("server\u0000route-1", route.key());
        assertEquals(1, route.payload().get("routeVersion"));
        assertEquals(2, ((List<?>) route.payload().get("points")).size());
        assertTrue(GarminRouteProtocol.fitsEnvelope("route", route.payload()));
        assertEquals(1, GarminRouteProtocol.remove("server", "route-1").get("routeVersion"));
        assertEquals(1, GarminRouteProtocol.reset().get("routeVersion"));
    }

    @Test public void enforcesUtf8ByteBoundsRatherThanCharacterBounds() {
        rejects(() -> GarminRouteProtocol.route(repeat("é", 33), "u", "n", null, null,
                points(2)));
        rejects(() -> GarminRouteProtocol.route("s", repeat("🚀", 33), "n", null, null,
                points(2)));
        rejects(() -> GarminRouteProtocol.route("s", "u", repeat("é", 33), null, null,
                points(2)));
        rejects(() -> GarminRouteProtocol.route("s", "u", "n", repeat("é", 65), null,
                points(2)));
        rejects(() -> GarminRouteProtocol.route("s", "u", "n", null, repeat("é", 65),
                points(2)));
    }

    @Test public void enforcesPointCountCoordinatesAndAtomicEnvelopeBudget() {
        rejects(() -> GarminRouteProtocol.route("s", "u", "n", null, null, points(0)));
        rejects(() -> GarminRouteProtocol.route("s", "u", "n", null, null, points(1)));
        rejects(() -> GarminRouteProtocol.route("s", "u", "n", null, null, points(65)));
        rejects(() -> GarminRouteProtocol.route("s", "u", "n", null, null,
                Arrays.asList(new GarminRouteProtocol.Point(90.01, 0, null),
                        new GarminRouteProtocol.Point(1, 1, null))));
        rejects(() -> GarminRouteProtocol.route("s", "u", "n", null, null,
                Arrays.asList(new GarminRouteProtocol.Point(0, Double.NaN, null),
                        new GarminRouteProtocol.Point(1, 1, null))));
        assertEquals(2, GarminRouteProtocol.route("s", "zero", "Zero", null, null,
                points(2)).points.size());

        List<GarminRouteProtocol.Point> vertices = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            vertices.add(new GarminRouteProtocol.Point(i, i, repeat("x", 64)));
        }
        rejects(() -> GarminRouteProtocol.route("s", "u", "n", null, null, vertices));
    }

    @Test public void parsesNormalizedRouteCotWithoutExposingRawXml() {
        String xml = "<event version='2.0' uid='r-1' type='b-m-r' time='now'>"
                + "<point lat='0' lon='0'/>"
                + "<detail><contact callsign='Route Name'/>"
                + "<__server uid='server-1'/><__mission name='Mission A'/>"
                + "<link uid='sender-1' relation='p-p'/>"
                + "<route><point lat='35.1' lon='-117.2' name='Start'/>"
                + "<point lat='35.2' lon='-117.3'/></route></detail></event>";
        GarminRouteProtocol.Route route = GarminRouteProtocol.fromCotXml(xml,
                Collections.emptyMap(), null);
        assertEquals("server-1", route.serverId);
        assertEquals("r-1", route.uid);
        assertEquals("Route Name", route.name);
        assertEquals("Mission A", route.missionName);
        assertEquals("sender-1", route.senderUid);
        assertEquals("Start", route.points.get(0).name);
        assertEquals(2, route.points.size());
    }

    @Test public void rejectsUnsupportedOrUnsafeRouteCot() {
        rejects(() -> GarminRouteProtocol.fromCotXml(
                "<!DOCTYPE event [<!ENTITY x SYSTEM 'file:///secret'>]>"
                        + "<event uid='r' type='b-m-r'><detail><route><point lat='1' lon='2'/>"
                        + "</route></detail></event>", Collections.emptyMap(), null));
        rejects(() -> GarminRouteProtocol.fromCotXml(
                "<event uid='x' type='a-f-G'><detail/></event>",
                Collections.emptyMap(), null));
        rejects(() -> GarminRouteProtocol.fromCotXml(
                "<event uid='x' type='b-m-r'><detail><route><point lat='91' lon='0'/>"
                        + "</route></detail></event>", Collections.emptyMap(), null));
    }

    @Test public void parsesOrderedAtakLinkVerticesAndSourceMetadata() {
        String xml = "<event uid='same' type='b-m-r'><detail><contact callsign='Patrol'/>"
                + "<link uid='v1' type='b-m-p-w' point='35.1,-117.2,10' callsign='Start'/>"
                + "<link uid='v2' type='b-m-p-c' point='35.2,-117.3,20'/>"
                + "</detail></event>";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("serverId", "server-b");
        GarminRouteProtocol.Route route = GarminRouteProtocol.fromCotXml(xml, metadata, null);
        assertEquals("server-b", route.serverId);
        assertEquals(2, route.points.size());
        assertEquals("Start", route.points.get(0).name);
        assertEquals(35.2, route.points.get(1).lat, 0.000001);
        assertEquals(-117.3, route.points.get(1).lon, 0.000001);
    }

    @Test public void rejectsInternalEntitiesAndExternalDtdBeforeParsing() {
        rejects(() -> GarminRouteProtocol.fromCotXml(
                "<!DOCTYPE event [<!ENTITY name 'Expanded'>]>"
                        + "<event uid='r' type='b-m-r'><detail><contact callsign='&name;'/>"
                        + "<link point='1,2'/><link point='3,4'/></detail></event>",
                Collections.emptyMap(), null));
        rejects(() -> GarminRouteProtocol.fromCotXml(
                "<!DOCTYPE event SYSTEM 'https://invalid.example/route.dtd'>"
                        + "<event uid='r' type='b-m-r'><detail>"
                        + "<link point='1,2'/><link point='3,4'/></detail></event>",
                Collections.emptyMap(), null));
    }

    private static List<GarminRouteProtocol.Point> points(int count) {
        List<GarminRouteProtocol.Point> points = new ArrayList<>();
        for (int i = 0; i < count; i++) points.add(new GarminRouteProtocol.Point(i, i, null));
        return points;
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            fail("Expected invalid route to be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
