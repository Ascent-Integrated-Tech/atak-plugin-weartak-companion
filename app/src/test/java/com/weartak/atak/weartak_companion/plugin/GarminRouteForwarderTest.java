package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class GarminRouteForwarderTest {
    @Test public void resetsBeforeReplayAndReplaysOnNewCapableLink() {
        Source source = new Source();
        GarminRouteProtocol.Route route = route("srv", "r1", 2);
        source.routes.add(route);
        FakeLink link = new FakeLink(1);
        GarminRouteForwarder forwarder = forwarder(source, link);

        forwarder.start();

        assertEquals(Arrays.asList("routes_reset", "route"), link.types());
        assertEquals(1, ((Number) link.payloads.get(1).get("routeVersion")).intValue());
    }

    @Test public void delaysUntilRouteCapabilityAndRefreshesOnHandshake() {
        Source source = new Source();
        source.routes.add(route("srv", "r1", 2));
        FakeLink link = new FakeLink(0);
        GarminRouteForwarder forwarder = forwarder(source, link);
        forwarder.start();
        assertTrue(link.types().isEmpty());

        link.version = 1;
        forwarder.refreshLink(link);
        assertEquals(Arrays.asList("routes_reset", "route"), link.types());
    }

    @Test public void scopesUpdatesAndRemovalsByServerAndUid() {
        Source source = new Source();
        source.routes.add(route("server-a", "shared", 2));
        source.routes.add(route("server-b", "shared", 2));
        FakeLink link = new FakeLink(1);
        GarminRouteForwarder forwarder = forwarder(source, link);
        forwarder.start();
        link.types.clear();
        link.payloads.clear();

        source.put(route("server-a", "shared", 3));
        assertEquals(Collections.singletonList("route"), link.types());
        assertEquals(3, ((List<?>) link.payloads.get(0).get("points")).size());
        source.remove("server-a\u0000shared");

        assertEquals(Arrays.asList("route", "route_remove"), link.types());
        assertEquals("server-a", link.payloads.get(1).get("serverId"));
        assertEquals("shared", link.payloads.get(1).get("uid"));
    }

    @Test public void respectsPerLinkRouteAndVertexBudgets() {
        Source source = new Source();
        for (int i = 0; i < 9; i++) source.routes.add(route("s", "r" + i, 16));
        FakeLink link = new FakeLink(1);
        GarminRouteForwarder forwarder = forwarder(source, link);
        forwarder.start();

        assertEquals(10, link.types().size()); // reset, eight routes, capacity error
        assertEquals("routes_reset", link.types().get(0));
        assertEquals(8, link.types().stream().filter("route"::equals).count());
        int vertices = 0;
        for (int i = 1; i < link.payloads.size(); i++) {
            if ("route".equals(link.types().get(i)))
                vertices += ((List<?>) link.payloads.get(i).get("points")).size();
        }
        assertEquals(128, vertices);
        assertEquals("routes_error", link.types().get(9));
    }

    private static GarminRouteForwarder forwarder(Source source, FakeLink link) {
        return new GarminRouteForwarder(source, () -> Collections.singletonList(link),
                Runnable::run, () -> true);
    }

    @Test public void rejectionLeavesPreviousRouteAndCloseSuppressesQueuedUpdates() {
        Source source = new Source();
        source.routes.add(route("s", "r", 2));
        FakeLink link = new FakeLink(1);
        List<Runnable> queued = new ArrayList<>();
        GarminRouteForwarder forwarder = new GarminRouteForwarder(source,
                () -> Collections.singletonList(link), queued::add, () -> true);
        forwarder.start();
        source.rejected.accept("Invalid route");
        queued.remove(0).run();
        assertEquals(Arrays.asList("routes_reset", "route", "routes_error"), link.types());
        source.put(route("s", "r", 3));
        forwarder.close();
        queued.remove(0).run();
        assertTrue(source.unsubscribed);
        assertEquals(3, link.types().size());
    }

    @Test public void failedResetCanRetryAndLiveOverflowPreservesExistingRoute() {
        Source source = new Source();
        source.routes.add(route("s", "a", 64));
        source.routes.add(route("s", "b", 64));
        FakeLink link = new FakeLink(1);
        link.accepted = false;
        GarminRouteForwarder forwarder = forwarder(source, link);
        forwarder.start();
        assertEquals(Collections.singletonList("routes_reset"), link.types());
        link.accepted = true;
        forwarder.refreshLinks();
        assertEquals(Arrays.asList("routes_reset", "routes_reset", "route", "route"), link.types());
        source.put(route("s", "overflow", 2));
        assertEquals("routes_error", link.types().get(4));
        source.put(route("s", "a", 2));
        assertEquals("route", link.types().get(5));
        assertEquals("route", link.types().get(6));
        assertEquals("overflow", link.payloads.get(6).get("uid"));
    }

    @Test public void removalCancelsQueuedUpdateBeforeItCanRestoreDeletedRoute() {
        Source source = new Source();
        source.routes.add(route("s", "r", 2));
        FakeLink link = new FakeLink(1);
        List<Runnable> queued = new ArrayList<>();
        GarminRouteForwarder forwarder = new GarminRouteForwarder(source,
                () -> Collections.singletonList(link), queued::add, () -> true);
        forwarder.start();

        source.put(route("s", "r", 3));
        source.remove("s\u0000r");
        queued.remove(0).run();

        assertEquals(Arrays.asList("routes_reset", "route", "route_remove"), link.types());
    }

    @Test public void recreatedRoutePublishesOnlyItsNewRevision() {
        Source source = new Source();
        source.routes.add(route("s", "r", 2));
        FakeLink link = new FakeLink(1);
        List<Runnable> queued = new ArrayList<>();
        GarminRouteForwarder forwarder = new GarminRouteForwarder(source,
                () -> Collections.singletonList(link), queued::add, () -> true);
        forwarder.start();

        source.put(route("s", "r", 3));
        source.remove("s\u0000r");
        source.put(route("s", "r", 4));
        for (Runnable update : queued) update.run();

        assertEquals(1, queued.size());
        assertEquals(Arrays.asList("routes_reset", "route", "route"), link.types());
        assertEquals(4, ((List<?>) link.payloads.get(2).get("points")).size());
    }

    @Test public void rejectedRemovalRetriesWithoutResetAndDoesNotOverfillWatch() {
        Source source = new Source();
        for (int i = 0; i < 9; i++) source.routes.add(route("s", "r" + i, 16));
        FakeLink link = new FakeLink(1);
        GarminRouteForwarder forwarder = forwarder(source, link);
        forwarder.start();
        link.rejectedTypes.add("route_remove");
        source.remove("s\u0000r0");
        assertTrue(link.delivered.containsKey("s\u0000r0"));
        assertFalse(link.delivered.containsKey("s\u0000r8"));
        link.rejectedTypes.clear();

        forwarder.refreshLinks();

        assertFalse(link.delivered.containsKey("s\u0000r0"));
        assertTrue(link.delivered.containsKey("s\u0000r8"));
        assertEquals(8, link.delivered.size());
        assertEquals(1, link.types.stream().filter("routes_reset"::equals).count());
        assertEquals(2, link.types.stream().filter("route_remove"::equals).count());
        int sent = link.types.size();
        forwarder.refreshLinks();
        assertEquals(sent, link.types.size());
    }

    @Test public void removalAutomaticallyRecoversRouteCountCapacityInStableKeyOrder() {
        Source source = new Source();
        for (int i = 9; i >= 0; i--) source.routes.add(route("s", "r" + i, 2));
        FakeLink link = new FakeLink(1);
        forwarder(source, link).start();
        assertFalse(link.delivered.containsKey("s\u0000r8"));
        source.remove("s\u0000r0");
        assertTrue(link.delivered.containsKey("s\u0000r8"));
        assertFalse(link.delivered.containsKey("s\u0000r9"));
        assertEquals(8, link.delivered.size());
    }

    @Test public void shrinkingGeometryRecoversOmittedRouteWithoutAnotherEdit() {
        Source source = new Source();
        source.routes.add(route("s", "a", 64));
        source.routes.add(route("s", "b", 64));
        source.routes.add(route("s", "c", 2));
        FakeLink link = new FakeLink(1);
        forwarder(source, link).start();
        assertFalse(link.delivered.containsKey("s\u0000c"));
        source.put(route("s", "a", 62));
        assertEquals(62, deliveredPoints(link, "a"));
        assertEquals(2, deliveredPoints(link, "c"));
    }

    @Test public void queuedShrinkPrecedesGrowthAndNeverExceedsTransientBudget() {
        Source source = new Source();
        source.routes.add(route("s", "a", 63));
        source.routes.add(route("s", "b", 64));
        FakeLink link = new FakeLink(1);
        List<Runnable> queued = new ArrayList<>();
        GarminRouteForwarder forwarder = new GarminRouteForwarder(source,
                () -> Collections.singletonList(link), queued::add, () -> true);
        forwarder.start();
        source.put(route("s", "a", 64));
        source.put(route("s", "b", 63));
        queued.remove(0).run();
        assertEquals(64, deliveredPoints(link, "a"));
        assertEquals(63, deliveredPoints(link, "b"));
        int count = link.types.size();
        forwarder.refreshLinks();
        assertEquals(count, link.types.size());
    }

    @Test public void rejectedShrinkCannotAdmitOverflowAndRetriesOnReconciliation() {
        Source source = new Source();
        source.routes.add(route("s", "a", 64));
        source.routes.add(route("s", "b", 64));
        source.routes.add(route("s", "c", 2));
        FakeLink link = new FakeLink(1);
        GarminRouteForwarder forwarder = forwarder(source, link);
        forwarder.start();
        link.rejectedTypes.add("route");
        source.put(route("s", "a", 62));
        assertEquals(64, deliveredPoints(link, "a"));
        assertFalse(link.delivered.containsKey("s\u0000c"));
        link.rejectedTypes.clear();
        forwarder.refreshLinks();
        assertEquals(62, deliveredPoints(link, "a"));
        assertEquals(2, deliveredPoints(link, "c"));
    }

    @Test public void oversizedLiveGrowthPreservesLastPublishedGeometry() {
        Source source = new Source();
        source.routes.add(route("s", "a", 62));
        source.routes.add(route("s", "b", 64));
        source.routes.add(route("s", "c", 2));
        FakeLink link = new FakeLink(1);
        forwarder(source, link).start();
        source.put(route("s", "a", 64));
        assertEquals(62, deliveredPoints(link, "a"));
        assertEquals(64, deliveredPoints(link, "b"));
        assertEquals(2, deliveredPoints(link, "c"));
        assertEquals("routes_error", link.types.get(link.types.size() - 1));
        source.put(route("s", "b", 62));
        assertEquals(64, deliveredPoints(link, "a"));
        assertEquals(62, deliveredPoints(link, "b"));
        assertEquals(2, deliveredPoints(link, "c"));
    }

    @Test public void retryStateIsPerLinkAndStaleLinksReceiveNoRecovery() {
        Source source = new Source();
        source.routes.add(route("s", "r", 2));
        FakeLink first = new FakeLink(1);
        FakeLink second = new FakeLink(1);
        GarminRouteForwarder forwarder = new GarminRouteForwarder(source,
                () -> Arrays.asList(first, second), Runnable::run, () -> true);
        forwarder.start();
        first.rejectedTypes.add("route_remove");
        source.remove("s\u0000r");
        assertEquals(1, first.delivered.size());
        assertTrue(second.delivered.isEmpty());
        int secondCount = second.types.size();
        forwarder.refreshLinks();
        assertEquals(secondCount, second.types.size());
        int firstCount = first.types.size();
        first.current = false;
        forwarder.refreshLinks();
        assertEquals(firstCount, first.types.size());
    }

    private static int deliveredPoints(FakeLink link, String uid) {
        return ((List<?>) link.delivered.get("s\u0000" + uid).get("points")).size();
    }

    @Test public void capacityWarningIsNotRepeatedUntilRecoveryOrExplicitRefresh() {
        Source source = new Source();
        for (int i = 0; i < 9; i++) source.routes.add(route("s", "r" + i, 2));
        FakeLink link = new FakeLink(1);
        GarminRouteForwarder forwarder = forwarder(source, link);
        forwarder.start();
        forwarder.refreshLinks();
        source.put(route("s", "r0", 3));
        assertEquals(1, link.types.stream().filter("routes_error"::equals).count());
        source.remove("s\u0000r8");
        source.put(route("s", "r8", 2));
        assertEquals(2, link.types.stream().filter("routes_error"::equals).count());
        forwarder.refreshLink(link);
        assertEquals(3, link.types.stream().filter("routes_error"::equals).count());
    }

    @Test public void rejectedCapacityWarningRetriesOnNextReconciliation() {
        Source source = new Source();
        for (int i = 0; i < 9; i++) source.routes.add(route("s", "r" + i, 2));
        FakeLink link = new FakeLink(1);
        link.rejectedTypes.add("routes_error");
        GarminRouteForwarder forwarder = forwarder(source, link);
        forwarder.start();
        link.rejectedTypes.clear();
        forwarder.refreshLinks();
        forwarder.refreshLinks();
        assertEquals(2, link.types.stream().filter("routes_error"::equals).count());
    }

    private static GarminRouteProtocol.Route route(String server, String uid, int count) {
        List<GarminRouteProtocol.Point> points = new ArrayList<>();
        for (int i = 0; i < count; i++) points.add(new GarminRouteProtocol.Point(i, i, null));
        return GarminRouteProtocol.route(server, uid, uid, null, null, points);
    }

    private static final class Source implements GarminRouteForwarder.Source {
        List<GarminRouteProtocol.Route> routes = new ArrayList<>();
        Consumer<GarminRouteProtocol.Route> changed;
        Consumer<String> removed;
        Consumer<String> rejected;
        boolean unsubscribed;
        void put(GarminRouteProtocol.Route route) {
            routes.removeIf(previous -> previous.key().equals(route.key()));
            routes.add(route);
            changed.accept(route);
        }
        void remove(String key) {
            routes.removeIf(route -> route.key().equals(key));
            removed.accept(key);
        }
        @Override public void subscribe(Consumer<GarminRouteProtocol.Route> changed,
                Consumer<String> removed, Consumer<String> rejected) {
            this.changed = changed;
            this.removed = removed;
            this.rejected = rejected;
        }
        @Override public void unsubscribe() { unsubscribed = true; }
        @Override public List<GarminRouteProtocol.Route> snapshot() { return routes; }
    }

    private static final class FakeLink implements GarminEntityForwarder.Link {
        int version;
        boolean accepted = true;
        boolean current = true;
        final java.util.Set<String> rejectedTypes = new java.util.HashSet<>();
        final Map<String, Map<String, Object>> delivered = new HashMap<>();
        final List<String> types = new ArrayList<>();
        final List<Map<String, Object>> payloads = new ArrayList<>();
        FakeLink(int version) { this.version = version; }
        @Override public boolean isCurrent() { return current; }
        @Override public int routeVersion() { return version; }
        @Override public boolean send(String type, Map<String, Object> payload) {
            types.add(type);
            payloads.add(new HashMap<>(payload));
            if (!accepted || rejectedTypes.contains(type)) return false;
            String key = payload.get("serverId") + "\u0000" + payload.get("uid");
            if ("routes_reset".equals(type)) delivered.clear();
            if ("route_remove".equals(type)) delivered.remove(key);
            if ("route".equals(type)) delivered.put(key, new HashMap<>(payload));
            assertTrue("Watch route count exceeded", delivered.size() <= 8);
            int total = 0;
            for (Map<String, Object> route : delivered.values()) {
                total += ((List<?>) route.get("points")).size();
            }
            assertTrue("Watch point count exceeded", total <= 128);
            return true;
        }
        List<String> types() { return types; }
    }
}
