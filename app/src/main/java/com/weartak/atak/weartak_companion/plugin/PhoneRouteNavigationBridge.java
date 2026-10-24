package com.weartak.atak.weartak_companion.plugin;

import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.routes.Route;
import com.atakmap.android.routes.RouteNavigationManager;
import com.atakmap.android.routes.RouteNavigator;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Read-only observer: ATAK alone decides objective advancement and arrival. */
final class PhoneRouteNavigationBridge implements RouteNavigator.RouteNavigatorListener,
        RouteNavigationManager.RouteNavigationManagerEventListener {
    private static final String TAG = "WTK/RouteNavigation";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WearTakBleClient client;
    private final RouteNavigator navigator;
    private final PhoneRouteNavigationRelay relay = new PhoneRouteNavigationRelay();
    private Route route;
    private RouteNavigationManager manager;
    private boolean closed;
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
        this.client = client;
        navigator = RouteNavigator.getInstance();
        if (navigator == null) {
            throw new IllegalStateException("ATAK RouteNavigator unavailable");
        }
        navigator.registerRouteNavigatorListener(this);
        main.post(reconcile);
    }

    void close() {
        closed = true;
        navigator.unregisterRouteNavigatorListener(this);
        main.removeCallbacksAndMessages(null);
        relay.stop(false);
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
                relay.stop(false);
                detach();
            }
            flushStop();
            return;
        }
        Route current = navigator.getRoute();
        if (route != current) {
            relay.stop(false);
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
            relay.stop(false);
            flushStop();
            return;
        }
        long connection = readyConnection();
        if (connection < 0) return;
        try {
            if (arrived(manager, route)) relay.stop(true);
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
        }
    }

    private long readyConnection() {
        return client.isBondedConnected() ? client.getReadySession() : -1;
    }

    private boolean write(String json, long connection) {
        boolean accepted = client.writeJsonLineToWatch(json, connection);
        if (!accepted) LogX.w(TAG, "Navigation write rejected; retrying current snapshot");
        return accepted;
    }

    private void flushStop() {
        try {
            relay.flushStop(readyConnection(), this::write);
        } catch (JSONException | RuntimeException e) {
            report(e);
        }
    }

    private void report(Exception e) {
        if (!String.valueOf(e.getMessage()).equals(lastError)) {
            LogX.e(TAG, "Phone route navigation relay failed", e);
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
        main.post(() -> { if (!closed) refresh(); });
    }
    @Override public void onNavigationStopping(RouteNavigator nav, Route stopped) {
        // Capture arrival before ATAK disposes its navigation engine.
        boolean completed = arrived(nav.getNavManager(), stopped);
        main.post(() -> {
            if (closed || route != stopped) return;
            relay.stop(completed);
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
                relay.stop(true);
                flushStop();
            } else {
                changed();
            }
        });
    }
    @Override public void onDepartedPoint(RouteNavigationManager nav, PointMapItem point) { changed(); }
}
