package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class GarminUserMapProtocolTest {
    private final Source source = new Source();
    private final Link link = new Link();
    private final List<GarminEntityForwarder.Link> links = new ArrayList<>();
    private final List<Runnable> queue = new ArrayList<>();
    private final GarminEntityForwarder forwarder =
            new GarminEntityForwarder(source, () -> new ArrayList<>(links), queue::add, () -> true);

    private void start() { links.add(link); forwarder.start(); }
    private void drain() { new ArrayList<>(queue).forEach(Runnable::run); queue.clear(); }
    private static Map<String, Object> user(String uid) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("isUser", true);
        metadata.put("stale", "2099-01-01T00:00:00Z");
        metadata.put("time", "2026-01-01T00:00:00Z");
        metadata.put("contact", Collections.singletonMap("endpoint", "*:-1:stcp"));
        metadata.put("__group", Collections.singletonMap("name", "Blue"));
        metadata.put("track", Collections.singletonMap("speed", "2"));
        return GarminEntityAdapter.normalize(uid, 38, -77, "a-f-G", metadata, false, false);
    }

    @Test public void snapshotExplicitlyScopesAndWarnsAboutNearestFifty() {
        for (int i = 0; i < 51; i++) source.items.add(user("user-" + i));
        start();
        Map<String, Object> payload = link.payloads.get(0);
        assertEquals(true, payload.get("replaceUsers"));
        assertEquals("phone-relay-users", payload.get("scope"));
        assertEquals(50, ((List<?>) payload.get("entities")).size());
        assertEquals(true, payload.get("truncated"));
        assertEquals(java.util.Arrays.asList("phone", "watch"), payload.get("excludedUids"));
        assertEquals(51, source.limit);
    }

    @Test public void publishedUserRemovalIsScopedButPointRemovalNeverDeletesWatchPoints() {
        source.items.add(user("remote"));
        start();
        source.removed.accept("remote");
        assertEquals("entity_remove", link.types.get(1));
        assertEquals("remote", link.payloads.get(1).get("uid"));
        assertEquals("phone-relay-users", link.payloads.get(1).get("scope"));
        source.removed.accept("remote");
        assertEquals(2, link.types.size());
        Map<String, Object> point = GarminEntityForwarderTest.entity("point", "2026-01-01T00:00:00Z", "a-f-G");
        source.listener.accept(point);
        drain();
        source.removed.accept("point");
        assertEquals(3, link.types.size());
        assertEquals("entity", link.types.get(2));
    }

    @Test public void queuedUserUpdateCannotCrossHelloSessionChange() {
        start();
        source.listener.accept(user("old"));
        link.session = "new";
        forwarder.refreshLinks();
        drain();
        assertEquals(java.util.Arrays.asList("entities", "entities"), link.types);
        source.listener.accept(user("fresh"));
        drain();
        assertEquals("entity", link.types.get(2));
    }

    @Test public void multipleWatchesClearOldUserCopyAndSuppressLiveUntilExclusive() {
        source.items.add(user("remote"));
        start();
        links.add(new Link());
        forwarder.refreshLinks();
        assertEquals(Collections.emptyList(), link.payloads.get(1).get("entities"));
        source.listener.accept(user("ignored"));
        drain();
        assertEquals(2, link.types.size());
        links.remove(1);
        forwarder.refreshLinks();
        assertEquals("entities", link.types.get(2));
    }

    @Test public void liveCapacityRefreshesBoundedSnapshotInsteadOfSilentlyEvictingUsers() {
        for (int i = 0; i < 50; i++) source.items.add(user("user-" + i));
        start();
        source.items.add(user("extra"));
        source.listener.accept(user("extra"));
        drain();
        assertEquals(java.util.Arrays.asList("entities", "entities"), link.types);
        assertEquals(true, link.payloads.get(1).get("truncated"));
        assertEquals(50, ((List<?>) link.payloads.get(1).get("entities")).size());
    }

    @Test public void failedRemovalRemainsRetryableAndNoSessionCannotPublish() {
        source.items.add(user("remote"));
        start();
        link.accept = false;
        source.removed.accept("remote");
        link.accept = true;
        source.removed.accept("remote");
        assertEquals("entity_remove", link.types.get(1));
        link.session = "";
        forwarder.refreshLinks();
        assertFalse(forwarder.sendSnapshot(link, 50));
        source.listener.accept(user("not-ready"));
        drain();
        assertEquals(2, link.types.size());
    }

    @Test public void userClassificationAndRestoredContactDetailsSurviveNormalization() {
        Map<String, Object> remote = user("remote");
        assertEquals(true, remote.get("isUser"));
        assertEquals(false, remote.get("isPoint"));
        assertEquals("*:-1:stcp", ((Map<?, ?>) remote.get("contact")).get("endpoint"));
        assertEquals("Blue", ((Map<?, ?>) remote.get("__group")).get("name"));
        assertEquals("2", ((Map<?, ?>) remote.get("track")).get("speed"));
        assertNull(GarminEntityAdapter.normalize("self", 38, -77, "a-f-G", remote, true, false));
        assertNull(GarminEntityAdapter.normalize("watch", 38, -77, "a-f-G", remote, false, true));
        Map<String, Object> point = GarminEntityAdapter.normalize("point", 38, -77,
                "b-m-p-s-p-i", remote, false, false);
        assertEquals(true, point.get("isPoint"));
        assertFalse(point.containsKey("isUser"));
    }

    @Test public void phoneAndSelectedWatchAreExcludedBeforeNativeHandoff() {
        source.items.add(user("phone"));
        source.items.add(user("watch"));
        source.items.add(user("remote"));
        start();
        assertEquals(1, ((List<?>) link.payloads.get(0).get("entities")).size());
        source.listener.accept(user("phone"));
        source.listener.accept(user("watch"));
        drain();
        assertEquals(1, link.types.size());
    }

    @Test public void pendingUserChangesCoalesceAndBoundDistinctUidsWithWarning() {
        start();
        for (int i = 0; i < 100; i++) source.listener.accept(user("same"));
        assertEquals(1, queue.size());
        for (int i = 0; i < 512; i++) source.listener.accept(user("user-" + i));
        assertEquals(512, queue.size());
        assertEquals(1, source.warnings);
        forwarder.close();
        drain();
        assertEquals(1, link.types.size());
    }

    @Test public void missingWatchOrPhoneIdentityFailsClosedForUsers() {
        link.watch = "";
        start();
        assertTrue(link.types.isEmpty());
        assertFalse(forwarder.sendSnapshot(link, 50));
        link.watch = "watch";
        forwarder.refreshLinks();
        source.phone = "";
        source.listener.accept(user("remote"));
        drain();
        assertEquals(Collections.singletonList("entities"), link.types);
        assertEquals(1, source.warnings);
    }

    @Test public void sourceUserLifetimeMustExistAndCannotOverflow() {
        assertFalse(GarminEntityAdapter.validUserLifetime(0, 90000));
        assertFalse(GarminEntityAdapter.validUserLifetime(1000, 0));
        assertFalse(GarminEntityAdapter.validUserLifetime(-1, 90000));
        assertFalse(GarminEntityAdapter.validUserLifetime(Long.MAX_VALUE - 1, 2));
        assertTrue(GarminEntityAdapter.validUserLifetime(1000, 90000));
    }

    static final class Link implements GarminEntityForwarder.Link {
        String session = "current";
        String watch = "watch";
        boolean accept = true;
        final List<String> types = new ArrayList<>();
        final List<Map<String, Object>> payloads = new ArrayList<>();
        @Override public boolean isCurrent() { return true; }
        @Override public int entitySyncVersion() { return 2; }
        @Override public String relaySession() { return session; }
        @Override public String watchUid() { return watch; }
        @Override public boolean send(String type, Map<String, Object> payload) {
            if (!accept) return false;
            types.add(type);
            payloads.add(payload);
            return true;
        }
    }

    static final class Source implements GarminEntityForwarder.Source {
        Consumer<Map<String, Object>> listener;
        Consumer<String> removed;
        final List<Map<String, Object>> items = new ArrayList<>();
        String phone = "phone";
        int limit, warnings;
        @Override public void subscribe(Consumer<Map<String, Object>> next, Consumer<String> removal) {
            listener = next; removed = removal;
        }
        @Override public void unsubscribe() { listener = null; }
        @Override public List<Map<String, Object>> snapshot(int requested) {
            limit = requested;
            return GarminEntityAdapter.nearestCandidates(items, 38, -77, requested);
        }
        @Override public List<String> excludedUids() { return Collections.singletonList(phone); }
        @Override public void warning(String message) { warnings++; }
    }
}
