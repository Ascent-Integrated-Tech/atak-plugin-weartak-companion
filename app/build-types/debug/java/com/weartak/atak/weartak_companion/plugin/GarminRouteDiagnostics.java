package com.weartak.atak.weartak_companion.plugin;


import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.routes.Route;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Offline emulator acceptance of native ATAK route observation with an in-memory watch link. */
final class GarminRouteDiagnostics {
    private static final String TAG = "WTK/GarminRouteTest";

    static void run(MapView map) {
        String uid = "SIMULATOR-ROUTE-" + UUID.randomUUID();
        Route route = new Route(map, "SIMULATOR ROUTE", 0xff00ffff, "CP", uid);
        GarminRouteMapSource source = new GarminRouteMapSource(map);
        CapturingLink link = new CapturingLink(uid);
        GarminRouteForwarder forwarder = new GarminRouteForwarder(source,
                () -> Collections.singletonList(link), Runnable::run, () -> true);
        try {
            boolean rejectedDeclaration = false;
            try {
                GarminRouteProtocol.fromCotXml("<!DOCTYPE event SYSTEM 'file:///not-a-route'>"
                        + "<event uid='r' type='b-m-r'><detail>"
                        + "<link point='1,2'/><link point='3,4'/></detail></event>",
                        Collections.emptyMap(), null);
            } catch (IllegalArgumentException expected) {
                rejectedDeclaration = true;
            }
            check(rejectedDeclaration, "Android route parser rejects external declarations");
            route.addPoint(point(35.1, -117.2));
            route.addPoint(point(35.2, -117.3));
            StringBuilder xml = new StringBuilder();
            CotEventFactory.createCotEvent(route).buildXml(xml);
            GarminRouteProtocol.fromCotXml(xml.toString(), Collections.emptyMap(), uid);
            forwarder.start();
            map.getRootGroup().addItem(route);
            check(link.route != null && points(link).size() == 2,
                    "native route addition exports two ordered vertices");
            check(Math.abs(latitude(link, 0) - 35.1) < 0.000001
                            && Math.abs(latitude(link, 1) - 35.2) < 0.000001,
                    "native CoT export preserves vertex order and coordinates");
            route.setPoint(1, point(35.4, -117.5), false);
            check(Math.abs(latitude(link, 1) - 35.4) < 0.000001,
                    "native geometry edit reaches the watch link");
            route.setTitle("SIMULATOR RENAMED");
            check("SIMULATOR RENAMED".equals(link.route.get("name")),
                    "native route rename reaches the watch link");
            map.getRootGroup().removeItem(route);
            check(link.route == null, "native removal removes only the matching watch route");
            forwarder.close();
            int sent = link.sent;
            map.getRootGroup().addItem(route);
            route.setPoint(1, point(35.6, -117.7), false);
            check(link.sent == sent, "closed route source detaches native listeners");
            LogX.i(TAG, "PASS: native route acceptance complete; in-memory link, no physical Garmin");
        } catch (RuntimeException e) {
            LogX.e(TAG, "FAIL: native route acceptance", e);
        } finally {
            forwarder.close();
            map.getRootGroup().removeItem(route);
        }
    }

    private static GeoPointMetaData point(double lat, double lon) {
        return GeoPointMetaData.wrap(new GeoPoint(lat, lon));
    }

    private static List<?> points(CapturingLink link) {
        return (List<?>) link.route.get("points");
    }

    private static double latitude(CapturingLink link, int index) {
        return ((Number) ((Map<?, ?>) points(link).get(index)).get("lat")).doubleValue();
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new IllegalStateException(message);
        LogX.i(TAG, "PASS: " + message);
    }

    private static final class CapturingLink implements GarminEntityForwarder.Link {
        final String uid;
        Map<String, Object> route;
        int sent;

        CapturingLink(String uid) { this.uid = uid; }
        @Override public boolean isCurrent() { return true; }
        @Override public int routeVersion() { return GarminRouteProtocol.VERSION; }
        @Override public boolean send(String type, Map<String, Object> payload) {
            sent++;
            if ("routes_error".equals(type)) LogX.w(TAG, String.valueOf(payload.get("error")));
            if (uid.equals(payload.get("uid"))) {
                if ("route".equals(type)) route = payload;
                if ("route_remove".equals(type)) route = null;
            }
            return true;
        }
    }
}
