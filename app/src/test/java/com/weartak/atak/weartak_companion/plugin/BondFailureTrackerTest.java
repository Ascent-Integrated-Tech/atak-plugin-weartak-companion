package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.*;

public class BondFailureTrackerTest {
    @Test public void rapidRejectionIsLikelyStaleAndSecondFailureLatchesStop() {
        BondFailureTracker tracker = new BondFailureTracker();
        BondFailureTracker.Failure first = tracker.rejected("watch", 400, -1, 1000);
        assertTrue(first.likelyStale);
        assertFalse(first.blocked);
        BondFailureTracker.Failure second = tracker.rejected("watch", 400, -1, 2000);
        assertEquals(2, second.count);
        assertTrue(second.blocked);
        assertSame(second, tracker.blocked("watch"));
        assertSame(second, tracker.rejected("watch", 400, 104, 999999));
    }

    @Test public void thresholdIsInclusiveWithoutClassifyingSlowFirstFailure() {
        BondFailureTracker tracker = new BondFailureTracker();
        assertTrue(tracker.rejected("a", 5000, -1, 10000).likelyStale);
        assertFalse(tracker.rejected("b", 5001, -1, 10000).likelyStale);
        assertFalse(tracker.rejected("c", -1, -1, 10000).likelyStale);
    }

    @Test public void exposedLeAuthCodesAreClassifiedAndPreserved() {
        for (int reason : new int[]{104, 105}) {
            BondFailureTracker.Failure failure = new BondFailureTracker()
                    .rejected("watch", 6000, reason, 10000);
            assertTrue(failure.likelyStale);
            assertEquals(reason, failure.reason);
        }
    }

    @Test public void explicitCancellationsDoNotTriggerRecoveryOrCountAsRejections() {
        for (int reason : new int[]{3, 8}) {
            BondFailureTracker tracker = new BondFailureTracker();
            tracker.rejected("watch", 400, 1, 1000);
            assertNull(tracker.rejected("watch", 400, reason, 2000));
            assertNull(tracker.blocked("watch"));
            assertEquals(1, tracker.rejected("watch", 400, 1, 3000).count);
        }
    }

    @Test public void repeatedSlowFailuresAreStillClassifiedWithinWindow() {
        BondFailureTracker tracker = new BondFailureTracker();
        assertFalse(tracker.rejected("watch", 30000, 1, 40000).likelyStale);
        BondFailureTracker.Failure next = tracker.rejected("watch", 30000, 1, 80000);
        assertTrue(next.likelyStale);
        assertTrue(next.blocked);
    }

    @Test public void unrelatedWatchesAndOldUnblockedFailuresDoNotShareBudget() {
        BondFailureTracker tracker = new BondFailureTracker();
        tracker.rejected("a", 400, 1, 1000);
        assertEquals(1, tracker.rejected("b", 400, 1, 2000).count);
        assertEquals(1, tracker.rejected("a", 400, 1, 121001).count);
    }

    @Test public void retryOrSuccessfulBondExplicitlyRestoresBudget() {
        BondFailureTracker tracker = new BondFailureTracker();
        tracker.rejected("watch", 400, 1, 1000);
        tracker.rejected("watch", 400, 1, 2000);
        tracker.clear("watch");
        assertNull(tracker.blocked("watch"));
        assertEquals(1, tracker.rejected("watch", 400, 1, 3000).count);
    }

    @Test public void stableAdvertisingIdentityIgnoresAddressAndCachedAliasChanges() {
        assertEquals(BondFailureTracker.identity("short", "WT-ODIN-WEARTAK", "cached alias"),
                BondFailureTracker.identity(null, "WT-odin-weartak", "different alias"));
        assertEquals("odin-weartak", BondFailureTracker.identity(null, null, " WT-ODIN-WEARTAK "));
        assertEquals(BondFailureTracker.identity(null, null, null),
                BondFailureTracker.identity("", "", ""));
    }
}
