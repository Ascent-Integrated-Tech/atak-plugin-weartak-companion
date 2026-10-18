package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class GatewayFanoutTest {
    @Test public void boundedCoalescingKeepsLatestRevisionAndRetryPreservesFailedWrite() {
        GatewayFanout queue = new GatewayFanout();
        for (int i = 0; i < 128; i++) assertTrue(queue.offer("uid" + i, "xml" + i));
        assertFalse(queue.offer("overflow", "xml"));
        assertTrue(queue.offer("uid0", "latest"));
        assertFalse(queue.drain(xml -> false));
        assertEquals(128, queue.size());
        List<String> sent = new ArrayList<>();
        assertTrue(queue.drain(xml -> { sent.add(xml); return true; }));
        assertEquals("latest", sent.get(0));
        assertEquals(127, queue.size());
        assertTrue(queue.offer("uid0", "latest"));
        assertEquals(127, queue.size());
    }
    @Test public void peerQueuesAndNewSessionsAreIndependent() {
        GatewayFanout a = new GatewayFanout(), b = new GatewayFanout();
        a.offer("entity", "revision1");
        b.offer("entity", "revision1");
        a.clear();
        assertEquals(0, a.size());
        assertEquals(1, b.size());
        assertTrue(b.drain(xml -> true));
        assertTrue(b.offer("entity", "revision1"));
        assertEquals(0, b.size());
        b.clear();
        b.offer("entity", "revision1");
        assertEquals(1, b.size());
    }
}
