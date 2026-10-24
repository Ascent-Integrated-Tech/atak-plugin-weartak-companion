package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.*;

public class WatchSelfStatusTest {
    private static final long NOW = Instant.parse("2026-10-22T18:15:30Z").toEpochMilli();

    static JSONObject snapshot(String session, long seq, String level, Integer hr, long measuredAt)
            throws Exception {
        JSONObject payload = new JSONObject().put("version", 1).put("session", session)
                .put("seq", seq).put("deviceUid", "WATCH-UID").put("model", "WEAROS");
        payload.put("ppe", level == null ? JSONObject.NULL
                : new JSONObject().put("level", level).put("savedAt", "2026-10-22T18:00:00Z"));
        payload.put("physio", hr == null ? JSONObject.NULL : new JSONObject().put("hr", hr)
                .put("measuredAt", Instant.ofEpochMilli(measuredAt).toString()));
        return payload;
    }

    @Test public void exactSharedRemarksLineAndBiometricsSchema() throws Exception {
        WatchSelfStatus state = new WatchSelfStatus();
        assertTrue(state.accept("link", snapshot("s1", 0, "MOPP 2", 72, NOW), NOW));
        WatchSelfStatus.Contribution c = state.current(NOW);
        assertEquals("MOPP2; HR: 72 bpm @ 18:15:30Z", c.remarksLine());
        assertEquals("<biometrics version='1'><device sampleTime='2026-10-22T18:15:30Z'>"
                + "<model>WEAROS</model><uid>WATCH-UID</uid><hr sampleTime='2026-10-22T18:15:30Z'>72</hr></device></biometrics>",
                c.biometricsXml());
    }

    @Test public void noneIsDistinctFromMoppZeroButOmittedFromRemarks() throws Exception {
        WatchSelfStatus state = new WatchSelfStatus();
        state.accept("link", snapshot("s1", 0, "None", null, 0), NOW);
        assertEquals("", state.current(NOW).remarksLine());
        assertEquals("None", state.current(NOW).ppeLevel);
        state.accept("link", snapshot("s1", 1, "None", 64, NOW), NOW);
        assertEquals("HR: 64 bpm @ 18:15:30Z", state.current(NOW).remarksLine());
        state.accept("link", snapshot("s1", 2, "MOPP 0", null, 0), NOW);
        assertEquals("MOPP0", state.current(NOW).remarksLine());
        state.accept("link", snapshot("s1", 3, null, null, 0), NOW);
        assertTrue(state.current(NOW).isEmpty());
        assertEquals("", state.current(NOW).biometricsXml());
    }

    @Test public void physioFreshnessUsesMeasurementTimeNotReceiptTime() throws Exception {
        WatchSelfStatus state = new WatchSelfStatus();
        long measured = NOW - 119_000;
        state.accept("link", snapshot("s1", 0, "MOPP 1", 80, measured), NOW);
        WatchSelfStatus.Contribution fresh = state.current(NOW);
        assertEquals(80, fresh.hr.value, 0);
        assertEquals(measured + WatchSelfStatus.PHYSIO_MAX_AGE_MS, fresh.nextChangeAt);
        WatchSelfStatus.Contribution expired = state.current(NOW + 1_000);
        assertNull(expired.hr);
        assertEquals("MOPP1", expired.remarksLine());
        assertEquals("", expired.biometricsXml());

        state.accept("link", snapshot("s1", 1, null, 80, NOW - 121_000), NOW);
        assertTrue("an old sample received just now is not fresh", state.current(NOW).isEmpty());
    }

    @Test public void futureSamplesBeyondSkewAreWithheldUntilPlausible() throws Exception {
        WatchSelfStatus state = new WatchSelfStatus();
        state.accept("link", snapshot("s1", 0, null, 70, NOW + 31_000), NOW);
        assertNull(state.current(NOW).hr);
        assertEquals(NOW + 1_000, state.current(NOW).nextChangeAt);
        state.accept("link", snapshot("s1", 1, null, 70, NOW + 30_000), NOW);
        assertEquals(70, state.current(NOW).hr.value, 0);
    }

    @Test public void snapshotExpiresWithoutKeepalive() throws Exception {
        WatchSelfStatus state = new WatchSelfStatus();
        state.accept("link", snapshot("s1", 0, "MOPP 4", null, 0), NOW);
        assertEquals(NOW + WatchSelfStatus.SNAPSHOT_TTL_MS, state.current(NOW).nextChangeAt);
        assertFalse(state.current(NOW + WatchSelfStatus.SNAPSHOT_TTL_MS - 1).isEmpty());
        assertTrue(state.current(NOW + WatchSelfStatus.SNAPSHOT_TTL_MS).isEmpty());
        assertTrue("expiry does not revive", state.current(NOW).isEmpty());
    }

    @Test public void sequenceIsStrictAndSessionIsFixedPerLink() throws Exception {
        WatchSelfStatus state = new WatchSelfStatus();
        assertTrue(state.accept("link", snapshot("s1", 5, "MOPP 3", null, 0), NOW));
        assertFalse(state.accept("link", snapshot("s1", 5, "MOPP 1", null, 0), NOW));
        assertFalse(state.accept("link", snapshot("s1", 4, "MOPP 1", null, 0), NOW));
        assertEquals("MOPP3", state.current(NOW).remarksLine());
        try {
            state.accept("link", snapshot("s2", 9, "MOPP 1", null, 0), NOW);
            fail("a different session on the same link must not replace current state");
        } catch (IllegalArgumentException expected) {
            assertEquals("MOPP3", state.current(NOW).remarksLine());
        }
        assertTrue(state.accept("other-link", snapshot("s2", 0, "MOPP 2", null, 0), NOW));
        assertEquals("MOPP2", state.current(NOW).remarksLine());
    }

    @Test public void invalidSnapshotsAreRejectedWithoutChangingState() throws Exception {
        WatchSelfStatus state = new WatchSelfStatus();
        state.accept("link", snapshot("s1", 0, "MOPP 2", null, 0), NOW);
        JSONObject[] bad = {
                snapshot("s1", 1, "MOPP 5", null, 0),
                snapshot("s1", 1, "mopp 2", null, 0),
                snapshot("s1", 1, null, 300, NOW),
                snapshot("s1", 1, null, 19, NOW),
                snapshot("s1", 1, "MOPP 1", null, 0).put("version", 2),
                snapshot("s1", 1, "MOPP 1", null, 0).put("model", "PHONE"),
                snapshot("s1", 1, "MOPP 1", null, 0).put("session", ""),
                snapshot("s1", 1, "MOPP 1", null, 0).put("deviceUid", "a\nb"),
                snapshot("s1", 1, "MOPP 1", null, 0).put("seq", -1),
                snapshot("s1", 1, "MOPP 1", null, 0).put("seq", 1.5),
                snapshot("s1", 1, "MOPP 1", null, 0).put("ppe", new JSONObject().put("level", "MOPP 1")),
                snapshot("s1", 1, null, 70, NOW).put("physio", new JSONObject().put("hr", 70)),
                snapshot("s1", 1, "MOPP 1", null, 0).put("ppe", "MOPP 1"),
        };
        for (JSONObject payload : bad) {
            try {
                state.accept("link", payload, NOW);
                fail("accepted " + payload);
            } catch (IllegalArgumentException expected) {
                assertEquals("MOPP2", state.current(NOW).remarksLine());
            }
        }
    }

    @Test public void optionalSkinTemperatureAndExertionUseTheirOwnSampleTimes() throws Exception {
        WatchSelfStatus state = new WatchSelfStatus();
        JSONObject payload = snapshot("s1", 0, "MOPP 1", null, 0).put("physio", new JSONObject()
                .put("sktF", 93.44).put("sktFMeasuredAt", "2026-10-22T18:14:00Z")
                .put("exert", 0.456).put("exertMeasuredAt", "2026-10-22T18:15:30Z"));
        assertTrue(state.accept("link", payload, NOW));
        WatchSelfStatus.Contribution c = state.current(NOW);
        assertNull(c.hr);
        assertEquals("MOPP1; Skin: 93.4F @ 18:14:00Z; Exert: 46% @ 18:15:30Z", c.remarksLine());
        assertEquals("<biometrics version='1'><device sampleTime='2026-10-22T18:15:30Z'>"
                + "<model>WEAROS</model><uid>WATCH-UID</uid>"
                + "<skt sampleTime='2026-10-22T18:14:00Z'>93.4</skt>"
                + "<exert sampleTime='2026-10-22T18:15:30Z'>0.46</exert></device></biometrics>",
                c.biometricsXml());
        WatchSelfStatus.Contribution later = state.current(NOW + 31_000);
        assertNull("skin sample is 151 s old", later.skinTempF);
        assertNotNull(later.exertion);

        JSONObject garmin = snapshot("s1", 1, null, null, 0).put("model", "GARMIN")
                .put("physio", new JSONObject().put("sktF", 95).put("sktFMeasuredAt", "2026-10-22T18:15:30Z"));
        state.accept("link", garmin, NOW);
        assertEquals("non-WEAROS skt is Celsius", "35.0", state.current(NOW).skinTempText());

        JSONObject[] bad = {
                snapshot("s1", 2, null, null, 0).put("physio", new JSONObject()),
                snapshot("s1", 2, null, null, 0).put("physio", new JSONObject().put("hr", JSONObject.NULL)),
                snapshot("s1", 2, null, null, 0).put("physio", new JSONObject().put("exert", 1.1)
                        .put("exertMeasuredAt", "2026-10-22T18:15:30Z")),
                snapshot("s1", 2, null, null, 0).put("physio", new JSONObject().put("sktF", 120)
                        .put("sktFMeasuredAt", "2026-10-22T18:15:30Z")),
                snapshot("s1", 2, null, null, 0).put("physio", new JSONObject().put("exert", 0.5)),
        };
        for (JSONObject invalid : bad) {
            try {
                state.accept("link", invalid, NOW);
                fail("accepted " + invalid);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void legacySavedPpeWithoutSaveTimeIsAccepted() throws Exception {
        WatchSelfStatus state = new WatchSelfStatus();
        assertTrue(state.accept("link", snapshot("s1", 0, null, null, 0).put("ppe",
                new JSONObject().put("level", "MOPP 3").put("savedAt", JSONObject.NULL)), NOW));
        assertEquals("MOPP3", state.current(NOW).remarksLine());
    }

    @Test public void clearRemovesEverything() throws Exception {
        WatchSelfStatus state = new WatchSelfStatus();
        state.accept("link", snapshot("s1", 0, "MOPP 2", 72, NOW), NOW);
        state.clear();
        assertSame(WatchSelfStatus.Contribution.NONE, state.current(NOW));
        assertTrue(state.accept("link", snapshot("s1", 0, "MOPP 2", null, 0), NOW));
    }
}
