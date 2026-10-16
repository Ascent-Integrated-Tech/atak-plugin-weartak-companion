package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneManagementReleaseTest {
    private static final String FIRST = "12345678-1234-1234-1234-123456789abc";
    private static final String SECOND = "abcdefab-cdef-abcd-efab-cdefabcdefab";

    @Test public void onlyOriginalPeerIdAndGenerationCanAcknowledge() {
        PhoneManagementRelease release = new PhoneManagementRelease();
        Object peer = new Object();
        long token = release.begin(peer, FIRST, 0);
        assertEquals(PhoneManagementRelease.Result.IGNORED,
                release.acknowledge(new Object(), FIRST, token, 1, true));
        assertEquals(PhoneManagementRelease.Result.IGNORED,
                release.acknowledge(peer, SECOND, token, 1, true));
        assertEquals(PhoneManagementRelease.Result.IGNORED,
                release.acknowledge(peer, FIRST, token + 1, 1, true));
        assertTrue(release.isPending());
        assertEquals(PhoneManagementRelease.Result.SUCCEEDED,
                release.acknowledge(peer, FIRST, token, 2, true));
        assertEquals(PhoneManagementRelease.Result.IGNORED,
                release.acknowledge(peer, FIRST, token, 3, true));
    }

    @Test public void refusalAllowsRetryButOldAckAndTimeoutCannotFinishIt() {
        PhoneManagementRelease release = new PhoneManagementRelease();
        Object peer = new Object();
        long first = release.begin(peer, FIRST, 0);
        assertEquals(PhoneManagementRelease.Result.REFUSED,
                release.acknowledge(peer, FIRST, first, 1, false));
        long second = release.begin(peer, SECOND, 2);
        assertEquals(PhoneManagementRelease.Result.IGNORED,
                release.acknowledge(peer, FIRST, first, 3, true));
        assertEquals(PhoneManagementRelease.Result.IGNORED, release.timeout(first, 20000));
        assertEquals(PhoneManagementRelease.Result.SUCCEEDED,
                release.acknowledge(peer, SECOND, second, 4, true));
    }

    @Test public void unsupportedWatchTimesOutAndLateAckCannotDisconnect() {
        PhoneManagementRelease release = new PhoneManagementRelease();
        Object peer = new Object();
        long token = release.begin(peer, FIRST, 10);
        assertEquals(PhoneManagementRelease.Result.IGNORED, release.timeout(token, 15009));
        assertEquals(PhoneManagementRelease.Result.TIMED_OUT, release.timeout(token, 15010));
        assertFalse(release.isPending());
        assertEquals(PhoneManagementRelease.Result.IGNORED,
                release.acknowledge(peer, FIRST, token, 15011, true));
    }

    @Test public void malformedAckNeverDefaultsToSuccessOrRefusal() {
        PhoneManagementRelease release = new PhoneManagementRelease();
        Object peer = new Object();
        long token = release.begin(peer, FIRST, 0);
        for (Object ok : new Object[] {null, "true", "false", 1, 0}) {
            assertEquals(PhoneManagementRelease.Result.IGNORED,
                    release.acknowledge(peer, FIRST, token, 1, ok));
        }
        assertEquals(PhoneManagementRelease.Result.IGNORED,
                release.acknowledge(peer, null, token, 1, true));
        assertEquals(PhoneManagementRelease.Result.IGNORED,
                release.acknowledge(peer, 1, token, 1, true));
        assertTrue(release.isPending());
        assertEquals(PhoneManagementRelease.Result.TIMED_OUT, release.timeout(token, 15000));
    }

    @Test public void ackAtDeadlineIsNotSuccessEvenIfTimeoutCallbackHasNotRun() {
        PhoneManagementRelease release = new PhoneManagementRelease();
        Object peer = new Object();
        long token = release.begin(peer, FIRST, 0);
        assertEquals(PhoneManagementRelease.Result.TIMED_OUT,
                release.acknowledge(peer, FIRST, token, PhoneManagementRelease.TIMEOUT_MILLIS, true));
    }

    @Test public void disconnectOrLifecycleCancelFencesReplacementSession() {
        PhoneManagementRelease release = new PhoneManagementRelease();
        Object oldPeer = new Object();
        long oldToken = release.begin(oldPeer, FIRST, 0);
        release.cancel();
        Object nextPeer = new Object();
        long nextToken = release.begin(nextPeer, SECOND, 100);
        assertEquals(PhoneManagementRelease.Result.IGNORED,
                release.acknowledge(oldPeer, FIRST, oldToken, 101, true));
        assertEquals(PhoneManagementRelease.Result.IGNORED, release.timeout(oldToken, 20000));
        assertTrue(release.isPending());
        assertEquals(PhoneManagementRelease.Result.SUCCEEDED,
                release.acknowledge(nextPeer, SECOND, nextToken, 102, true));
    }

    @Test(expected = IllegalStateException.class)
    public void repeatedDisconnectDoesNotReplacePendingRequest() {
        PhoneManagementRelease release = new PhoneManagementRelease();
        release.begin(new Object(), FIRST, 0);
        release.begin(new Object(), SECOND, 1);
    }
}
