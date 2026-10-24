package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import java.time.Instant;

import static com.weartak.atak.weartak_companion.plugin.WatchSelfStatusTest.snapshot;
import static org.junit.Assert.*;

public class SelfPliAugmenterTest {
    private static final long NOW = Instant.parse("2026-10-22T18:15:30Z").toEpochMilli();

    private static final class Fake implements SelfPliAugmenter.Host, SelfPliAugmenter.Clock {
        String remarks = "";
        String managed = "";
        String biometrics = "";
        int remarkWrites, biometricWrites;
        long now = NOW;
        Runnable scheduled;
        long delay = -1;

        @Override public String userRemarks() { return remarks; }
        @Override public void setUserRemarks(String value) { remarks = value; remarkWrites++; }
        @Override public String managedRemarks() { return managed; }
        @Override public void setManagedRemarks(String line) { managed = line; }
        @Override public void setBiometrics(WatchSelfStatus.Contribution c) {
            biometrics = c.biometricsXml();
            biometricWrites++;
        }
        @Override public void log(String message) { }
        @Override public long now() { return now; }
        @Override public void schedule(Runnable action, long delayMs) { scheduled = action; delay = delayMs; }
        @Override public void cancel(Runnable action) { if (scheduled == action) { scheduled = null; delay = -1; } }

        void advance(long ms) {
            now += ms;
            Runnable action = scheduled;
            if (action != null && ms >= delay) {
                scheduled = null;
                action.run();
            }
        }
    }

    private static SelfPliAugmenter start(Fake fake) {
        SelfPliAugmenter augmenter = new SelfPliAugmenter(fake, fake);
        augmenter.start();
        return augmenter;
    }

    @Test public void appendsOneManagedLineAfterUserRemarksAndPublishesBiometrics() throws Exception {
        Fake fake = new Fake();
        fake.remarks = "Medic, call 123";
        SelfPliAugmenter augmenter = start(fake);
        assertTrue(augmenter.accept("wearos:1", snapshot("s1", 0, "MOPP 2", 72, NOW)));
        assertEquals("Medic, call 123\nPPE: MOPP 2; HR: 72 bpm @ 18:15:30Z", fake.remarks);
        assertEquals("PPE: MOPP 2; HR: 72 bpm @ 18:15:30Z", fake.managed);
        assertTrue(fake.biometrics.contains("<uid>WATCH-UID</uid><hr>72</hr>"));
        assertEquals("Medic, call 123\nPPE: MOPP 2; HR: 72 bpm @ 18:15:30Z", augmenter.phoneRemarks());
    }

    @Test public void repeatedSnapshotsAreIdempotentAndUpdatesReplaceTheLine() throws Exception {
        Fake fake = new Fake();
        SelfPliAugmenter augmenter = start(fake);
        augmenter.accept("wearos:1", snapshot("s1", 0, "MOPP 2", null, 0));
        augmenter.accept("wearos:1", snapshot("s1", 1, "MOPP 2", null, 0));
        augmenter.accept("wearos:1", snapshot("s1", 2, "MOPP 2", null, 0));
        assertEquals("PPE: MOPP 2", fake.remarks);
        assertEquals(1, fake.remarkWrites);
        augmenter.accept("wearos:1", snapshot("s1", 3, "MOPP 4", null, 0));
        assertEquals("PPE: MOPP 4", fake.remarks);
    }

    @Test public void disconnectRemovesOnlyWatchContributions() throws Exception {
        Fake fake = new Fake();
        fake.remarks = "line one\nline two";
        SelfPliAugmenter augmenter = start(fake);
        augmenter.accept("garmin:r1", snapshot("s1", 0, "MOPP 1", 65, NOW));
        augmenter.clear("watch disconnected");
        assertEquals("line one\nline two", fake.remarks);
        assertEquals("", fake.managed);
        assertEquals("", fake.biometrics);
        assertNull(fake.scheduled);
    }

    @Test public void physioExpiresOnScheduleWhilePpeRemainsThenSnapshotTtlClearsAll() throws Exception {
        Fake fake = new Fake();
        SelfPliAugmenter augmenter = start(fake);
        augmenter.accept("wearos:1", snapshot("s1", 0, "None", 90, NOW));
        assertEquals(WatchSelfStatus.PHYSIO_MAX_AGE_MS, fake.delay);
        fake.advance(WatchSelfStatus.PHYSIO_MAX_AGE_MS);
        assertEquals("PPE: None", fake.remarks);
        assertEquals("", fake.biometrics);
        fake.advance(WatchSelfStatus.SNAPSHOT_TTL_MS - WatchSelfStatus.PHYSIO_MAX_AGE_MS);
        assertEquals("", fake.remarks);
        assertNull(fake.scheduled);
    }

    @Test public void userEditedManagedLineIsNeverStripped() throws Exception {
        Fake fake = new Fake();
        SelfPliAugmenter augmenter = start(fake);
        augmenter.accept("wearos:1", snapshot("s1", 0, "MOPP 3", null, 0));
        fake.remarks = "PPE: MOPP 3 (checked by medic)";
        augmenter.clear("watch disconnected");
        assertEquals("PPE: MOPP 3 (checked by medic)", fake.remarks);
        assertEquals("", fake.managed);
    }

    @Test public void startupRemovesLineLeftByPreviousProcessButKeepsUserText() {
        Fake fake = new Fake();
        fake.remarks = "keep me\nPPE: MOPP 2";
        fake.managed = "PPE: MOPP 2";
        start(fake);
        assertEquals("keep me", fake.remarks);
        assertEquals("", fake.managed);

        Fake untouched = new Fake();
        untouched.remarks = "PPE: MOPP 2";
        start(untouched);
        assertEquals("unmanaged text is never stripped", "PPE: MOPP 2", untouched.remarks);
        assertEquals(0, untouched.remarkWrites);
    }

    @Test public void rejectedOrStaleOrderSnapshotsChangeNothing() throws Exception {
        Fake fake = new Fake();
        SelfPliAugmenter augmenter = start(fake);
        augmenter.accept("wearos:1", snapshot("s1", 3, "MOPP 2", null, 0));
        assertFalse(augmenter.accept("wearos:1", snapshot("s1", 2, "MOPP 0", null, 0)));
        assertFalse(augmenter.accept("wearos:1", snapshot("s1", 4, "MOPP 9", null, 0)));
        assertFalse(augmenter.accept("wearos:1", null));
        assertEquals("PPE: MOPP 2", fake.remarks);
    }

    @Test public void remarksHelperHandlesEmptyAndUnmanagedText() {
        assertEquals("", SelfStatusRemarks.apply(null, null, ""));
        assertEquals("PPE: None", SelfStatusRemarks.apply("", "", "PPE: None"));
        assertEquals("a\nPPE: None", SelfStatusRemarks.apply("a\nPPE: MOPP 1", "PPE: MOPP 1", "PPE: None"));
        assertEquals("a", SelfStatusRemarks.strip("a\nPPE: MOPP 1", "PPE: MOPP 1"));
        assertEquals("a PPE: MOPP 1", SelfStatusRemarks.strip("a PPE: MOPP 1", "PPE: MOPP 1"));
    }
}
