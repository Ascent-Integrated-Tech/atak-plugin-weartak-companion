package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class GarminEntityAdapterTest {
    @Test public void rejectsSelfEchoUnsupportedAndInvalidPoints() {
        Map<String, Object> meta = Collections.emptyMap();
        assertNull(GarminEntityAdapter.normalize("self", 38, -77, "a-f-G", meta, true, false));
        assertNull(GarminEntityAdapter.normalize("echo", 38, -77, "a-f-G", meta, false, true));
        assertNull(GarminEntityAdapter.normalize("chat", 38, -77, "b-t-f", meta, false, false));
        for (double[] coords : new double[][]{{0, 0}, {Double.NaN, 10},
                {Double.POSITIVE_INFINITY, 10}, {91, 10}, {10, 181}}) {
            assertNull(GarminEntityAdapter.normalize("point", coords[0], coords[1],
                    "a-f-G", meta, false, false));
        }
        assertNotNull(GarminEntityAdapter.normalize("equator", 0, 10, "a-f-G", meta, false, false));
        assertEquals(true, GarminEntityAdapter.normalize("point", 38, -77,
                "b-m-p-s-p-i", meta, false, false).get("isPoint"));
    }

    @Test public void stalePointsFailClosedButAlertExpiryIsPreserved() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("stale", "2000-01-01T00:00:00Z");
        assertNull(GarminEntityAdapter.normalize("point", 38, -77, "a-f-G", meta, false, false));
        Map<String, Object> alert = GarminEntityAdapter.normalize("alert", 38, -77,
                "b-a-o-tbl", meta, false, false);
        assertEquals(meta.get("stale"), alert.get("stale"));
        assertEquals(true, alert.get("active"));
        meta.put("stale", "not-a-date");
        assertNull(GarminEntityAdapter.normalize("point", 38, -77, "a-f-G", meta, false, false));
    }

    @Test public void cancellationDoesNotRequireOrInventCoordinates() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("time", "2026-10-12T12:00:00Z");
        meta.put("senderUID", "sender");
        Map<String, Object> cancel = GarminEntityAdapter.normalize("alert", 0, 0,
                "b-a-o-tbl-can", meta, false, false);
        assertEquals("CANCEL", cancel.get("state"));
        assertEquals(false, cancel.get("active"));
        assertFalse(cancel.containsKey("lat"));
        assertEquals(true, ((Map<?, ?>) cancel.get("emergency")).get("cancel"));
        assertEquals("sender", cancel.get("senderUID"));
        assertEquals(meta.get("time"), cancel.get("time"));
        meta.put("cancel", true);
        assertEquals("CANCEL", GarminEntityAdapter.normalize("alert", Double.NaN, Double.NaN,
                "b-a-o-tbl", meta, false, false).get("state"));
    }

    @Test public void locationlessActiveAlertsHaveNoFallbackFix() {
        Map<String, Object> alert = GarminEntityAdapter.normalize("alert", Double.NaN, 999,
                "b-a-o-tbl", Collections.emptyMap(), false, false);
        assertEquals(true, alert.get("active"));
        assertFalse(alert.containsKey("lat"));
        assertFalse(alert.containsKey("lon"));
        assertEquals(Collections.singletonList(alert), GarminEntityAdapter.nearest(
                Collections.singletonList(alert), 38, -77, 50));
    }

    @Test public void snapshotsAreNearestFirstDeterministicAndBounded() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 59; i >= 0; i--) candidates.add(GarminEntityAdapter.normalize("unit-" + i,
                38 + i * .001, -77, "a-f-G", Collections.emptyMap(), false, false));
        List<Map<String, Object>> nearest = GarminEntityAdapter.nearest(candidates, 38, -77, 1000);
        assertEquals(50, nearest.size());
        assertEquals("unit-0", nearest.get(0).get("uid"));
        assertEquals("unit-49", nearest.get(49).get("uid"));
        assertEquals(1, GarminEntityAdapter.nearest(candidates, 38, -77, 0).size());
        assertTrue(GarminEntityAdapter.nearest(candidates, 0, 0, 50).isEmpty());
    }

    @Test public void genericAffiliationsSpotsAndShapeGeometryPreserveWireFields() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("stale", "2099-01-01T00:00:00Z");
        Map<String, Object> link = new HashMap<>();
        link.put("uid", "linked-target");
        link.put("relation", "p-p");
        metadata.put("link", link);
        Map<String, Object> ellipse = new HashMap<>();
        ellipse.put("major", 250.0);
        ellipse.put("minor", 250.0);
        metadata.put("shape", Collections.singletonMap("ellipse", ellipse));
        metadata.put("radius", 250.0);
        for (String type : new String[]{"a-h-A-M-F", "a-n-G", "a-u-G", "a-s-G",
                "b-m-p-s-m", "u-d-c-c", "u-d-f"}) {
            Map<String, Object> entity = GarminEntityAdapter.normalize("generic", 38, -77,
                    type, metadata, false, false);
            assertNotNull(entity);
            assertEquals(link, entity.get("link"));
            assertEquals(metadata.get("stale"), entity.get("stale"));
            assertEquals(metadata.get("shape"), entity.get("shape"));
            assertEquals(250.0, entity.get("radius"));
            assertFalse(entity.containsKey("isUser"));
        }
    }

    @Test public void videoAliasMarkersRelayAsPointsWithPresenceOnlyVideo() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("stale", "2099-01-01T00:00:00Z");
        meta.put("callSign", "Gate Cam");
        meta.put("__video", true);
        assertTrue(GarminEntityAdapter.supportedType("b-i-v"));
        Map<String, Object> camera = GarminEntityAdapter.normalize("cam-1", 38, -77,
                "b-i-v", meta, false, false);
        assertNotNull(camera);
        assertEquals("b-i-v", camera.get("type"));
        assertEquals(true, camera.get("isPoint"));
        assertEquals(Collections.emptyMap(), camera.get("__video"));
        assertEquals("Gate Cam", camera.get("callSign"));
        assertFalse(camera.containsKey("isUser"));

        meta.remove("__video");
        Map<String, Object> bare = GarminEntityAdapter.normalize("cam-2", 38, -77,
                "b-i-v", meta, false, false);
        assertEquals(true, bare.get("isPoint"));
        assertFalse(bare.containsKey("__video"));
        assertFalse(GarminEntityAdapter.supportedType("b-i-v-x"));
        assertFalse(GarminEntityAdapter.supportedType("b-i"));
    }

    @Test public void videoBearingTracksCarrySanitizedVideoWithoutStreamAttributes() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("stale", "2099-01-01T00:00:00Z");
        meta.put("__video", true);
        Map<String, Object> uav = GarminEntityAdapter.normalize("uav", 38, -77,
                "a-f-A-M-F-Q", meta, false, false);
        assertEquals(Collections.emptyMap(), uav.get("__video"));
        assertFalse(uav.containsKey("isPoint"));

        Map<String, Object> leaked = new HashMap<>();
        leaked.put("url", "rtsp://user:secret@10.0.0.1/stream");
        meta.put("__video", leaked);
        Map<String, Object> unsafe = GarminEntityAdapter.normalize("uav", 38, -77,
                "a-f-A-M-F-Q", meta, false, false);
        assertFalse(unsafe.containsKey("__video"));
        assertFalse(unsafe.toString().contains("rtsp"));

        meta.remove("__video");
        meta.put("callSign", "camera sensor video");
        Map<String, Object> labelled = GarminEntityAdapter.normalize("spi", 38, -77,
                "b-m-p-s-p-i", meta, false, false);
        assertFalse(labelled.containsKey("__video"));
    }

    @Test public void coordinateFreeDeleteKeepsLinkedTargetAndIsNotFilteredAsStale() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("stale", "2000-01-01T00:00:00Z");
        Map<String, Object> link = new HashMap<>();
        link.put("uid", "target");
        link.put("relation", "p-p");
        meta.put("link", link);
        Map<String, Object> deletion = GarminEntityAdapter.normalize("delete-event",
                Double.NaN, Double.NaN, "t-x-d-d", meta, false, false);
        assertEquals(true, deletion.get("isDelete"));
        assertFalse(deletion.containsKey("lat"));
        assertFalse(deletion.containsKey("lon"));
        assertEquals(link, deletion.get("link"));
    }
}
