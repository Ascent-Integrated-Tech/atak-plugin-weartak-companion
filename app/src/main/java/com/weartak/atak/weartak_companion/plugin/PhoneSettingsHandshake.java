package com.weartak.atak.weartak_companion.plugin;

/** Confirmation and retries belong to one ready single-watch session. */
final class PhoneSettingsHandshake {
    static final long TIMEOUT_MILLIS = 10_000;
    static final int MAX_ATTEMPTS = 3;
    enum Action { NONE, REQUEST, APPLY, FAILED, CONFIRMED }
    private enum Phase { IDLE, SNAPSHOT, ACK, CONFIRMATION, COMPLETE, FAILED }

    private Phase phase = Phase.IDLE;
    private long generation;
    private long deadline;
    private int attempts;

    long token() { return generation; }
    boolean isCurrent(long token) { return generation == token; }
    boolean isPending() {
        return phase == Phase.SNAPSHOT || phase == Phase.ACK || phase == Phase.CONFIRMATION;
    }
    boolean isFailed() { return phase == Phase.FAILED; }
    boolean isConfirmed() { return phase == Phase.COMPLETE; }

    Action begin(long now) {
        ++generation;
        return enter(Phase.SNAPSHOT, now);
    }

    void cancel() {
        ++generation;
        phase = Phase.IDLE;
    }

    Action snapshot(long token, boolean complete, boolean managed, long now) {
        if (!isCurrent(token) || !complete) return Action.NONE;
        if (phase == Phase.SNAPSHOT) return enter(Phase.ACK, now);
        if (phase == Phase.CONFIRMATION && managed) {
            phase = Phase.COMPLETE;
            return Action.CONFIRMED;
        }
        return Action.NONE;
    }

    Action acknowledge(long token, boolean ok, long now) {
        if (!isCurrent(token) || phase != Phase.ACK) return Action.NONE;
        if (!ok) {
            phase = Phase.FAILED;
            return Action.FAILED;
        }
        return enter(Phase.CONFIRMATION, now);
    }

    Action timeout(long token, long now) {
        if (!isCurrent(token) || !isPending() || now < deadline) return Action.NONE;
        if (attempts >= MAX_ATTEMPTS) {
            phase = Phase.FAILED;
            return Action.FAILED;
        }
        ++attempts;
        deadline = now + TIMEOUT_MILLIS;
        return phase == Phase.ACK ? Action.APPLY : Action.REQUEST;
    }

    private Action enter(Phase next, long now) {
        phase = next;
        attempts = 1;
        deadline = now + TIMEOUT_MILLIS;
        return phase == Phase.ACK ? Action.APPLY : Action.REQUEST;
    }
}
