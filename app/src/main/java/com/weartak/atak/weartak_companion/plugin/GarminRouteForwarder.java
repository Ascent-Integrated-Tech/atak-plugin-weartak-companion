package com.weartak.atak.weartak_companion.plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded per-watch route snapshots and live updates over the Garmin Connect IQ link. */
final class GarminRouteForwarder {
    interface Source {
        void subscribe(Consumer<GarminRouteProtocol.Route> changed, Consumer<String> removed,
                Consumer<String> rejected);
        void unsubscribe();
        List<GarminRouteProtocol.Route> snapshot();
    }

    private final Source source;
    private final Supplier<List<GarminEntityForwarder.Link>> targets;
    private final Consumer<Runnable> schedule;
    private final java.util.function.BooleanSupplier current;
    private final Map<GarminEntityForwarder.Link, Map<String, GarminRouteProtocol.Route>> published =
            new HashMap<>();
    private final Set<GarminEntityForwarder.Link> capacityReported = new HashSet<>();
    private boolean reconciliationQueued;
    private boolean started;
    private boolean closed;

    GarminRouteForwarder(Source source, Supplier<List<GarminEntityForwarder.Link>> targets,
            Consumer<Runnable> schedule, java.util.function.BooleanSupplier current) {
        this.source = source;
        this.targets = targets;
        this.schedule = schedule;
        this.current = current;
    }

    synchronized void start() {
        if (started || closed || !current.getAsBoolean()) return;
        started = true;
        source.subscribe(this::changed, this::removed, this::rejected);
        refreshLinks();
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        if (started) source.unsubscribe();
        started = false;
        published.clear();
        capacityReported.clear();
        reconciliationQueued = false;
    }

    synchronized void refreshLinks() {
        if (!active()) return;
        List<GarminEntityForwarder.Link> links = targets.get();
        published.keySet().removeIf(link -> !link.isCurrent() || !links.contains(link));
        capacityReported.retainAll(published.keySet());
        List<GarminRouteProtocol.Route> routes = source.snapshot();
        if (routes == null) return;
        for (GarminEntityForwarder.Link link : links) {
            if (!link.isCurrent()
                    || link.routeVersion() != GarminRouteProtocol.VERSION) continue;
            if (!published.containsKey(link) && !reset(link)) continue;
            reconcile(link, routes);
        }
    }

    synchronized void refreshLink(GarminEntityForwarder.Link link) {
        if (!active() || link == null || !link.isCurrent()) return;
        if (link.routeVersion() != GarminRouteProtocol.VERSION) return;
        if (reset(link)) {
            List<GarminRouteProtocol.Route> routes = source.snapshot();
            if (routes != null) reconcile(link, routes);
        }
    }

    private boolean reset(GarminEntityForwarder.Link link) {
        capacityReported.remove(link);
        if (!link.send("routes_reset", GarminRouteProtocol.reset())) {
            published.remove(link);
            return false;
        }
        published.put(link, new LinkedHashMap<>());
        return true;
    }

    private void reconcile(GarminEntityForwarder.Link link,
            List<GarminRouteProtocol.Route> routes) {
        Map<String, GarminRouteProtocol.Route> cache = published.get(link);
        Map<String, GarminRouteProtocol.Route> available = new TreeMap<>();
        for (GarminRouteProtocol.Route route : routes) {
            if (route != null) available.put(route.key(), route);
        }
        Map<String, GarminRouteProtocol.Route> desired = new LinkedHashMap<>();
        int vertices = 0;
        for (GarminRouteProtocol.Route route : available.values()) {
            GarminRouteProtocol.Route previous = cache.get(route.key());
            if (previous != null) {
                desired.put(route.key(), previous);
                vertices += previous.points.size();
            }
        }
        boolean omitted = false;
        // Apply shrinking updates before growth, preserving old geometry on capacity rejection.
        List<GarminRouteProtocol.Route> ordered = new ArrayList<>(available.values());
        ordered.sort(Comparator.comparingInt(route -> {
            GarminRouteProtocol.Route previous = desired.get(route.key());
            return previous == null ? Integer.MAX_VALUE : route.points.size() - previous.points.size();
        }));
        for (GarminRouteProtocol.Route route : ordered) {
            GarminRouteProtocol.Route previous = desired.get(route.key());
            int nextTotal = vertices + route.points.size()
                    - (previous == null ? 0 : previous.points.size());
            if ((previous == null && desired.size() >= GarminRouteProtocol.MAX_ROUTES)
                    || nextTotal > GarminRouteProtocol.MAX_TOTAL_POINTS) {
                omitted = true;
                continue;
            }
            desired.put(route.key(), route);
            vertices = nextTotal;
        }
        for (GarminRouteProtocol.Route previous : new ArrayList<>(cache.values())) {
            if (!desired.containsKey(previous.key())
                    && link.send("route_remove",
                            GarminRouteProtocol.remove(previous.serverId, previous.uid))) {
                cache.remove(previous.key());
            }
        }
        for (GarminRouteProtocol.Route route : ordered) {
            GarminRouteProtocol.Route next = desired.get(route.key());
            if (next == null) continue;
            GarminRouteProtocol.Route previous = cache.get(next.key());
            if (previous != null && previous.payload().equals(next.payload())) continue;
            int total = next.points.size();
            for (GarminRouteProtocol.Route existing : cache.values()) {
                if (!existing.key().equals(next.key())) total += existing.points.size();
            }
            if ((previous == null && cache.size() >= GarminRouteProtocol.MAX_ROUTES)
                    || total > GarminRouteProtocol.MAX_TOTAL_POINTS) continue;
            // Advance state only after SDK acceptance; rejected removals/updates retry on reconciliation.
            if (link.send("route", next.payload())) cache.put(next.key(), next);
        }
        if (!omitted) {
            capacityReported.remove(link);
        } else if (!capacityReported.contains(link) && link.send("routes_error",
                GarminRouteProtocol.error("Route capacity reached; some routes were not sent."))) {
            capacityReported.add(link);
        }
    }

    private synchronized void changed(GarminRouteProtocol.Route route) {
        if (!active() || route == null) return;
        queueReconciliation();
    }

    private synchronized void rejected(String message) {
        if (!active()) return;
        String safe = message == null ? "Invalid ATAK route was not sent." : message;
        schedule.accept(() -> {
            if (!active()) return;
            for (GarminEntityForwarder.Link link : targets.get()) {
                if (link.isCurrent() && link.routeVersion() == GarminRouteProtocol.VERSION)
                    link.send("routes_error", GarminRouteProtocol.error(safe));
            }
        });
    }

    private synchronized void removed(String key) {
        if (!active() || key == null) return;
        queueReconciliation();
    }

    private void queueReconciliation() {
        if (reconciliationQueued) return;
        reconciliationQueued = true;
        schedule.accept(() -> {
            synchronized (GarminRouteForwarder.this) {
                reconciliationQueued = false;
                if (active()) refreshLinks();
            }
        });
    }

    private boolean active() { return started && !closed && current.getAsBoolean(); }
}
