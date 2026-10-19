package com.weartak.atak.weartak_companion.plugin;

/**
 * Traditional BLE only. Timers and transport callbacks run on the client's main thread.
 * Only a services-ready bonded peer can arm ongoing retries. A service change during
 * initial setup permits one bounded retry of the same bonded peer, never discovery/pairing.
 */
final class TraditionalBleReconnect {
    interface Transport {
        boolean available();
        boolean busy();
        boolean connect(String address);
        boolean probe();
        void reset();
    }

    private final SystemBondedWatchConnection.Scheduler scheduler;
    private final Transport transport;
    private boolean running, allowed, ready, retrying, awaiting;
    private boolean setupRecoveryAttempted, setupOnly;
    private String target;
    private long generation;
    private int failures, missed;
    private Runnable retry, timeout, health;

    TraditionalBleReconnect(SystemBondedWatchConnection.Scheduler scheduler, Transport transport) {
        this.scheduler = scheduler;
        this.transport = transport;
    }

    void start(boolean allowed) {
        start(allowed, null);
    }

    void start(boolean allowed, String previouslyReadyPeer) {
        stop();
        running = true;
        this.allowed = allowed;
        target = previouslyReadyPeer;
        resume();
    }

    void stop() {
        running = false;
        disarm();
    }

    void disarm() {
        target = null;
        setupRecoveryAttempted = setupOnly = false;
        ready = retrying = awaiting = false;
        failures = missed = 0;
        cancel();
    }

    void setAllowed(boolean allowed) {
        this.allowed = allowed;
        if (!allowed) disarm();
    }

    void onReady(String address) {
        cancel();
        target = address;
        setupOnly = false;
        ready = true;
        retrying = awaiting = false;
        failures = missed = 0;
        scheduleHealth();
    }

    void onLoss() {
        cancel();
        if (setupOnly) {
            target = null;
            setupOnly = false;
        }
        ready = awaiting = retrying = false;
        missed = 0;
        resume();
    }

    boolean onServiceChanged(String bondedAddress) {
        if (!running || !allowed || bondedAddress == null) return false;
        if (target != null && !setupOnly) {
            onLoss();
            return true;
        }
        if (setupRecoveryAttempted) return false;
        setupRecoveryAttempted = setupOnly = true;
        target = bondedAddress;
        ready = awaiting = false;
        failures = 0;
        resume();
        return true;
    }

    void pause() {
        cancel();
        if (setupOnly) {
            target = null;
            setupOnly = false;
        }
        ready = retrying = awaiting = false;
        failures = 0;
    }

    void resume() {
        if (!eligible() || ready || retrying || !transport.available()) return;
        retrying = true;
        scheduleRetry();
    }

    boolean isRetrying() { return retrying; }

    private boolean eligible() {
        return running && allowed && target != null;
    }

    private void scheduleRetry() {
        if (!eligible() || !transport.available()) {
            retrying = false;
            return;
        }
        cancel();
        final long token = generation;
        retry = () -> {
            if (!current(token) || !retrying) return;
            retry = null;
            if (!transport.available()) { pause(); return; }
            if (transport.busy()) { scheduleRetry(); return; }
            timeout = () -> {
                if (!current(token) || !retrying) return;
                timeout = null;
                transport.reset();
                onLoss();
            };
            scheduler.later(timeout, 30000);
            if (!transport.connect(target)) onLoss();
        };
        scheduler.later(retry, failures++ == 0 ? 2000 : 60000);
    }

    private void scheduleHealth() {
        if (!eligible() || !ready) return;
        final long token = generation;
        health = () -> {
            if (!current(token) || !ready) return;
            health = null;
            awaiting = true;
            timeout = () -> {
                if (!current(token) || !ready || !awaiting) return;
                timeout = null;
                awaiting = false;
                if (++missed >= 2) {
                    transport.reset();
                    onLoss();
                } else scheduleHealth();
            };
            scheduler.later(timeout, 10000);
            if (!transport.probe()) {
                transport.reset();
                onLoss();
            }
        };
        scheduler.later(health, 30000);
    }

    void onHealthResponse() {
        if (!eligible() || !ready || !awaiting) return;
        awaiting = false;
        missed = 0;
        if (timeout != null) scheduler.cancel(timeout);
        timeout = null;
        scheduleHealth();
    }

    private boolean current(long token) { return eligible() && token == generation; }

    private void cancel() {
        ++generation;
        if (retry != null) scheduler.cancel(retry);
        if (timeout != null) scheduler.cancel(timeout);
        if (health != null) scheduler.cancel(health);
        retry = timeout = health = null;
    }
}
