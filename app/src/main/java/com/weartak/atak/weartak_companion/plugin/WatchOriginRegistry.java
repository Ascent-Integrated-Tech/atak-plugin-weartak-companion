package com.weartak.atak.weartak_companion.plugin;

import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks watch-originated events so ATAK callbacks are not echoed to the watch. */
final class WatchOriginRegistry {

    private static final Set<String> MARKERS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> CHATS = new ConcurrentHashMap<>();
    private static final Map<String, String> CHAT_ORIGINS = new ConcurrentHashMap<>();
    private static final long CHAT_TTL_MS = 120_000L;
    private static final Set<String> EMERGENCIES = ConcurrentHashMap.newKeySet();

    private WatchOriginRegistry() {
    }

    static void markMarker(String uid) {
        addIfPresent(MARKERS, uid);
    }

    static boolean consumeMarker(String uid) {
        return removeIfPresent(MARKERS, uid);
    }

    static boolean isMarker(String uid) {
        return containsIfPresent(MARKERS, uid);
    }

    static void markChat(String uid) {
        markChat(uid, null);
    }

    static void markChat(String uid, String originDeviceUid) {
        if (uid == null || uid.isEmpty()) return;
        CHATS.put(uid, System.currentTimeMillis());
        if (originDeviceUid != null && !originDeviceUid.isEmpty()) {
            CHAT_ORIGINS.put(uid, originDeviceUid);
        }
    }

    static boolean isChat(String uid) {
        if (uid == null || uid.isEmpty()) return false;
        Long markedAt = CHATS.get(uid);
        if (markedAt == null) return false;
        if (System.currentTimeMillis() - markedAt > CHAT_TTL_MS) {
            CHATS.remove(uid, markedAt);
            CHAT_ORIGINS.remove(uid);
            return false;
        }
        return true;
    }

    static String chatOrigin(String uid) {
        if (!isChat(uid)) return "";
        String origin = CHAT_ORIGINS.get(uid);
        return origin == null ? "" : origin;
    }

    static void clearChat(String uid) {
        if (uid != null && !uid.isEmpty()) {
            CHATS.remove(uid);
            CHAT_ORIGINS.remove(uid);
        }
    }

    static void markEmergency(String uid) {
        addIfPresent(EMERGENCIES, uid);
    }

    static boolean consumeEmergency(String uid) {
        return removeIfPresent(EMERGENCIES, uid);
    }

    static boolean isEmergency(String uid) {
        return containsIfPresent(EMERGENCIES, uid);
    }

    static void clearEmergency(String uid) {
        removeIfPresent(EMERGENCIES, uid);
    }

    static void clearAll() {
        MARKERS.clear();
        CHATS.clear();
        CHAT_ORIGINS.clear();
        EMERGENCIES.clear();
    }

    private static void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isEmpty()) values.add(value);
    }

    private static boolean removeIfPresent(Set<String> values, String value) {
        return value != null && !value.isEmpty() && values.remove(value);
    }

    private static boolean containsIfPresent(Set<String> values, String value) {
        return value != null && !value.isEmpty() && values.contains(value);
    }
}
