package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class TraditionalBleReconnectTest {
    @Test public void nineIndependentPeersUseSingleWatchRetrySemanticsAndCancelTogetherOnExit() {
        Scheduler[] schedulers = new Scheduler[9];
        Transport[] transports = new Transport[9];
        TraditionalBleReconnect[] peers = new TraditionalBleReconnect[9];
        Runnable[] stale = new Runnable[9];
        for (int i = 0; i < 9; i++) {
            schedulers[i] = new Scheduler();
            transports[i] = new Transport();
            peers[i] = new TraditionalBleReconnect(schedulers[i], transports[i]);
            peers[i].start(true, "ready-bonded-" + i);
            assertEquals(2000, schedulers[i].delay());
            stale[i] = schedulers[i].first();
        }
        peers[0].disarm(); // Manual pause/removal belongs only to this admitted peer.
        stale[0].run();
        assertEquals(0, transports[0].connects);
        assertTrue(schedulers[0].pending.isEmpty());
        for (int i = 1; i < 9; i++) {
            schedulers[i].fire();
            assertEquals("ready-bonded-" + i, transports[i].address);
            peers[i].onLoss();
            assertEquals(60000, schedulers[i].delay());
            stale[i] = schedulers[i].first();
        }
        for (int i = 0; i < 9; i++) {
            peers[i].stop();
            stale[i].run();
            peers[i].resume();
            assertTrue(schedulers[i].pending.isEmpty());
            assertEquals(i == 0 ? 0 : 1, transports[i].connects);
        }
    }

    @Test public void onePeersTrustLossDoesNotDisarmAnotherPeersHealthOrRetry() {
        Scheduler secondScheduler = new Scheduler();
        Transport secondTransport = new Transport();
        TraditionalBleReconnect second = new TraditionalBleReconnect(secondScheduler, secondTransport);
        ready();
        second.start(true, "second-bonded-watch");
        controller.disarm();
        secondScheduler.fire();
        assertEquals("second-bonded-watch", secondTransport.address);
        second.onReady("second-bonded-watch");
        assertEquals(30000, secondScheduler.delay());
        assertTrue(scheduler.pending.isEmpty());
    }
    private final Scheduler scheduler = new Scheduler();
    private final Transport transport = new Transport();
    private final TraditionalBleReconnect controller = new TraditionalBleReconnect(scheduler, transport);

    private void ready() {
        controller.start(true);
        controller.onReady("bonded-watch");
    }

    @Test public void otherModesDoNotProbeOrRetryEvenAfterReady() {
        controller.start(false);
        controller.onReady("bonded-watch");
        controller.onLoss();
        controller.resume();
        assertTrue(scheduler.pending.isEmpty());
        assertFalse(controller.isRetrying());
    }

    @Test public void initialConnectionFailureCannotArmReconnect() {
        controller.start(true);
        controller.onLoss();
        controller.resume();
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void unexpectedLossStartsAtTwoSecondsThenSixtySecondsAfterFailure() {
        ready();
        controller.onLoss();
        assertEquals(2000, scheduler.delay());
        scheduler.fire();
        assertEquals("bonded-watch", transport.address);
        assertEquals(30000, scheduler.delay());
        controller.onLoss();
        assertEquals(60000, scheduler.delay());
        scheduler.fire();
        controller.onLoss();
        assertEquals(60000, scheduler.delay());
    }

    @Test public void readinessStopsRetryAndRestartsBoundedHealthProbe() {
        ready();
        controller.onLoss();
        Runnable stale = scheduler.first();
        controller.onReady("new-watch");
        stale.run();
        assertEquals(0, transport.connects);
        assertFalse(controller.isRetrying());
        assertEquals(30000, scheduler.delay());
        scheduler.fire();
        assertEquals(1, transport.probes);
        assertEquals(10000, scheduler.delay());
        controller.onHealthResponse();
        assertEquals(30000, scheduler.delay());
        assertEquals(0, transport.resets);
    }

    @Test public void twoMissedProbesResetAndRetryWithoutContinuousPolling() {
        ready();
        scheduler.fire(); // 30-second probe
        scheduler.fire(); // 10-second timeout
        assertEquals(30000, scheduler.delay());
        scheduler.fire();
        scheduler.fire();
        assertEquals(1, transport.resets);
        assertEquals(2000, scheduler.delay());
    }

    @Test public void failedProbeResetsAndRetries() {
        ready();
        transport.probeAccepted = false;
        scheduler.fire();
        assertEquals(1, transport.resets);
        assertEquals(2000, scheduler.delay());
    }

    @Test public void manualDisconnectAndTrustLossDisarmUntilAnotherReadySession() {
        ready();
        controller.onLoss();
        Runnable old = scheduler.first();
        controller.disarm();
        old.run();
        controller.resume();
        controller.onLoss();
        assertTrue(scheduler.pending.isEmpty());
        assertEquals(0, transport.connects);
        controller.onReady("another-watch");
        controller.onLoss();
        scheduler.fire();
        assertEquals("another-watch", transport.address);
    }

    @Test public void revokingModeOwnershipCancelsAllTimersAndLateCallbacks() {
        ready();
        Runnable health = scheduler.first();
        controller.setAllowed(false);
        health.run();
        controller.onLoss();
        assertTrue(scheduler.pending.isEmpty());
        assertEquals(0, transport.probes);
        controller.start(true);
        controller.onReady("bonded-watch");
        controller.onLoss();
        assertEquals(2000, scheduler.delay());
    }

    @Test public void bluetoothOffPausesAndOnResumesPreviouslyArmedPeer() {
        ready();
        controller.onLoss();
        Runnable old = scheduler.first();
        transport.available = false;
        controller.pause();
        old.run();
        controller.resume();
        assertTrue(scheduler.pending.isEmpty());
        transport.available = true;
        controller.resume();
        assertEquals(2000, scheduler.delay());
        scheduler.fire();
        assertEquals(1, transport.connects);
    }

    @Test public void samsungAndGarminNeverCompeteWithTraditionalRetries() {
        ready();
        controller.onLoss();
        Runnable old = scheduler.first();
        controller.setAllowed(false);
        old.run();
        controller.onReady("samsung-peer");
        controller.onLoss();
        assertTrue(scheduler.pending.isEmpty());
        controller.stop(); // Garmin owns its separate lifecycle.
        controller.resume();
        assertEquals(0, transport.connects);
    }

    @Test public void switchingModesCancelsPendingHealthTimeoutAndRetryCallbacks() {
        ready();
        Runnable staleProbe = scheduler.first();
        scheduler.fire();
        Runnable staleHealthTimeout = scheduler.first();
        controller.setAllowed(false);
        controller.stop();
        staleProbe.run();
        staleHealthTimeout.run();
        assertEquals(1, transport.probes);
        assertEquals(0, transport.resets);
        assertTrue(scheduler.pending.isEmpty());

        controller.start(true, "bonded-watch");
        Runnable staleRetry = scheduler.first();
        controller.setAllowed(false);
        controller.stop();
        controller.start(false, "bonded-watch"); // Samsung owner only.
        staleRetry.run();
        controller.onLoss();
        controller.resume();
        assertEquals(0, transport.connects);
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void repeatedStopStartCannotReuseOldSessionTimersOrTarget() {
        ready();
        controller.onLoss();
        Runnable old = scheduler.first();
        for (int i = 0; i < 3; i++) {
            controller.stop();
            controller.start(true);
            old.run();
            controller.resume();
        }
        assertTrue(scheduler.pending.isEmpty());
        assertEquals(0, transport.connects);
    }

    @Test public void reloadAutomaticallyResumesPreviouslyReadyPeerOnlyInTraditionalMode() {
        controller.start(false, "previously-ready-peer");
        assertTrue(scheduler.pending.isEmpty());
        controller.stop();
        controller.start(true, "previously-ready-peer");
        Runnable stale = scheduler.first();
        controller.stop();
        controller.start(true, "previously-ready-peer");
        stale.run();
        assertEquals(0, transport.connects);
        scheduler.fire();
        assertEquals("previously-ready-peer", transport.address);
        assertEquals(1, transport.connects);
    }

    @Test public void failedConnectAndSetupTimeoutKeepSixtySecondBackoff() {
        ready();
        controller.onLoss();
        transport.connectAccepted = false;
        scheduler.fire();
        assertEquals(60000, scheduler.delay());
        transport.connectAccepted = true;
        scheduler.fire();
        assertEquals(30000, scheduler.delay());
        scheduler.fire();
        assertEquals(1, transport.resets);
        assertEquals(60000, scheduler.delay());
    }

    @Test public void busyTransportDefersInsteadOfLaunchingSecondConnection() {
        ready();
        controller.onLoss();
        transport.busy = true;
        scheduler.fire();
        assertEquals(0, transport.connects);
        assertEquals(60000, scheduler.delay());
    }

    private static final class Scheduler implements SystemBondedWatchConnection.Scheduler {
        final Map<Runnable, Long> pending = new LinkedHashMap<>();
        @Override public void execute(Runnable action) { action.run(); }
        @Override public void later(Runnable action, long delay) { pending.put(action, delay); }
        @Override public void cancel(Runnable action) { pending.remove(action); }
        Runnable first() { return pending.keySet().iterator().next(); }
        long delay() { return pending.get(first()); }
        void fire() { Runnable r = first(); pending.remove(r); r.run(); }
    }

    private static final class Transport implements TraditionalBleReconnect.Transport {
        boolean available = true, busy, connectAccepted = true, probeAccepted = true;
        int connects, probes, resets;
        String address;
        @Override public boolean available() { return available; }
        @Override public boolean busy() { return busy; }
        @Override public boolean connect(String address) {
            connects++;
            this.address = address;
            return connectAccepted;
        }
        @Override public boolean probe() { probes++; return probeAccepted; }
        @Override public void reset() { resets++; }
    }
}
