package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PairedWatchTrackSuppressorTest {
    private static final String WATCH = "WEAROS_ffd863ef0fd282b4";
    private static final String PHONE = "ANDROID-8c3a66b30c4e7e8c";

    @Test public void suppressesOnlyTheExactPairedWatchTrack() {
        assertTrue(PairedWatchTrackSuppressor.suppresses(WATCH, WATCH, PHONE));
        assertFalse(PairedWatchTrackSuppressor.suppresses(WATCH, WATCH + "-9-1-1", PHONE));
        assertFalse(PairedWatchTrackSuppressor.suppresses(WATCH, "WEAROS_0000000000000000", PHONE));
        assertFalse(PairedWatchTrackSuppressor.suppresses(WATCH, PHONE, PHONE));
    }

    @Test public void keepsTracksWhenNoWatchIsPairedAndNeverRemovesSelf() {
        assertFalse(PairedWatchTrackSuppressor.suppresses(null, WATCH, PHONE));
        assertFalse(PairedWatchTrackSuppressor.suppresses("", WATCH, PHONE));
        assertFalse(PairedWatchTrackSuppressor.suppresses(PHONE, PHONE, PHONE));
    }
}
