package com.weartak.atak.weartak_companion.plugin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Rejection budget belongs to watch identity, not its rotating advertising address. */
final class BondFailureTracker {
    static final long RAPID_REJECTION_MS = 5_000;
    static final long FAILURE_WINDOW_MS = 120_000;
    private final Map<String, Failure> failures = new LinkedHashMap<>();

    static final class Failure {
        final int reason, count;
        final long at;
        final boolean likelyStale, blocked;

        Failure(int reason, int count, long at, boolean likelyStale) {
            this.reason = reason;
            this.count = count;
            this.at = at;
            this.likelyStale = likelyStale;
            blocked = count >= 2;
        }
    }

    static String identity(String advertisedCallsign, String advertisedName, String name) {
        for (String value : new String[]{advertisedName, name, advertisedCallsign}) {
            if (value == null || value.trim().isEmpty()) continue;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("wt-")) normalized = normalized.substring(3).trim();
            if (!normalized.isEmpty()) return normalized;
        }
        // An anonymous advertiser must not get an unlimited budget by changing its address.
        return "<unknown-watch>";
    }

    static boolean cancelled(int reason) {
        return reason == 3 || reason == 8; // Android AUTH_CANCELED / REMOTE_AUTH_CANCELED
    }

    synchronized Failure rejected(String identity, long elapsed, int reason, long now) {
        if (cancelled(reason)) {
            failures.remove(identity);
            return null;
        }
        Failure previous = failures.get(identity);
        if (previous != null && previous.blocked) return previous;
        int count = previous != null && now >= previous.at && now - previous.at <= FAILURE_WINDOW_MS
                ? previous.count + 1 : 1;
        boolean rapid = elapsed >= 0 && elapsed <= RAPID_REJECTION_MS;
        Failure failure = new Failure(reason, count, now,
                rapid || reason == 104 || reason == 105 || count >= 2);
        failures.put(identity, failure);
        if (failures.size() > 128) failures.remove(failures.keySet().iterator().next());
        return failure;
    }

    synchronized Failure blocked(String identity) {
        Failure failure = failures.get(identity);
        return failure != null && failure.blocked ? failure : null;
    }

    synchronized void clear(String identity) { failures.remove(identity); }
}
