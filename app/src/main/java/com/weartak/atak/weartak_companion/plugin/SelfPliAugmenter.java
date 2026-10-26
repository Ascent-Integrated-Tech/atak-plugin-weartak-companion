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
    private String boundLink;
    private String boundSession;
    private String boundDeviceUid;
    private java.util.function.Consumer<String> pairedWatch = uid -> { };

    SelfPliAugmenter(Host host, Clock clock) {
        this.host = host;
        this.clock = clock;
    }

    /** Receives the authorized watch UID while its link is current, and null once it is gone. */
    synchronized void setPairedWatchListener(java.util.function.Consumer<String> listener) {
        pairedWatch = listener == null ? uid -> { } : listener;
        pairedWatch.accept(boundDeviceUid);
    }

    /** Removes contributions left behind by a previous process before any watch connects. */
    synchronized void start() {
        boundLink = boundSession = boundDeviceUid = null;
        state.clear();
        published = null;
        pairedWatch.accept(null);
        refresh();
    }

    /**
     * Binds snapshots to the current authorized transport generation. A null expected session or
     * device UID is locked to the first accepted snapshot on this link. Rebinding to a different
     * link removes the previous link's contribution.
     */
    synchronized void bind(String linkId, String expectedSession, String expectedDeviceUid) {
        if (linkId == null || linkId.isEmpty()) return;
        boolean sameLink = linkId.equals(boundLink)
                && (expectedSession == null || expectedSession.equals(boundSession))
                && (expectedDeviceUid == null || expectedDeviceUid.equals(boundDeviceUid));
        if (sameLink) return;
        if (boundLink != null) clear("watch link changed");
        boundLink = linkId;
        boundSession = expectedSession;
        boundDeviceUid = expectedDeviceUid;
    }

    synchronized boolean accept(String linkId, JSONObject payload) {
        if (boundLink == null || !boundLink.equals(linkId)) {
            host.log("Watch self_status rejected: link is not current");
            return false;
        }
        String session = payload == null ? null : payload.optString("session", null);
        String deviceUid = payload == null ? null : payload.optString("deviceUid", null);
        if (boundSession != null && !boundSession.equals(session)) {
            host.log("Watch self_status rejected: session is not current");
            return false;
        }
        if (boundDeviceUid != null && !boundDeviceUid.equals(deviceUid)) {
            host.log("Watch self_status rejected: device is not the authorized watch");
            return false;
        }
        try {
            if (!state.accept(linkId, payload, clock.now())) return false;
        } catch (IllegalArgumentException error) {
            host.log("Watch self_status rejected: " + error.getMessage());
            return false;
        }
        boundSession = session;
        boundDeviceUid = deviceUid;
        pairedWatch.accept(deviceUid);
        refresh();
        return true;
    }

    synchronized void clear(String reason) {
        if (published != null && !published.isEmpty())
            host.log("Removing watch PPE/physio from phone PLI: " + reason);
        boundLink = boundSession = boundDeviceUid = null;
        state.clear();
        pairedWatch.accept(null);
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
