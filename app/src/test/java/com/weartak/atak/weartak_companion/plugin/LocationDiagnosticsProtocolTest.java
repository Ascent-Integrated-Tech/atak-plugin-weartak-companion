package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class LocationDiagnosticsProtocolTest {
    private static final String SESSION = "01234567-89ab-cdef-0123-456789abcdef";

    @Test
    public void capabilityRequiresVersionAndMatchingSession() throws Exception {
        JSONObject message = LocationDiagnosticsProtocol.capability(SESSION, true);
        assertNotNull(LocationDiagnosticsProtocol.parse(
                message, LocationDiagnosticsProtocol.CAPABILITY, SESSION));
        assertNull(LocationDiagnosticsProtocol.parse(
                message, LocationDiagnosticsProtocol.CAPABILITY,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        message.put("version", 2);
        assertNull(LocationDiagnosticsProtocol.parse(
                message, LocationDiagnosticsProtocol.CAPABILITY, SESSION));
    }

    @Test
    public void stopMessageIsSessionFencedAndContainsNoAdditionalData() throws Exception {
        JSONObject stop = new JSONObject().put("msg_type", LocationDiagnosticsProtocol.STOP)
                .put("version", LocationDiagnosticsProtocol.VERSION).put("session", SESSION);
        assertNotNull(LocationDiagnosticsProtocol.parse(stop, LocationDiagnosticsProtocol.STOP, SESSION));
        assertNull(LocationDiagnosticsProtocol.parse(stop, LocationDiagnosticsProtocol.STOP,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        stop.put("payload", new JSONObject());
        assertNull(LocationDiagnosticsProtocol.parse(stop, LocationDiagnosticsProtocol.STOP, SESSION));
    }

    @Test
    public void snapshotQualityNeverCallsMissingGnssSupportGood() throws Exception {
        JSONObject sample = goodSample();
        assertEquals("GOOD", LocationDiagnosticsProtocol.quality(sample));
        sample.put("measurementsSupported", false);
        assertEquals("UNKNOWN", LocationDiagnosticsProtocol.quality(sample));
        sample.put("measurementsSupported", true).put("maxAgcDb", JSONObject.NULL);
        assertEquals("UNKNOWN", LocationDiagnosticsProtocol.quality(sample));
        sample.put("maxAgcDb", 2d).put("used", 2);
        assertEquals("DEGRADED", LocationDiagnosticsProtocol.quality(sample));
        sample.put("error", "location permission denied");
        assertEquals("UNAVAILABLE", LocationDiagnosticsProtocol.quality(sample));
    }

    @Test
    public void incomingSnapshotsRejectCoordinatesIdentifiersAndUnknownFields() throws Exception {
        JSONObject envelope = LocationDiagnosticsProtocol.snapshot(SESSION, goodSample());
        assertNotNull(LocationDiagnosticsProtocol.parse(
                envelope, LocationDiagnosticsProtocol.SNAPSHOT, SESSION));
        envelope.getJSONObject("payload").put("lat", 47.1d);
        assertNull(LocationDiagnosticsProtocol.parse(
                envelope, LocationDiagnosticsProtocol.SNAPSHOT, SESSION));
        envelope.getJSONObject("payload").remove("lat");
        envelope.getJSONObject("payload").put("deviceAlias", "watch");
        assertNull(LocationDiagnosticsProtocol.parse(
                envelope, LocationDiagnosticsProtocol.SNAPSHOT, SESSION));
        assertNull(LocationDiagnosticsProtocol.parse(
                new JSONObject().put("msg_type", "gateway_cot").put("version", 1)
                        .put("session", SESSION).put("cot", "<event/>"),
                LocationDiagnosticsProtocol.SNAPSHOT, SESSION));
    }

    @Test
    public void outboundDiagnosticsFrameHasStrictSizeLimit() throws Exception {
        JSONObject oversized = new JSONObject().put("msg_type", LocationDiagnosticsProtocol.HELLO)
                .put("version", LocationDiagnosticsProtocol.VERSION).put("session", SESSION)
                .put("reason", new String(new char[600]).replace('\0', 'x'));
        assertNull(LocationDiagnosticsProtocol.parse(
                oversized, LocationDiagnosticsProtocol.HELLO, SESSION));
    }

    private static JSONObject goodSample() throws Exception {
        return new JSONObject()
                .put("source", "gps")
                .put("ageMs", 1_000L)
                .put("accuracyM", 5d)
                .put("speedMps", 1d)
                .put("speedAccuracyMps", 0.1d)
                .put("bearingAccuracyDeg", 2d)
                .put("satellites", 10)
                .put("used", 7)
                .put("meanCn0DbHz", 28d)
                .put("maxAgcDb", 2d)
                .put("satelliteStatusSupported", true)
                .put("measurementsSupported", true)
                .put("providerAvailable", true)
                .put("quality", "GOOD")
                .put("reason", "quality thresholds met");
    }
}
