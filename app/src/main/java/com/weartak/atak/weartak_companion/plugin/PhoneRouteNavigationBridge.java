package com.weartak.atak.weartak_companion.plugin;

import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.util.Pair;
import android.widget.Toast;

import com.atakmap.android.cot.importer.CotImporterManager;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.routes.Route;
import com.atakmap.android.routes.RouteNavigationManager;
import com.atakmap.android.routes.RouteNavigator;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.comms.CommsMapComponent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Read-only observer: ATAK alone decides objective advancement and arrival. */
final class PhoneRouteNavigationBridge implements RouteNavigator.RouteNavigatorListener,
        RouteNavigationManager.RouteNavigationManagerEventListener {
    private static final String TAG = "WTK/RouteNavigation";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RouteNavigationTransport transport;
    private final RouteNavigator navigator;
    private final PhoneRouteNavigationRelay relay = new PhoneRouteNavigationRelay();
    private final RouteNavigationControl controls = new RouteNavigationControl(new RouteNavigationControl.Port() {
        @Override public String sessionId() { return relay.sessionId(); }
        @Override public String routeUid() { return relay.routeUid(); }
        @Override public void start(JSONObject command) throws Exception { startNative(command); }
        @Override public void stop() {
            if (!navigator.isNavigating() || navigator.getRoute() != route
                    || route == null || !route.getUID().equals(relay.routeUid())) {
                throw new IllegalStateException("Native route changed before watch stop");
            }
            changingNative = true;
            try {
                navigator.stopNavigating();
                endNavigation(false);
                flushStop();
                detach();
            } finally { changingNative = false; }
        }
    });
    private Route route;
    private RouteNavigationManager manager;
    private boolean closed;
    private boolean changingNative;
    private boolean writeRejected;
    private String lastError;
    private final Route.OnRoutePointsChangedListener points = item -> changed();
    private final MapItem.OnMetadataChangedListener metadata = (item, key) -> changed();
    private final Runnable publish = this::publish;
    private final Runnable reconcile = new Runnable() {
        @Override public void run() {
            if (closed) return;
            // Also catches replacement managers (updateNavigation), reconnects and rejected writes.
            refresh();
            main.postDelayed(this, 1500L);
        }
    };

    PhoneRouteNavigationBridge(WearTakBleClient client) {
        this(new RouteNavigationTransport() {
            @Override public long readyConnection() {
                return client.isBondedConnected() ? client.getReadySession() : -1;
            }
            @Override public boolean send(String json, long connection) {
                return client.writeJsonLineToWatch(json, connection);
            }
        });
    }

    PhoneRouteNavigationBridge(RouteNavigationTransport transport) {
        this.transport = transport;
        navigator = RouteNavigator.getInstance();
        if (navigator == null) {
            throw new IllegalStateException("ATAK RouteNavigator unavailable");
        }
        navigator.registerRouteNavigatorListener(this);
        main.post(reconcile);
    }

    boolean handleJson(String json) {
        final JSONObject payload;
        final long connection = readyConnection();
        try {
            JSONObject envelope = new JSONObject(json);
            if (!"watch_route_navigation".equals(envelope.optString("msg_type"))) return false;
            payload = envelope.getJSONObject("payload");
        } catch (JSONException e) {
            report(e);
            return true;
        }
        main.post(() -> {
            if (closed || connection < 0 || connection != readyConnection()) {
                LogX.w(TAG, "Ignoring navigation command from an ended connection");
                return;
            }
            try {
                JSONObject result = controls.receive(payload, connection);
                if (!result.optBoolean("ok")) {
                    String error = result.optString("error");
                    LogX.w(TAG, "Watch route request rejected: " + error);
                    Toast.makeText(navigator.getMapView().getContext(), error, Toast.LENGTH_SHORT).show();
                }
                if (!transport.send(new JSONObject().put("msg_type", "route_navigation_ack")
                        .put("payload", result).toString(), connection)) {
                    LogX.w(TAG, "Navigation acknowledgement write rejected");
                }
            } catch (JSONException | RuntimeException e) {
                report(e);
            }
        });
        return true;
    }

    private void startNative(JSONObject command) throws Exception {
        String uid = command.getString("routeUid");
        String id = command.getString("sessionId");
        int index = command.getInt("targetPointIndex");
        MapView map = navigator.getMapView();
        MapItem item = map.getRootGroup().deepFindUID(uid);
        RouteNavigationCot proposed = command.has("routeCot")
                ? RouteNavigationCot.read(command.getString("routeCot"), uid) : null;
        if (proposed != null && index >= proposed.vertices.size()) {
            throw new IllegalArgumentException("Route objective is outside watch geometry");
        }
        if (item == null) {
            if (!transport.canImport() || proposed == null) {
                throw new IllegalArgumentException("Route is not present in ATAK");
            }
            CotEvent event = CotEvent.parse(command.getString("routeCot"));
            if (event == null || !event.isValid()) throw new IllegalArgumentException("Invalid route event");
            for (CotDetail link : event.getDetail().getChildrenByName("link")) {
                String pointUid = link.getAttribute("uid");
                if (link.getAttribute("point") != null && pointUid != null
                        && map.getRootGroup().deepFindUID(pointUid) != null) {
                    throw new IllegalArgumentException("Watch route checkpoint UID belongs to an existing map item");
                }
            }
            CotImporterManager importer = CotImporterManager.getInstance();
            Bundle extras = new Bundle();
            extras.putBoolean(PhoneMarkerMapRelay.WATCH_IMPORT, true);
            extras.putBoolean("visible", true);
            if (importer == null || importer.importData(event, extras) != CommsMapComponent.ImportResult.SUCCESS) {
                throw new IllegalStateException("ATAK could not import watch route");
            }
            item = map.getRootGroup().deepFindUID(uid);
        }
        if (!(item instanceof Route)) throw new IllegalArgumentException("UID is not an ATAK route");
        Route requested = (Route) item;
        if (index >= requested.getNumPoints() || requested.getMarker(index) == null) {
            throw new IllegalArgumentException("Requested objective is not an ATAK checkpoint");
        }
        CotEvent actual = CotEventFactory.createCotEvent(requested);
        if (actual == null) throw new IllegalArgumentException("Native route cannot be exported");
        StringBuilder xml = new StringBuilder();
        actual.buildXml(xml);
        if (proposed != null) {
            if (!proposed.sameGeometry(RouteNavigationCot.read(xml.toString(), uid))) {
                throw new IllegalArgumentException("Watch route geometry does not match ATAK");
            }
        }
        transport.validateStart(command, uid, xml.toString());
        changingNative = true;
        try {
            endNavigation(false);
            flushStop();
            detach();
            if (!navigator.startNavigating(requested, index) || !navigator.isNavigating()
                    || navigator.getRoute() != requested) {
                throw new IllegalStateException("ATAK could not start route navigation");
            }
            route = requested;
            relay.start(uid, id);
            route.addOnRoutePointsChangedListener(points);
            MetadataObservers.add(route, metadata);
            writeRejected = false;
            refresh();
            if (writeRejected || !id.equals(relay.sessionId())) {
                navigator.stopNavigating();
                endNavigation(false);
                flushStop();
                detach();
                throw new IllegalStateException("Initial route snapshot was rejected");
            }
            main.post(publish);
        } finally { changingNative = false; }
    }

    void close() {
        closed = true;
        navigator.unregisterRouteNavigatorListener(this);
        main.removeCallbacksAndMessages(null);
        endNavigation(false);
        flushStop();
        detach();
        relay.clear();
    }

    private void changed() {
        main.removeCallbacks(publish);
        if (!closed) main.post(publish);
    }

    private void refresh() {
        if (!navigator.isNavigating() || navigator.getRoute() == null) {
            if (route != null) {
                endNavigation(false);
                detach();
            }
            flushStop();
            return;
        }
        Route current = navigator.getRoute();
        if (route != current) {
            endNavigation(false);
            flushStop();
            detach();
            route = current;
            relay.start(route.getUID());
            route.addOnRoutePointsChangedListener(points);
            MetadataObservers.add(route, metadata);
        }
        RouteNavigationManager currentManager = navigator.getNavManager();
        if (manager != currentManager) {
            if (manager != null) manager.unregisterListener(this);
            manager = currentManager;
            if (manager != null) manager.registerListener(this);
        }
        publish();
    }

    private void publish() {
        if (closed || route == null || manager == null || !navigator.isNavigating()
                || navigator.getRoute() != route || navigator.getNavManager() != manager) return;
        if (route.getGroup() == null) {
            endNavigation(false);
            flushStop();
            return;
        }
        long connection = readyConnection();
        if (connection < 0) return;
        try {
            if (!changingNative && arrived(manager, route)) endNavigation(true);
            flushStop();
            Pair<Integer, PointMapItem> objective = manager.getCurrentObjective();
            if (objective == null || objective.first == null || objective.second == null) {
                throw new IllegalStateException("ATAK navigation objective unavailable");
            }
            CotEvent event = CotEventFactory.createCotEvent(route);
            if (event == null || !route.getUID().equals(event.getUID())
                    || event.getDetail() == null || event.getType() == null
                    || !event.getType().startsWith("b-m-r")) {
                throw new IllegalStateException("ATAK route CoT unavailable");
            }
            List<String> vertices = new ArrayList<>();
            for (CotDetail link : event.getDetail().getChildrenByName("link")) {
                if (link.getAttribute("point") != null) vertices.add(link.getAttribute("point"));
            }
            PhoneRouteNavigationRelay.validateVertices(vertices, route.getNumPoints());
            PointMapItem target = route.getPointMapItem(objective.first);
            if (target == null || !target.getUID().equals(objective.second.getUID())) {
                throw new IllegalStateException("ATAK objective changed during route export");
            }
            StringBuilder xml = new StringBuilder();
            event.buildXml(xml);
            String fingerprint = event.getType() + "|" + event.getDetail();
            relay.update(route.getUID(), objective.first, route.getNumPoints(), xml.toString(),
                    fingerprint, connection, this::write);
            lastError = null;
        } catch (JSONException | RuntimeException e) {
            report(e);
            if (e instanceof IllegalArgumentException) {
                endNavigation(false);
                flushStop();
            }
        }
    }

    private long readyConnection() {
        return transport.readyConnection();
    }

    private boolean write(String json, long connection) {
        boolean accepted = transport.send(json, connection);
        if (!accepted) {
            writeRejected = true;
            LogX.w(TAG, "Navigation write rejected; retrying current snapshot");
        }
        return accepted;
    }

    private void flushStop() {
        try {
            relay.flushStop(readyConnection(), this::write);
        } catch (JSONException | RuntimeException e) {
            report(e);
        }
    }

    private void endNavigation(boolean completed) {
        controls.ended(relay.sessionId());
        relay.stop(completed);
    }

    private void report(Exception e) {
        if (!String.valueOf(e.getMessage()).equals(lastError)) {
            LogX.e(TAG, "Phone route navigation relay failed", e);
            Toast.makeText(navigator.getMapView().getContext(),
                    "Route sync failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            lastError = String.valueOf(e.getMessage());
        }
    }

    private static boolean arrived(RouteNavigationManager manager, Route route) {
        return manager != null && route != null && route.getNumPoints() > 1
                && manager.hasArrivedAtPoint(route.getNumPoints() - 1);
    }

    private void detach() {
        if (manager != null) manager.unregisterListener(this);
        if (route != null) {
            route.removeOnRoutePointsChangedListener(points);
            MetadataObservers.remove(route, metadata);
        }
        route = null;
        manager = null;
    }

    @Override public void onNavigationStarting(RouteNavigator nav) { }
    @Override public void onNavigationStarted(RouteNavigator nav, Route started) {
        if (changingNative) return;
        main.post(() -> { if (!closed) refresh(); });
    }
    @Override public void onNavigationStopping(RouteNavigator nav, Route stopped) {
        if (changingNative) return;
        // Capture arrival before ATAK disposes its navigation engine.
        boolean completed = arrived(nav.getNavManager(), stopped);
        main.post(() -> {
            if (closed || route != stopped) return;
            endNavigation(completed);
            flushStop();
            detach();
        });
    }
    @Override public void onNavigationStopped(RouteNavigator nav) { }
    @Override public void onGpsStatusChanged(RouteNavigationManager nav, boolean available) { }
    @Override public void onLocationChanged(RouteNavigationManager nav, GeoPoint old, GeoPoint next) { }
    @Override public void onNavigationObjectiveChanged(RouteNavigationManager nav,
            PointMapItem objective, boolean fromRoute) { changed(); }
    @Override public void onOffRoute(RouteNavigationManager nav) { }
    @Override public void onReturnedToRoute(RouteNavigationManager nav) { }
    @Override public void onTriggerEntered(RouteNavigationManager nav, PointMapItem point, int trigger) { }
    @Override public void onArrivedAtPoint(RouteNavigationManager nav, PointMapItem point) {
        boolean terminal = nav.getRoute().getIndexOfPoint(point) == nav.getRoute().getNumPoints() - 1;
        main.post(() -> {
            if (closed || manager != nav) return;
            if (terminal) {
                endNavigation(true);
                flushStop();
            } else {
                changed();
            }
        });
    }
    @Override public void onDepartedPoint(RouteNavigationManager nav, PointMapItem point) { changed(); }
}
