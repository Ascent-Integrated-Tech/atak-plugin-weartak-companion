package com.weartak.atak.weartak_companion.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/** Original source timestamps, never adjusted for receipt time or ATAK's stale grace period. */
final class SourceCotTiming {
    final long time, start, stale;

    SourceCotTiming(long time, long start, long stale) {
        if (time <= 0 || start <= 0 || stale <= 0) {
            throw new IllegalArgumentException("Invalid source CoT timestamps");
        }
        this.time = time;
        this.start = start;
        this.stale = stale;
    }

    String encode() {
        return time + "|" + start + "|" + stale;
    }

    static SourceCotTiming decode(String encoded) {
        String[] values = encoded.split("\\|", -1);
        if (values.length != 3) throw new IllegalArgumentException("Invalid source CoT timing metadata");
        return new SourceCotTiming(Long.parseLong(values[0]), Long.parseLong(values[1]),
                Long.parseLong(values[2]));
    }

    String fingerprint(String content) {
        return content + "|" + encode();
    }

    static SourceCotTiming select(SourceCotTiming source, SourceCotTiming exported,
                                  boolean persistentLocal) {
        if (source != null) return source;
        return persistentLocal ? exported : null;
    }

    static final class Cache {
        static final int CAPACITY = 512;
        private final Map<String, SourceCotTiming> values = new LinkedHashMap<>();

        synchronized void put(String uid, SourceCotTiming timing) {
            values.remove(uid);
            values.put(uid, timing);
            while (values.size() > CAPACITY) values.remove(values.keySet().iterator().next());
        }

        synchronized SourceCotTiming get(String uid) { return values.get(uid); }
        synchronized void remove(String uid) { values.remove(uid); }
        synchronized void clear() { values.clear(); }
    }
}
