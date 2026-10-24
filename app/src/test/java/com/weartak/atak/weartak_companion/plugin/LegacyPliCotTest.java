package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

public class LegacyPliCotTest {
    private static final long NOW = 1792699200000L;
    private final LegacyPliCot.Metadata phone = new LegacyPliCot.Metadata(
            "Cyan", "Medic", "ATAK", "5.6.0.runtime-CIV", "PHONE", "36");

    @Test public void watchIdentityLocationAndMetadataCannotReplacePhoneEntity() throws Exception {
        JSONObject watch = new JSONObject().put("uid", "WATCH").put("callsign", "WATCH-CS")
                .put("lat", 1).put("lon", 2).put("hae", 3)
                .put("team", "Red").put("role", "Sniper")
                .put("takv", new JSONObject().put("device", "WATCH-DEVICE"));
        Document event = parse(build(watch, phone));
        assertEquals("PHONE-UID", event.getDocumentElement().getAttribute("uid"));
        assertEquals("a-f-G-U-C", event.getDocumentElement().getAttribute("type"));
        assertEquals("h-g-i-g-o", event.getDocumentElement().getAttribute("how"));
        assertEquals("PHONE-CS", first(event, "contact").getAttribute("callsign"));
        assertEquals("*:-1:stcp", first(event, "contact").getAttribute("endpoint"));
        assertEquals("38.123456", first(event, "point").getAttribute("lat"));
        assertEquals("-77.123456", first(event, "point").getAttribute("lon"));
        assertEquals("123.4", first(event, "point").getAttribute("hae"));
        assertEquals("10", first(event, "point").getAttribute("ce"));
        assertEquals("20", first(event, "point").getAttribute("le"));
        assertEquals("Cyan", first(event, "__group").getAttribute("name"));
        assertEquals("Medic", first(event, "__group").getAttribute("role"));
        assertEquals("ATAK", first(event, "takv").getAttribute("platform"));
        assertEquals("5.6.0.runtime-CIV", first(event, "takv").getAttribute("version"));
        assertEquals("PHONE", first(event, "takv").getAttribute("device"));
        assertEquals("36", first(event, "takv").getAttribute("os"));
        assertFalse(build(watch, phone).contains("WATCH"));
    }

    @Test public void oldAndNewPeersProduceSamePhoneMetadataAndCurrentPhoneChangesApply() throws Exception {
        JSONObject oldPeer = new JSONObject();
        JSONObject newPeer = new JSONObject().put("team", "Red").put("role", "Sniper");
        assertEquals(build(oldPeer, phone), build(newPeer, phone));
        LegacyPliCot.Metadata changed = new LegacyPliCot.Metadata(
                "Green", "Team Lead", "ATAK", "runtime", "PHONE", "36");
        Document event = parse(build(oldPeer, changed));
        assertEquals("Green", first(event, "__group").getAttribute("name"));
        assertEquals("Team Lead", first(event, "__group").getAttribute("role"));
        assertEquals("PHONE-UID", event.getDocumentElement().getAttribute("uid"));
    }

    @Test public void escapesAllMetadataAndRemarksWithoutXmlInjection() throws Exception {
        String special = "A&<'\" >";
        LegacyPliCot.Metadata metadata = new LegacyPliCot.Metadata(
                special, special, special, special, special, special);
        String xml = LegacyPliCot.build(new JSONObject(),
                special, special, 1, 2, 3, 4, 5, metadata, NOW, special, WatchSelfStatus.Contribution.NONE);
        Document event = parse(xml);
        assertEquals(special, event.getDocumentElement().getAttribute("uid"));
        assertEquals(special, first(event, "contact").getAttribute("callsign"));
        assertEquals(special, first(event, "remarks").getTextContent());
        assertEquals(special, first(event, "__group").getAttribute("role"));
        assertEquals(special, first(event, "__group").getAttribute("name"));
        for (String key : new String[]{"platform", "version", "device", "os"}) {
            assertEquals(special, first(event, "takv").getAttribute(key));
        }
        assertEquals(1, event.getElementsByTagName("takv").getLength());
    }

    @Test public void preservesLegacyTimingMotionAndSensorAliases() throws Exception {
        JSONObject payload = new JSONObject().put("tStart", "2026-10-22T20:00:00Z")
                .put("tStale", "2026-10-22T20:10:00Z")
                .put("course", 42.5).put("speed", 3).put("bat", " 87.0 ")
                .put("heartRateBpm", "95.0");
        Document event = parse(build(payload, phone));
        assertEquals("2026-10-22T20:00:00Z", event.getDocumentElement().getAttribute("time"));
        assertEquals("2026-10-22T20:00:00Z", event.getDocumentElement().getAttribute("start"));
        assertEquals("2026-10-22T20:10:00Z", event.getDocumentElement().getAttribute("stale"));
        assertEquals("42.5", first(event, "track").getAttribute("course"));
        assertEquals("3", first(event, "track").getAttribute("speed"));
        assertEquals("87", first(event, "status").getAttribute("battery"));
        assertEquals("untimed legacy heart rate is never published", 0,
                event.getElementsByTagName("physio").getLength());
        assertEquals(0, event.getElementsByTagName("biometrics").getLength());
        payload.put("battery_percent", 200).put("hr", -1);
        event = parse(build(payload, phone));
        assertEquals("100", first(event, "status").getAttribute("battery"));
        assertEquals(0, event.getElementsByTagName("physio").getLength());
    }

    @Test public void noGeneratedBoilerplateAndPhoneRemarksAndFreshWatchBiometricsAreUsed() throws Exception {
        JSONObject watchPayload = new JSONObject().put("remarks", "WATCH TEXT");
        Document plain = parse(build(watchPayload, phone));
        assertEquals(0, plain.getElementsByTagName("remarks").getLength());
        assertFalse(build(watchPayload, phone).contains("WearTAK PLI"));

        WatchSelfStatus state = new WatchSelfStatus();
        state.accept("link", WatchSelfStatusTest.snapshot("s1", 0, "MOPP 2", 72, NOW), NOW);
        Document event = parse(LegacyPliCot.build(watchPayload, "PHONE-UID", "PHONE-CS",
                38.1, -77.1, 1, 10, 20, phone, NOW, " user\nPPE: MOPP 2; HR: 72 bpm @ 20:00:00Z ",
                state.current(NOW)));
        assertEquals("PHONE-UID", event.getDocumentElement().getAttribute("uid"));
        assertEquals("user\nPPE: MOPP 2; HR: 72 bpm @ 20:00:00Z", first(event, "remarks").getTextContent());
        Element biometrics = first(event, "biometrics");
        assertEquals("1", biometrics.getAttribute("version"));
        assertEquals("WATCH-UID", first(event, "uid").getTextContent());
        assertEquals("72", first(event, "hr").getTextContent());
        assertEquals("WEAROS", first(event, "model").getTextContent());
        assertEquals(1, event.getElementsByTagName("remarks").getLength());
    }

    @Test public void absentRuntimeValuesAreOmittedAndHostPrivacyCanOmitTakv() throws Exception {
        LegacyPliCot.Metadata missing = new LegacyPliCot.Metadata(
                "Blue", "Team Member", null, "", null, "");
        Document event = parse(build(new JSONObject(), missing));
        assertEquals(0, event.getElementsByTagName("takv").getLength());
        LegacyPliCot.Metadata partial = new LegacyPliCot.Metadata(
                "Blue", "Team Member", "ATAK", null, "PHONE", null);
        Element takv = first(parse(build(new JSONObject(), partial)), "takv");
        assertFalse(takv.hasAttribute("version"));
        assertFalse(takv.hasAttribute("os"));
    }

    @Test public void pointFormattingIsCotCompatibleInCommaDecimalLocales() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            Document event = parse(build(new JSONObject(), phone));
            assertEquals("38.123456", first(event, "point").getAttribute("lat"));
            assertEquals("123.4", first(event, "point").getAttribute("hae"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    private String build(JSONObject payload, LegacyPliCot.Metadata metadata) {
        return LegacyPliCot.build(payload, "PHONE-UID", "PHONE-CS",
                38.123456, -77.123456, 123.4, 10, 20, metadata, NOW);
    }

    @Test public void validNearOriginFixIsNotRoundedIntoZeroZero() throws Exception {
        Document event = parse(LegacyPliCot.build(new JSONObject(), "phone", "PHONE",
                0.00000001, 0, 0, 42, 9999999, phone, NOW));
        assertEquals(0.00000001, Double.parseDouble(first(event, "point").getAttribute("lat")), 0);
        assertEquals(42, Double.parseDouble(first(event, "point").getAttribute("ce")), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void noFixPlaceholderCanNeverBePublishedAsPli() {
        LegacyPliCot.build(new JSONObject(), "phone", "PHONE", 0, 0, 0, 10, 10, phone, NOW);
    }

    private static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
    }

    private static Element first(Document event, String tag) {
        return (Element) event.getElementsByTagName(tag).item(0);
    }
}
