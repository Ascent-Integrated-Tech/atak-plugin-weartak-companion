package com.weartak.atak.weartak_companion.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** One map subscription, with independent bounded handoff state for each active watch link. */
final class GarminEntityForwarder {
    interface Source {
        void subscribe(Consumer<Map<String, Object>> listener, Consumer<String> removed);
        void unsubscribe();
        List<Map<String, Object>> snapshot(int limit);
        default List<String> excludedUids() { return java.util.Collections.emptyList(); }
        default void warning(String message) {}
    }
    interface Link {
        boolean isCurrent();
        boolean send(String type, Map<String, Object> payload);
        default int routeVersion() { return 0; }
        default int routeNavigationVersion() { return 0; }
        default int entitySyncVersion() { return 1; }
        default String relaySession() { return ""; }
        default String watchUid() { return ""; }
        default boolean sendForSession(String type, Map<String, Object> payload, String expectedSession) {
            return relaySession().equals(expectedSession) && send(type, payload);
        }
    }
    private final Source source;
    private final Supplier<List<Link>> targets;
    private final Consumer<Runnable> schedule;
    private final BooleanSupplier current;
    private final Map<Link, Map<String, Map<String, Object>>> published = new HashMap<>();
    private final Map<Link, String> sessions = new HashMap<>();
    private static final class Pending {
        final boolean retainOnRemoval;
        Map<String, Object> entity;
        Pending(Map<String, Object> value) {
            entity = value;
            retainOnRemoval = Boolean.TRUE.equals(value.get("isAlert"))
                    || Boolean.TRUE.equals(value.get("isDelete"));
        }
    }
    private final Map<String, Set<Pending>> pending = new HashMap<>();
    private boolean started;
    private boolean closed;
    private int snapshotSize;
    private boolean snapshotTruncated;

    GarminEntityForwarder(Source source, Supplier<List<Link>> targets,
            Consumer<Runnable> schedule, BooleanSupplier current) {
        this.source = source;
        this.targets = targets;
        this.schedule = schedule;
        this.current = current;
    }

    synchronized void start() {
        if (started || closed || !current.getAsBoolean()) return;
        started = true;
        source.subscribe(this::changed, this::removed);
        refreshLinks();
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        if (started) source.unsubscribe();
        started = false;
        published.clear();
        sessions.clear();
        pending.clear();
    }

    synchronized void refreshLinks() {
        if (!active()) return;
        List<Link> links = targets.get();
        published.keySet().removeIf(link -> !link.isCurrent() || !links.contains(link));
        sessions.keySet().retainAll(published.keySet());
        for (Link link : links) {
            if (link.entitySyncVersion() >= 2
                    && (links.size() != 1 || link.relaySession().isEmpty() || link.watchUid().isEmpty())) {
                if (published.containsKey(link) && link.isCurrent() && !link.relaySession().isEmpty()) {
                    Map<String, Object> clear = new HashMap<>();
                    clear.put("entities", java.util.Collections.emptyList());
                    clear.put("replaceUsers", true);
                    clear.put("scope", "phone-relay-users");
                    clear.put("excludedUids", source.excludedUids());
                    link.send("entities", clear);
                }
                published.remove(link);
                sessions.remove(link);
                continue;
            }
            if (published.containsKey(link) && !link.relaySession().equals(sessions.get(link))) {
                published.remove(link);
            }
            if (!link.isCurrent() || published.containsKey(link)) continue;
            sessions.put(link, link.relaySession());
            published.put(link, new LinkedHashMap<String, Map<String, Object>>(64, .75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, Map<String, Object>> eldest) {
                    return link.entitySyncVersion() < 2 && size() > 50;
                }
            });
            sendSnapshot(link, 50);
        }
    }

    synchronized boolean sendSnapshot(Link link, int limit) {
        if (!active() || link == null || !link.isCurrent()) return false;
        if (link.entitySyncVersion() >= 2 && (targets.get().size() != 1
                || link.relaySession().isEmpty() || link.watchUid().isEmpty())) return false;
        int bound = Math.max(1, Math.min(limit, 50));
        String session = link.relaySession();
        List<Map<String, Object>> candidates = new ArrayList<>(source.snapshot(bound + 1));
        boolean truncated = candidates.size() > bound;
        candidates.removeIf(entity -> !eligible(link, entity));
        List<Map<String, Object>> snapshot = new ArrayList<>(candidates.subList(0, Math.min(bound, candidates.size())));
        snapshotSize = snapshot.size();
        snapshotTruncated = truncated;
        Map<String, Object> payload = new HashMap<>();
        payload.put("entities", snapshot);
        if (link.entitySyncVersion() >= 2) {
            payload.put("replaceUsers", true);
            payload.put("scope", "phone-relay-users");
            payload.put("limit", bound);
            payload.put("truncated", truncated);
            List<String> excluded = new ArrayList<>(source.excludedUids());
            if (!link.watchUid().isEmpty()) excluded.add(link.watchUid());
            payload.put("excludedUids", excluded);
        }
        if (!link.sendForSession("entities", payload, session)) return false;
        Map<String, Map<String, Object>> cache = published.get(link);
        if (cache != null) {
            if (link.entitySyncVersion() >= 2) cache.entrySet().removeIf(e -> isUser(e.getValue()));
            for (Map<String, Object> entity : snapshot) cache.put((String) entity.get("uid"), entity);
            if (link.entitySyncVersion() >= 2) trimCache(cache);
        }
        return true;
    }

    synchronized int snapshotSize() { return snapshotSize; }
    synchronized boolean snapshotTruncated() { return snapshotTruncated; }

    private synchronized void changed(Map<String, Object> entity) {
        if (!active() || entity == null) return;
        String uid = (String) entity.get("uid");
        if (isUser(entity)) {
            Set<Pending> queued = pending.get(uid);
            if (queued != null) {
                for (Pending revision : queued) {
                    if (isUser(revision.entity)) {
                        if (!older(entity, revision.entity)) revision.entity = entity;
                        return;
                    }
                }
            } else if (pending.size() >= 512) {
                source.warning("Garmin pending user capacity reached (512); update omitted");
                return;
            }
        }
        List<Link> captured = new ArrayList<>(targets.get());
        if (captured.isEmpty()) return;
        Pending revision = new Pending(entity);
        pending.computeIfAbsent(uid, key -> new HashSet<>()).add(revision);
        Map<Link, String> capturedSessions = new HashMap<>();
        for (Link link : captured) capturedSessions.put(link, link.relaySession());
        schedule.accept(() -> publish(revision.entity, captured, capturedSessions, revision));
    }

    private synchronized void removed(String uid) {
        if (!active()) return;
        Set<Pending> queued = pending.get(uid);
        if (queued != null) {
            queued.removeIf(revision -> !revision.retainOnRemoval);
            if (queued.isEmpty()) pending.remove(uid);
        }
        for (Map.Entry<Link, Map<String, Map<String, Object>>> entry : published.entrySet()) {
            Link link = entry.getKey();
            Map<String, Object> previous = entry.getValue().get(uid);
            if (previous != null && isUser(previous) && link.entitySyncVersion() >= 2
                    && link.isCurrent() && targets.get().size() == 1) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("uid", uid);
                payload.put("scope", "phone-relay-users");
                if (previous.get("time") instanceof String) payload.put("time", previous.get("time"));
                if (!link.sendForSession("entity_remove", payload, sessions.get(link))) continue;
            } else if (previous != null && !Boolean.TRUE.equals(previous.get("isAlert")) && link.isCurrent()) {
                Map<String, Object> deletion = new HashMap<>();
                deletion.put("uid", uid);
                deletion.put("type", "t-x-d-d");
                deletion.put("isDelete", true);
                deletion.put("time", java.time.Instant.now().toString());
                if (!link.sendForSession("entity", deletion, sessions.get(link))) continue;
            }
            entry.getValue().remove(uid);
        }
    }

    private synchronized void publish(Map<String, Object> entity, List<Link> captured,
            Map<Link, String> capturedSessions, Pending revision) {
        String uid = (String) entity.get("uid");
        Set<Pending> queued = pending.get(uid);
        if (!active() || queued == null || !queued.remove(revision)) return;
        if (queued.isEmpty()) pending.remove(uid);
        for (Link link : captured) {
            if (!link.isCurrent()) continue;
            if (!link.relaySession().equals(capturedSessions.get(link))) continue;
            if (link.entitySyncVersion() >= 2 && targets.get().size() != 1) continue;
            Map<String, Map<String, Object>> cache = published.get(link);
            if (cache == null) continue;
            if (!eligible(link, entity)) continue;
            Map<String, Object> previous = cache.get(uid);
            if (entity.equals(previous) || older(entity, previous)) continue;
            if (isUser(entity) && previous == null
                    && cache.values().stream().filter(GarminEntityForwarder::isUser).count() >= 50
                    && link.entitySyncVersion() >= 2) {
                sendSnapshot(link, 50);
                continue;
            }
            // Acceptance means SDK handoff only, never an acknowledgement from the watch.
            if (link.sendForSession("entity", new HashMap<>(entity), capturedSessions.get(link))) {
                cache.put(uid, entity);
                if (link.entitySyncVersion() >= 2) trimCache(cache);
            }
        }
    }

    private static boolean isUser(Map<String, Object> entity) {
        return Boolean.TRUE.equals(entity.get("isUser"));
    }

    private static void trimCache(Map<String, Map<String, Object>> cache) {
        java.util.Iterator<Map.Entry<String, Map<String, Object>>> entries = cache.entrySet().iterator();
        while (entries.hasNext() && cache.size() > 100) {
            if (!isUser(entries.next().getValue())) entries.remove();
        }
    }

    private boolean eligible(Link link, Map<String, Object> entity) {
        if (!Boolean.TRUE.equals(entity.get("isAlert"))
                && (link.watchUid().equals(entity.get("uid"))
                || source.excludedUids().contains(entity.get("uid")))) return false;
        if (!isUser(entity)) return true;
        Object uid = entity.get("uid");
        if (link.entitySyncVersion() >= 2 && (link.watchUid().isEmpty()
                || source.excludedUids().stream().noneMatch(id -> id != null && !id.isEmpty()))) {
            source.warning("Garmin user relay requires known phone and watch UIDs");
            return false;
        }
        return !link.watchUid().equals(uid) && !source.excludedUids().contains(uid);
    }

    private static boolean older(Map<String, Object> next, Map<String, Object> previous) {
        if (previous == null || !(next.get("time") instanceof String)
                || !(previous.get("time") instanceof String)) return false;
        try {
            int order = java.time.Instant.parse((String) next.get("time"))
                    .compareTo(java.time.Instant.parse((String) previous.get("time")));
            return order < 0 || order == 0 && Boolean.FALSE.equals(previous.get("active"))
                    && Boolean.TRUE.equals(next.get("active"));
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private boolean active() { return started && !closed && current.getAsBoolean(); }
}
