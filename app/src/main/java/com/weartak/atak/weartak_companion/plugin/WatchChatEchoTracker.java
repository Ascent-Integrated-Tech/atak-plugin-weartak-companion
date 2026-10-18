package com.weartak.atak.weartak_companion.plugin;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact, short-lived identities, not a sender or conversation mute. */
final class WatchChatEchoTracker {
    private final int capacity;
    private final long lifetimeMillis;
    private final Map<List<String>, Long> sent = new LinkedHashMap<>();

    WatchChatEchoTracker(int capacity, long lifetimeMillis) {
        if (capacity <= 0 || lifetimeMillis <= 0) {
            throw new IllegalArgumentException("Echo tracking requires positive limits");
        }
        this.capacity = capacity;
        this.lifetimeMillis = lifetimeMillis;
    }

    synchronized void record(String type, String eventUid, String source, String conversation,
                             String messageId, String text, long now) {
        List<String> key = key(type, eventUid, source, conversation, messageId, text);
        if (key == null) throw new IllegalArgumentException("Incomplete outgoing chat identity");
        expire(now);
        // Persistence and transmission can serialize the same message more than once.
        if (!sent.containsKey(key)) sent.put(key, now);
        while (sent.size() > capacity) sent.remove(sent.keySet().iterator().next());
    }

    synchronized boolean isEcho(String type, String eventUid, String source, String conversation,
                                String messageId, String text, long now) {
        expire(now);
        List<String> key = key(type, eventUid, source, conversation, messageId, text);
        return key != null && sent.containsKey(key);
    }

    synchronized void clear() {
        sent.clear();
    }

    private void expire(long now) {
        Iterator<Long> times = sent.values().iterator();
        while (times.hasNext()) {
            long recorded = times.next();
            if (now < recorded || now - recorded >= lifetimeMillis) times.remove();
        }
    }

    private static List<String> key(String type, String eventUid, String source,
                                    String conversation, String messageId, String text) {
        if (!"b-t-f".equals(type) || empty(eventUid) || empty(source)
                || empty(conversation) || empty(messageId) || text == null) return null;
        return Arrays.asList(eventUid, source, conversation, messageId, text);
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }
}
