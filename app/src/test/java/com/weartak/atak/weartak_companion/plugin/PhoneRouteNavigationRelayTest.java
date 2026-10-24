package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class PhoneRouteNavigationRelayTest {
    private static final String COT = "<event uid='r' type='b-m-r'><detail>"
            + "<link point='38,-77'/><link point='38.1,-77.1'/>"
            + "<link point='38.2,-77.2'/></detail></event>";
    private final PhoneRouteNavigationRelay relay = new PhoneRouteNavigationRelay();
    private final List<JSONObject> frames = new ArrayList<>();
    private final PhoneRouteNavigationRelay.Writer writer = (json, connection) -> {
        assertEquals(7, connection);
        try {
            JSONObject envelope = new JSONObject(json);
            assertEquals("phone_route_navigation", envelope.getString("msg_type"));
            frames.add(envelope.getJSONObject("payload"));
            return true;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    };

    private boolean update(int target, String cot) throws Exception {
        return relay.update("r", target, 3, cot, cot, 7, writer);
    }

    @Test public void startAndExactForwardBackwardObjectivesIncludeAtomicGeometry() throws Exception {
        relay.start("r");
        assertTrue(update(1, COT));
        assertTrue(update(2, COT));
        assertTrue(update(0, COT));
        assertEquals("start", frames.get(0).getString("action"));
        String id = frames.get(0).getString("sessionId");
        for (int i = 0; i < frames.size(); i++) {
            JSONObject frame = frames.get(i);
            assertEquals(1, frame.getInt("version"));
            assertEquals("r", frame.getString("routeUid"));
            assertEquals(id, frame.getString("sessionId"));
            assertEquals(i + 1, frame.getLong("sequence"));
            assertEquals(COT, frame.getString("routeCot"));
        }
        assertEquals(2, frames.get(1).getInt("targetPointIndex"));
        assertEquals(0, frames.get(2).getInt("targetPointIndex"));
        assertEquals("progress", frames.get(2).getString("action"));
    }

    @Test public void duplicateSuppressionDoesNotSuppressGeometryOrNameEdits() throws Exception {
        relay.start("r");
        assertTrue(update(1, COT));
        assertFalse(update(1, COT));
        String edit = COT.replace("38.1", "38.15").replace("<detail>", "<detail><contact callsign='Edited'/>");
        assertTrue(update(1, edit));
        assertEquals(edit, frames.get(1).getString("routeCot"));
        assertEquals(2, frames.size());
    }

    @Test public void reconnectReplaysStartWithSameNavigationIdentityAndNewSequence() throws Exception {
        relay.start("r");
        update(1, COT);
        String id = frames.get(0).getString("sessionId");
        assertFalse(relay.update("r", 2, 3, COT, COT, -1, writer));
        assertTrue(relay.update("r", 2, 3, COT, COT, 8, (json, connection) -> {
            assertEquals(8, connection);
            try {
                JSONObject frame = new JSONObject(json).getJSONObject("payload");
                assertEquals("start", frame.getString("action"));
                assertEquals(id, frame.getString("sessionId"));
                assertEquals(2, frame.getLong("sequence"));
                assertEquals(2, frame.getInt("targetPointIndex"));
                assertEquals(COT, frame.getString("routeCot"));
            } catch (Exception e) { throw new AssertionError(e); }
            return true;
        }));
    }

    @Test public void rejectedStartAndProgressRetryWithoutAdvancingSequence() throws Exception {
        relay.start("r");
        assertFalse(relay.update("r", 1, 3, COT, COT, 7, (json, connection) -> false));
        update(1, COT);
        assertEquals(1, frames.get(0).getLong("sequence"));
        assertEquals("start", frames.get(0).getString("action"));
        assertFalse(relay.update("r", 2, 3, COT, COT, 7, (json, connection) -> false));
        update(2, COT);
        assertEquals(2, frames.get(1).getLong("sequence"));
    }

    @Test public void stopReportsNativeCompletionAndCannotRestartEndedNavigation() throws Exception {
        for (boolean completed : new boolean[]{false, true}) {
            relay.start("r");
            update(1, COT);
            relay.stop(completed);
            assertFalse(update(2, COT));
            assertTrue(relay.flushStop(7, writer));
            JSONObject stop = frames.get(frames.size() - 1);
            assertEquals("stop", stop.getString("action"));
            assertEquals(completed, stop.getBoolean("completed"));
            assertEquals(2, stop.getLong("sequence"));
            assertFalse(relay.flushStop(7, writer));
            assertFalse(update(2, COT));
        }
    }

    @Test public void rejectedStopRetriesButNeverStopsAReplacementConnection() throws Exception {
        relay.start("r");
        update(1, COT);
        relay.stop(false);
        assertFalse(relay.flushStop(7, (json, connection) -> false));
        assertTrue(relay.flushStop(7, writer));
        relay.start("r");
        update(1, COT);
        relay.stop(false);
        assertFalse(relay.flushStop(-1, writer));
        assertFalse(relay.flushStop(8, (json, connection) -> {
            fail("Old stop must not affect a new connection");
            return true;
        }));
    }

    @Test public void routeReplacementAndRestartUseFreshNavigationIdentity() throws Exception {
        relay.start("r");
        update(1, COT);
        String previous = frames.get(0).getString("sessionId");
        relay.stop(false);
        relay.flushStop(7, writer);
        relay.start("r");
        update(1, COT);
        assertNotEquals(previous, frames.get(2).getString("sessionId"));
        assertEquals(1, frames.get(2).getLong("sequence"));
        assertFalse(relay.update("different", 1, 3, COT, COT, 7, writer));
        relay.clear();
        assertFalse(update(1, COT));
    }

    @Test public void invalidIndicesAndCountsCannotBeSent() throws Exception {
        relay.start("r");
        for (int target : new int[]{-1, 3, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> update(target, COT));
        }
        assertThrows(IllegalArgumentException.class,
                () -> relay.update("r", 0, 1, COT, COT, 7, writer));
        assertThrows(IllegalArgumentException.class,
                () -> relay.update("r", 0, 10001, COT, COT, 7, writer));
        assertTrue(frames.isEmpty());
    }

    @Test public void frameBoundIsMeasuredAfterJsonEscapingInUtf8() throws Exception {
        relay.start("r");
        char[] chars = new char[PhoneRouteNavigationRelay.MAX_FRAME_BYTES / 2];
        Arrays.fill(chars, '\u20ac');
        String oversized = new String(chars);
        assertTrue(oversized.length() < PhoneRouteNavigationRelay.MAX_FRAME_BYTES);
        assertTrue(oversized.getBytes(StandardCharsets.UTF_8).length
                > PhoneRouteNavigationRelay.MAX_FRAME_BYTES);
        assertThrows(IllegalArgumentException.class, () -> update(1, oversized));
        assertTrue(frames.isEmpty());
        assertTrue(update(1, COT));
        assertEquals(1, frames.get(0).getLong("sequence"));
    }

    @Test public void exactFrameLimitIsAcceptedAndOneMoreByteRejected() throws Exception {
        relay.start("r");
        int[] baseSize = {0};
        assertFalse(relay.update("r", 1, 3, COT, COT, 7, (json, connection) -> {
            baseSize[0] = json.getBytes(StandardCharsets.UTF_8).length;
            return false;
        }));
        char[] padding = new char[PhoneRouteNavigationRelay.MAX_FRAME_BYTES - baseSize[0]];
        Arrays.fill(padding, ' ');
        String bounded = COT + new String(padding);
        assertFalse(relay.update("r", 1, 3, bounded, bounded, 7, (json, connection) -> {
            assertEquals(PhoneRouteNavigationRelay.MAX_FRAME_BYTES,
                    json.getBytes(StandardCharsets.UTF_8).length);
            return false;
        }));
        assertThrows(IllegalArgumentException.class, () -> update(1, bounded + " "));
        assertTrue(update(1, bounded));
        assertEquals(1, frames.get(0).getLong("sequence"));
        char[] escaped = new char[PhoneRouteNavigationRelay.MAX_FRAME_BYTES / 2];
        Arrays.fill(escaped, '\\');
        assertThrows(IllegalArgumentException.class, () -> update(1, new String(escaped)));
    }

    @Test public void everyRawVertexMustBeValidAndCountPreserved() {
        PhoneRouteNavigationRelay.validateVertices(Arrays.asList("0,0", "90,-180,10"), 2);
        for (String invalid : new String[]{"NaN,1", "1,Infinity", "91,0", "0,-181", "38", "", "x,0"}) {
            assertThrows(IllegalArgumentException.class, () ->
                    PhoneRouteNavigationRelay.validateVertices(Arrays.asList("38,-77", invalid), 2));
        }
        assertThrows(IllegalArgumentException.class, () ->
                PhoneRouteNavigationRelay.validateVertices(Collections.singletonList("38,-77"), 2));
        assertThrows(IllegalArgumentException.class, () ->
                PhoneRouteNavigationRelay.validateVertices(Collections.nCopies(10001, "38,-77"), 10001));
        PhoneRouteNavigationRelay.validateVertices(Collections.nCopies(10000, "38,-77"), 10000);
    }
}
