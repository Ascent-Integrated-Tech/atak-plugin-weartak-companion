package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.*;

public class WatchChatEchoTrackerTest {
    @Test public void matchesRepeatedEchoesAcrossConversationKinds() {
        WatchChatEchoTracker tracker = new WatchChatEchoTracker(8, 100);
        for (String room : new String[] {"All Chat Rooms", "direct-contact", "named-group"}) {
            tracker.record("b-t-f", "event-" + room, "phone", room, "id", "hello", 0);
            for (int i = 0; i < 2; i++) {
                assertTrue(tracker.isEcho("b-t-f", "event-" + room, "phone", room, "id", "hello", 1));
            }
        }
    }

    @Test public void preservesNewMessagesRepliesAndReceipts() {
        WatchChatEchoTracker tracker = new WatchChatEchoTracker(8, 100);
        tracker.record("b-t-f", "event", "phone", "room", "id", "hello", 0);
        assertFalse(tracker.isEcho("b-t-f", "new-event", "phone", "room", "new-id", "hello", 1));
        assertFalse(tracker.isEcho("b-t-f", "event", "other-device", "room", "id", "hello", 1));
        assertFalse(tracker.isEcho("b-t-f", "event", "phone", "other-room", "id", "hello", 1));
        assertFalse(tracker.isEcho("b-t-f", "event", "phone", "room", "id", "reply", 1));
        assertFalse(tracker.isEcho("b-t-f-d", "event", "phone", "room", "id", "hello", 1));
        assertFalse(tracker.isEcho("b-t-f-r", "event", "phone", "room", "id", "hello", 1));
    }

    @Test public void expiresAtBoundaryWithoutRenewingOnSerializationOrEcho() {
        WatchChatEchoTracker tracker = new WatchChatEchoTracker(8, 100);
        tracker.record("b-t-f", "event", "phone", "room", "id", "hello", 0);
        tracker.record("b-t-f", "event", "phone", "room", "id", "hello", 90);
        assertTrue(tracker.isEcho("b-t-f", "event", "phone", "room", "id", "hello", 99));
        assertFalse(tracker.isEcho("b-t-f", "event", "phone", "room", "id", "hello", 100));
    }

    @Test public void boundsCapacityAndClearsOnStop() {
        WatchChatEchoTracker tracker = new WatchChatEchoTracker(1, 100);
        tracker.record("b-t-f", "old", "phone", "room", "id", "hello", 0);
        tracker.record("b-t-f", "new", "phone", "room", "id2", "hello", 1);
        assertFalse(tracker.isEcho("b-t-f", "old", "phone", "room", "id", "hello", 2));
        assertTrue(tracker.isEcho("b-t-f", "new", "phone", "room", "id2", "hello", 2));
        tracker.clear();
        assertFalse(tracker.isEcho("b-t-f", "new", "phone", "room", "id2", "hello", 2));
    }

    @Test public void malformedInputAndClockResetNeverSuppress() {
        WatchChatEchoTracker tracker = new WatchChatEchoTracker(1, 100);
        assertFalse(tracker.isEcho("b-t-f", null, "phone", "room", "id", "hello", 0));
        tracker.record("b-t-f", "event", "phone", "room", "id", "hello", 10);
        assertFalse(tracker.isEcho("b-t-f", "event", "phone", "room", "id", "hello", 9));
    }
}
