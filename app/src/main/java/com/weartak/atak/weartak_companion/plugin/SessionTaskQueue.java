package com.weartak.atak.weartak_companion.plugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ordered work that must not retain a transport callback's locks or outlive its session. */
final class SessionTaskQueue {
    interface WorkerFactory {
        ExecutorService create();
    }

    private ExecutorService worker;
    private volatile long generation;
    private final WorkerFactory workers;

    SessionTaskQueue() {
        this(() -> Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "WearTAK-CoT");
            thread.setDaemon(true);
            return thread;
        }));
    }

    SessionTaskQueue(WorkerFactory workers) {
        this.workers = workers;
    }

    synchronized void start() {
        if (worker != null) return;
        ++generation;
        worker = workers.create();
    }

    synchronized boolean submit(Runnable action) {
        if (worker == null) return false;
        long token = generation;
        worker.execute(() -> {
            if (token == generation) action.run();
        });
        return true;
    }

    synchronized void stop() {
        ++generation;
        if (worker != null) {
            // Let an already-importing event finish; queued old-session events are fenced out.
            worker.shutdown();
            worker = null;
        }
    }

    synchronized void invalidate() {
        ++generation;
    }
}
