package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

/**
 * Applies watch-owned SELF_STATUS contributions to the phone's own PLI and removes only those
 * contributions when the watch goes away, the snapshot expires, or the plugin stops.
 */
final class SelfPliAugmenter {
    private static final String TAG = "WTK/SelfPli";

    interface Host {
        String userRemarks();
        void setUserRemarks(String remarks);
        String managedRemarks();
        void setManagedRemarks(String line);
        /** Publishes the biometrics detail, or removes it when xml is empty. */
        void setBiometrics(WatchSelfStatus.Contribution contribution);
        void log(String message);
    }

    interface Clock {
        long now();
        void schedule(Runnable action, long delayMs);
        void cancel(Runnable action);
    }

    private final WatchSelfStatus state = new WatchSelfStatus();
    private final Host host;
    private final Clock clock;
    private final Runnable refresh = this::refresh;
    private WatchSelfStatus.Contribution published;

    SelfPliAugmenter(Host host, Clock clock) {
        this.host = host;
        this.clock = clock;
    }

    /** Removes contributions left behind by a previous process before any watch connects. */
    synchronized void start() {
        state.clear();
        published = null;
        refresh();
    }

    synchronized boolean accept(String linkId, JSONObject payload) {
        try {
            if (!state.accept(linkId, payload, clock.now())) return false;
        } catch (IllegalArgumentException error) {
            host.log("Watch self_status rejected: " + error.getMessage());
            return false;
        }
        refresh();
        return true;
    }

    synchronized void clear(String reason) {
        if (published != null && !published.isEmpty())
            host.log("Removing watch PPE/physio from phone PLI: " + reason);
        state.clear();
        refresh();
    }

    synchronized WatchSelfStatus.Contribution current() {
        return state.current(clock.now());
    }

    synchronized String phoneRemarks() {
        return host.userRemarks();
    }

    private synchronized void refresh() {
        clock.cancel(refresh);
        long now = clock.now();
        WatchSelfStatus.Contribution next = state.current(now);
        String line = next.remarksLine();
        String previous = host.managedRemarks();
        String current = host.userRemarks();
        String updated = SelfStatusRemarks.apply(current, previous, line);
        if (!updated.equals(current == null ? "" : current)) host.setUserRemarks(updated);
        if (!line.equals(previous == null ? "" : previous)) host.setManagedRemarks(line);
        if (published == null || !next.biometricsXml().equals(published.biometricsXml()))
            host.setBiometrics(next);
        published = next;
        if (next.nextChangeAt != Long.MAX_VALUE)
            clock.schedule(refresh, Math.max(0L, next.nextChangeAt - now));
    }
}
