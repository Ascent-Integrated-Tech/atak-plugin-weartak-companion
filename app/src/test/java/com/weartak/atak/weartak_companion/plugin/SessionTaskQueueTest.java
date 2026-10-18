package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class SessionTaskQueueTest {
    @Test public void dispatchRunsInOrderWithoutCallbackThreadOrTransportLock() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SessionTaskQueue queue = new SessionTaskQueue(() -> worker);
        Object transportLock = new Object();
        Thread caller = Thread.currentThread();
        List<Integer> received = new ArrayList<>();
        AtomicBoolean wrongThread = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        try {
            queue.start();
            synchronized (transportLock) {
                assertTrue(queue.submit(() -> {
                    wrongThread.set(Thread.currentThread() == caller);
                    synchronized (transportLock) { received.add(1); }
                }));
                assertTrue(queue.submit(() -> { received.add(2); done.countDown(); }));
            }
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertFalse(wrongThread.get());
            assertEquals(Arrays.asList(1, 2), received);
        } finally {
            queue.stop();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void stopDropsQueuedEventsWithoutInterruptingCurrentImport() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SessionTaskQueue queue = new SessionTaskQueue(() -> worker);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean staleRan = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            assertFalse(queue.submit(() -> fail("Inactive queue accepted work")));
            queue.start();
            queue.submit(() -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            queue.submit(() -> staleRan.set(true));
            queue.stop();
            assertFalse(queue.submit(() -> fail("Stopped queue accepted work")));
            release.countDown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(staleRan.get());
            assertFalse(interrupted.get());
        } finally {
            release.countDown();
            queue.stop();
            worker.shutdownNow();
        }
    }

    @Test public void restartAcceptsNewSessionWork() throws Exception {
        SessionTaskQueue queue = new SessionTaskQueue();
        CountDownLatch done = new CountDownLatch(1);
        try {
            queue.start();
            queue.stop();
            queue.start();
            assertTrue(queue.submit(done::countDown));
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally { queue.stop(); }
    }

    @Test public void disconnectInvalidatesPendingWorkButAllowsReconnectedWork() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SessionTaskQueue queue = new SessionTaskQueue(() -> worker);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean staleRan = new AtomicBoolean();
        try {
            queue.start();
            queue.submit(() -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            queue.submit(() -> staleRan.set(true));
            queue.invalidate();
            queue.submit(done::countDown);
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertFalse(staleRan.get());
        } finally {
            release.countDown();
            queue.stop();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
