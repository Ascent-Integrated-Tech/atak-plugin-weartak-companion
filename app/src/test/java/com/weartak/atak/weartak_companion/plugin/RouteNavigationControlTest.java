package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

public class RouteNavigationControlTest {
    private final FakePort port = new FakePort();
    private final RouteNavigationControl control = new RouteNavigationControl(port);

    private static final class FakePort implements RouteNavigationControl.Port {
        String id, uid;
        int starts, stops;
        boolean fail, confirm = true;
        @Override public String sessionId() { return id; }
        @Override public String routeUid() { return uid; }
        @Override public void start(JSONObject command) throws Exception {
            if (fail) throw new IllegalArgumentException("Geometry mismatch");
            starts++;
            if (confirm) {
                id = command.getString("sessionId");
                uid = command.getString("routeUid");
            }
        }
        @Override public void stop() { stops++; id = null; uid = null; }
    }

    private JSONObject command(String action, String id) throws Exception {
        return new JSONObject().put("version", 1).put("requestId", UUID.randomUUID().toString())
                .put("sessionId", id).put("routeUid", "route").put("action", action)
                .put("targetPointIndex", 1);
    }

    @Test public void watchProposedIdentityIsPreservedAndNativeStartMustConfirm() throws Exception {
        String id = UUID.randomUUID().toString();
        JSONObject start = command("start", id);
        JSONObject ack = control.receive(start, 7);
        assertTrue(ack.getBoolean("ok"));
        assertEquals(id, ack.getString("sessionId"));
        assertEquals(start.getString("requestId"), ack.getString("requestId"));
        assertEquals(id, port.id);
        assertEquals(1, port.starts);
        port.confirm = false;
        JSONObject rejected = control.receive(command("start", UUID.randomUUID().toString()), 7);
        assertFalse(rejected.getBoolean("ok"));
        assertTrue(rejected.getString("error").contains("ATAK"));
    }

    @Test public void repeatedRequestAndSessionNeverRestartNativeNavigation() throws Exception {
        String id = UUID.randomUUID().toString();
        JSONObject start = command("start", id);
        assertTrue(control.receive(start, 7).getBoolean("ok"));
        assertTrue(control.receive(new JSONObject(start.toString()), 7).getBoolean("ok"));
        assertTrue(control.receive(command("start", id), 7).getBoolean("ok"));
        assertEquals(1, port.starts);
        assertFalse(control.receive(new JSONObject(start.toString()).put("routeUid", "changed"), 7)
                .getBoolean("ok"));
        assertEquals(1, port.starts);
    }

    @Test public void scopedStopCannotStopUnrelatedNativeNavigation() throws Exception {
        String id = UUID.randomUUID().toString();
        control.receive(command("start", id), 7);
        assertFalse(control.receive(command("stop", UUID.randomUUID().toString()), 7).getBoolean("ok"));
        assertFalse(control.receive(command("stop", id).put("routeUid", "other"), 7).getBoolean("ok"));
        assertEquals(0, port.stops);
        assertEquals(id, port.id);
        assertTrue(control.receive(command("stop", id), 7).getBoolean("ok"));
        assertEquals(1, port.stops);
    }

    @Test public void stopIsIdempotentAndDelayedStartCannotResurrectIt() throws Exception {
        String id = UUID.randomUUID().toString();
        control.receive(command("start", id), 7);
        assertTrue(control.receive(command("stop", id), 7).getBoolean("ok"));
        assertTrue(control.receive(command("stop", id), 7).getBoolean("ok"));
        assertFalse(control.receive(command("start", id), 7).getBoolean("ok"));
        assertEquals(1, port.stops);
        assertEquals(1, port.starts);
    }

    @Test public void stopBeforeStartFencesLateStartWithoutStoppingAnotherRoute() throws Exception {
        String id = UUID.randomUUID().toString();
        assertFalse(control.receive(command("stop", id), 7).getBoolean("ok"));
        assertFalse(control.receive(command("start", id), 7).getBoolean("ok"));
        assertEquals(0, port.starts);
        assertEquals(0, port.stops);
    }

    @Test public void nativeEndedSessionStaysRetiredAcrossReconnect() throws Exception {
        String id = UUID.randomUUID().toString();
        control.receive(command("start", id), 7);
        control.ended(id);
        port.id = null;
        port.uid = null;
        assertFalse(control.receive(command("start", id), 8).getBoolean("ok"));
        assertTrue(control.receive(command("start", UUID.randomUUID().toString()), 8).getBoolean("ok"));
    }

    @Test public void nativeErrorsAreExplicitAndFailedRequestsDoNotRetryNativeSideEffects() throws Exception {
        port.fail = true;
        JSONObject request = command("start", UUID.randomUUID().toString());
        JSONObject ack = control.receive(request, 7);
        assertFalse(ack.getBoolean("ok"));
        assertEquals("Geometry mismatch", ack.getString("error"));
        port.fail = false;
        assertFalse(control.receive(request, 7).getBoolean("ok"));
        assertEquals(0, port.starts);
    }

    @Test public void malformedTypesFractionsAndBoundsAreRejectedBeforeNativeCalls() throws Exception {
        for (Object index : new Object[]{-1, 10000, 1.5, "1", true, JSONObject.NULL}) {
            assertFalse(control.receive(command("start", UUID.randomUUID().toString())
                    .put("targetPointIndex", index), 7).getBoolean("ok"));
        }
        assertFalse(control.receive(command("start", "not-a-uuid"), 7).getBoolean("ok"));
        assertFalse(control.receive(command("start", UUID.randomUUID().toString()).put("version", "1"), 7)
                .getBoolean("ok"));
        assertFalse(control.receive(command("progress", UUID.randomUUID().toString()), 7).getBoolean("ok"));
        assertThrows(IllegalArgumentException.class, () ->
                control.receive(command("start", UUID.randomUUID().toString()), -1));
        assertEquals(0, port.starts);
    }
}
