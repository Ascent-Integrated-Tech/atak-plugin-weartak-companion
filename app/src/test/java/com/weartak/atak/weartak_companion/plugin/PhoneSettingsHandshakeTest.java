package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.weartak.atak.weartak_companion.plugin.PhoneSettingsHandshake.Action.*;

public class PhoneSettingsHandshakeTest {
    @Test public void readyAndQueuedApplyDoNotConfirmManagement() {
        PhoneSettingsHandshake h = new PhoneSettingsHandshake();
        assertEquals(REQUEST, h.begin(0));
        long token = h.token();
        assertEquals(NONE, h.snapshot(token, false, true, 1));
        assertEquals(APPLY, h.snapshot(token, true, false, 2));
        assertEquals(NONE, h.snapshot(token, true, true, 3));
        assertFalse(h.isConfirmed());
        assertEquals(REQUEST, h.acknowledge(token, true, 4));
        assertEquals(NONE, h.snapshot(token, true, false, 5));
        assertEquals(CONFIRMED, h.snapshot(token, true, true, 6));
        assertTrue(h.isConfirmed());
        assertEquals(NONE, h.timeout(token, 100_000));
    }

    @Test public void missingSnapshotRetriesAreBounded() {
        PhoneSettingsHandshake h = new PhoneSettingsHandshake();
        h.begin(0);
        long token = h.token();
        assertEquals(NONE, h.timeout(token, 9_999));
        assertEquals(REQUEST, h.timeout(token, 10_000));
        assertEquals(REQUEST, h.timeout(token, 20_000));
        assertEquals(FAILED, h.timeout(token, 30_000));
        assertTrue(h.isFailed());
        assertEquals(NONE, h.snapshot(token, true, true, 31_000));
    }

    @Test public void lostAckRetriesSameApplyAndLostConfirmationRetriesRead() {
        PhoneSettingsHandshake h = new PhoneSettingsHandshake();
        h.begin(0);
        long token = h.token();
        h.snapshot(token, true, false, 1);
        assertEquals(APPLY, h.timeout(token, 10_001));
        assertEquals(REQUEST, h.acknowledge(token, true, 10_002));
        assertEquals(REQUEST, h.timeout(token, 20_002));
        assertEquals(CONFIRMED, h.snapshot(token, true, true, 20_003));
    }

    @Test public void disconnectOrUnlockCancelsAllOldWork() {
        PhoneSettingsHandshake h = new PhoneSettingsHandshake();
        h.begin(0);
        long old = h.token();
        h.cancel();
        assertEquals(NONE, h.timeout(old, 10_000));
        assertEquals(NONE, h.snapshot(old, true, true, 10_000));
        assertEquals(NONE, h.acknowledge(old, true, 10_000));
        h.begin(10_001);
        assertEquals(NONE, h.snapshot(old, true, true, 10_002));
        assertEquals(APPLY, h.snapshot(h.token(), true, false, 10_003));
    }

    @Test public void rejectedApplyFailsWithoutClaimingManaged() {
        PhoneSettingsHandshake h = new PhoneSettingsHandshake();
        h.begin(0);
        h.snapshot(h.token(), true, false, 1);
        assertEquals(FAILED, h.acknowledge(h.token(), false, 2));
        assertTrue(h.isFailed());
        assertFalse(h.isConfirmed());
    }

    @Test public void unmanagedConfirmationEventuallyFailsRatherThanReapplyingBlindly() {
        PhoneSettingsHandshake h = new PhoneSettingsHandshake();
        h.begin(0);
        long token = h.token();
        h.snapshot(token, true, false, 1);
        h.acknowledge(token, true, 2);
        assertEquals(NONE, h.snapshot(token, true, false, 3));
        assertEquals(REQUEST, h.timeout(token, 10_002));
        assertEquals(REQUEST, h.timeout(token, 20_002));
        assertEquals(FAILED, h.timeout(token, 30_002));
        assertFalse(h.isConfirmed());
    }

    @Test public void tenReconnectCyclesRejectOldResponsesAndRecoverLostMessages() {
        PhoneSettingsHandshake h = new PhoneSettingsHandshake();
        long previous = h.token();
        for (int cycle = 0; cycle < 10; cycle++) {
            h.cancel();
            assertEquals(REQUEST, h.begin(0));
            long token = h.token();
            assertEquals(NONE, h.acknowledge(previous, true, 1));
            assertEquals(NONE, h.snapshot(previous, true, true, 1));
            assertEquals(REQUEST, h.timeout(token, 10_000));
            assertEquals(APPLY, h.snapshot(token, true, false, 10_001));
            assertEquals(APPLY, h.timeout(token, 20_001));
            assertEquals(REQUEST, h.acknowledge(token, true, 20_002));
            assertEquals(CONFIRMED, h.snapshot(token, true, true, 20_003));
            previous = token;
        }
    }
}
