package com.weartak.atak.weartak_companion.plugin;

import java.util.UUID;

/** One acknowledged release, fenced to the original ready transport session. */
final class PhoneManagementRelease {
    static final String COMMAND = "release_phone_management";
    static final String ACK = "phone_management_release_ack";
    static final long TIMEOUT_MILLIS = 15_000L;
    enum Result { IGNORED, SUCCEEDED, REFUSED, TIMED_OUT }

    private Object peer;
    private String requestId;
    private long generation;
    private long startedAt;

    long begin(Object readyPeer, String id, long now) {
        if (readyPeer == null || id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("A ready peer and UUID request ID are required.");
        }
        if (isPending()) throw new IllegalStateException("Release already pending.");
        peer = readyPeer;
        requestId = id;
        startedAt = now;
        return ++generation;
    }

    Result acknowledge(Object readyPeer, Object id, long token, long now, Object ok) {
        if (!(id instanceof String) || !(ok instanceof Boolean)
                || !matches(readyPeer, (String) id, token)) return Result.IGNORED;
        if (now - startedAt >= TIMEOUT_MILLIS) {
            cancel();
            return Result.TIMED_OUT;
        }
        cancel();
        return (Boolean) ok ? Result.SUCCEEDED : Result.REFUSED;
    }

    Result timeout(long token, long now) {
        if (!isPending() || token != generation || now - startedAt < TIMEOUT_MILLIS) {
            return Result.IGNORED;
        }
        cancel();
        return Result.TIMED_OUT;
    }

    void cancel() {
        peer = null;
        requestId = null;
        ++generation;
    }

    boolean isPending() { return peer != null; }
    long token() { return generation; }

    private boolean matches(Object readyPeer, String id, long token) {
        return isPending() && peer == readyPeer && generation == token && requestId.equals(id);
    }
}
