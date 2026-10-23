package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.*;

public class SourceCotTimingTest {
    private static final long TIME = 1792797107322L;
    private static final long MINUTES_10 = 600000L;
    private static final long YEAR = 31536000000L;

    @Test public void tenMinuteSourceWinsOverSynthesizedYearEvenForArchivedItems() {
        SourceCotTiming received = new SourceCotTiming(TIME, TIME - 1234, TIME + MINUTES_10);
        SourceCotTiming exported = new SourceCotTiming(TIME + 5000, TIME + 5000, TIME + YEAR);
        for (boolean local : new boolean[]{false, true}) {
            SourceCotTiming result = SourceCotTiming.select(received, exported, local);
            assertEquals(TIME, result.time);
            assertEquals(TIME - 1234, result.start);
            assertEquals(TIME + MINUTES_10, result.stale);
            assertNotEquals(TIME + YEAR, result.stale);
        }
    }

    @Test public void expiredTimingIsNeverExtendedOrRebasedOnReceipt() {
        SourceCotTiming expired = new SourceCotTiming(TIME - MINUTES_10, TIME - MINUTES_10, TIME - 1);
        SourceCotTiming laterExport = new SourceCotTiming(TIME, TIME, TIME + YEAR);
        assertEquals(TIME - 1, SourceCotTiming.select(expired, laterExport, false).stale);
    }

    @Test public void onlyPersistentLocalPointsKeepTheExporterDefault() {
        SourceCotTiming exported = new SourceCotTiming(TIME, TIME, TIME + YEAR);
        assertSame(exported, SourceCotTiming.select(null, exported, true));
        assertNull(SourceCotTiming.select(null, exported, false));
    }

    @Test public void retainedMetadataRoundTripsAllSourceTimesExactly() {
        SourceCotTiming source = new SourceCotTiming(TIME, TIME - 2000, TIME + MINUTES_10);
        assertEquals(source.encode(), SourceCotTiming.decode(source.encode()).encode());
        assertEquals(source.fingerprint("geometry"), SourceCotTiming.decode(source.encode()).fingerprint("geometry"));
    }

    @Test public void malformedOrNonpositiveMetadataCannotBecomeAValidLifetime() {
        for (String value : new String[]{"", "1|2", "1|2|3|4", "0|1|2", "1|-1|2", "1|2|0", "a|2|3"}) {
            try {
                SourceCotTiming.decode(value);
                fail(value);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test public void boundedReceiveCacheKeepsNewestPerUidAndClearsRemovedItems() {
        SourceCotTiming.Cache cache = new SourceCotTiming.Cache();
        SourceCotTiming original = new SourceCotTiming(TIME, TIME, TIME + MINUTES_10);
        SourceCotTiming refreshed = new SourceCotTiming(TIME + 1, TIME + 1, TIME + MINUTES_10 + 1);
        cache.put("track", original);
        cache.put("track", refreshed);
        assertSame(refreshed, cache.get("track"));
        for (int i = 0; i < SourceCotTiming.Cache.CAPACITY; i++) cache.put("track" + i, original);
        assertNull(cache.get("track"));
        assertSame(original, cache.get("track0"));
        cache.remove("track0");
        assertNull(cache.get("track0"));
        cache.clear();
        assertNull(cache.get("track1"));
    }

    @Test public void expiryOnlyChangeIsForwardedOnceAndInvalidatesTheOldDeleteRevision() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        SourceCotTiming first = new SourceCotTiming(TIME, TIME, TIME + MINUTES_10);
        SourceCotTiming extended = new SourceCotTiming(TIME, TIME, TIME + 2 * MINUTES_10);
        PhoneMarkerRelay.Snapshot old = snapshot(first), updated = snapshot(extended);
        assertTrue(relay.forward(old, 7, 7, TIME, (json, session) -> true));
        String oldToken = relay.deletionToken("source-track");
        assertFalse(relay.forward(old, 7, 7, TIME + 1, (json, session) -> true));
        assertTrue(relay.forward(updated, 7, 7, TIME + 2, (json, session) -> true));
        assertFalse(relay.forward(updated, 7, 7, TIME + 3, (json, session) -> true));
        assertFalse(relay.acceptsDeletion(updated, oldToken, 7));
    }

    @Test public void watchCopyWithSourceTimingRemainsEchoSuppressed() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        PhoneMarkerRelay.Snapshot marker = snapshot(new SourceCotTiming(TIME, TIME, TIME + MINUTES_10));
        relay.watchImported(marker);
        assertFalse(relay.forward(marker, 7, 7, TIME, (json, session) -> true));
    }

    private static PhoneMarkerRelay.Snapshot snapshot(SourceCotTiming timing) {
        return new PhoneMarkerRelay.Snapshot("source-track", "a-h-A-C-H", 38, -77, 0, 9999999, 9999999,
                timing.time, timing.start, timing.stale, false,
                timing.fingerprint("unchanged geometry and detail"), "<event/>");
    }
}
