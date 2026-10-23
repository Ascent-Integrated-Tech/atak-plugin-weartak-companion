package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PhonePositionActionsTest {
    private final PhonePositionActions actions = new PhonePositionActions();
    private final List<PhonePosition> sent = new ArrayList<>();
    private int unavailable;

    private PhonePosition fix(long time, double accuracy) {
        return new PhonePosition(38, -77, 120, accuracy, Double.NaN, time);
    }

    @Test public void staleFallbackIsImmediateAndFreshFixUpdatesSameActiveAlert() {
        PhonePosition old = fix(1000, 71);
        actions.observe(old);
        actions.request("emergency:same-uid", true, 10000, sent::add, () -> unavailable++);
        assertEquals(old, sent.get(0));
        assertEquals(71, sent.get(0).ce, 0);
        assertEquals(PhonePosition.UNKNOWN, old.le, 0);
        assertTrue(old.detailXml(10000).contains("stale='true'"));
        PhonePosition fresh = fix(11000, 4);
        actions.observe(fresh);
        actions.observe(fix(12000, 5));
        assertEquals(3, sent.size());
        assertEquals(fresh, sent.get(1));
        assertEquals(0, unavailable);
    }

    @Test public void noEverFixNotifiesImmediatelyThenFirstFixPublishesPosition() {
        actions.request("emergency:uid", true, 10000, sent::add, () -> unavailable++);
        assertEquals(1, unavailable);
        assertTrue(sent.isEmpty());
        actions.observe(fix(11000, 8));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).pointXml().contains("lat='38.0'"));
    }

    @Test public void repeatedAlertIsIdempotentAndCancellationStopsFollowups() {
        actions.observe(fix(1000, 8));
        for (int i = 0; i < 18; i++)
            actions.request("emergency:uid", true, 10000, sent::add, () -> unavailable++);
        assertEquals(1, sent.size());
        actions.cancel("emergency:uid");
        actions.observe(fix(11000, 5));
        assertEquals(1, sent.size());
        assertFalse(actions.hasActions());
    }

    @Test public void oneShotMarkerAndPliBothRepublishOnNewFixWithoutContinuingForever() {
        actions.observe(fix(1000, 20));
        actions.request("marker:same-uid", false, 10000, sent::add, () -> unavailable++);
        actions.request("pli", false, 10000, sent::add, () -> unavailable++);
        assertEquals(2, sent.size());
        actions.observe(fix(11000, 8));
        assertEquals(4, sent.size());
        actions.observe(fix(12000, 4));
        assertEquals(4, sent.size());
        assertFalse(actions.hasActions());
    }

    @Test public void olderOrLessAccurateSameTimestampNeverReplacesNewestFix() {
        PhonePosition best = fix(10000, 7);
        actions.observe(best);
        actions.observe(fix(9000, 1));
        actions.observe(fix(10000, 20));
        assertSame(best, actions.best());
        actions.observe(fix(10000, 2));
        assertEquals(2, actions.best().ce, 0);
    }

    @Test public void stopCancelsCallbacksButRetainsLastKnownFixForReconnect() {
        PhonePosition best = fix(1000, 50);
        actions.observe(best);
        actions.request("emergency:uid", true, 10000, sent::add, () -> unavailable++);
        actions.stop();
        assertSame(best, actions.best());
        actions.observe(fix(11000, 5));
        assertEquals(1, sent.size());
    }

    @Test public void boundedOneShotAcquisitionDoesNotExpireActiveEmergencies() {
        actions.request("pli", false, 1000, sent::add, () -> unavailable++);
        actions.request("emergency:uid", true, 1000, sent::add, () -> unavailable++);
        actions.expireOneShots(31000);
        actions.observe(fix(32000, 5));
        assertEquals(1, sent.size());
        assertTrue(actions.hasActions());
    }

    @Test public void unknownAccuracyAndTimestampAreNeverInvented() {
        PhonePosition fix = new PhonePosition(38, -77, Double.NaN, -1, Double.NaN, 0);
        assertEquals(PhonePosition.UNKNOWN, fix.ce, 0);
        assertEquals(PhonePosition.UNKNOWN, fix.le, 0);
        assertEquals(PhonePosition.UNKNOWN, fix.hae, 0);
        assertTrue(fix.detailXml(10000).contains("time='' stale='true' ageMillis='-1'"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroZeroCanNeverBecomeAPhoneFix() {
        new PhonePosition(0, 0, 0, 5, 5, 1000);
    }
}
