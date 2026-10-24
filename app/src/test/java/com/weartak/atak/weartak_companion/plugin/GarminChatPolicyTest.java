package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class GarminChatPolicyTest {
    @Test public void routeNavigationCapabilityRequiresExactVersionRouteSupportAndExclusiveLink() throws Exception {
        JSONObject hello = new JSONObject().put("relaySession", "watch")
                .put("routeVersion", 1).put("routeNavigationVersion", 1);
        assertEquals(1, GarminChatPolicy.relayStatus(hello).get("routeNavigationVersion"));
        assertFalse(GarminChatPolicy.relayStatus(hello, false).containsKey("routeNavigationVersion"));
        for (Object version : new Object[]{"1", 2, 1.5, true}) {
            assertFalse(GarminChatPolicy.relayStatus(new JSONObject(hello.toString())
                    .put("routeNavigationVersion", version)).containsKey("routeNavigationVersion"));
        }
        assertFalse(GarminChatPolicy.relayStatus(new JSONObject(hello.toString())
                .put("routeVersion", 0)).containsKey("routeNavigationVersion"));
    }

    @Test public void capabilitiesAreSessionBoundAndEnableRetryAcknowledgementsWithoutGroups() throws Exception {
        Map<String, Object> status = GarminChatPolicy.relayStatus(new JSONObject()
                .put("relaySession", "current-watch-session").put("watchUid", "device-uid")
                .put("bloodhoundVersion", 1));
        assertEquals("current-watch-session", status.get("relaySession"));
        assertEquals(false, status.get("chatGroups"));
        assertEquals(true, status.get("reliableDelivery"));
        assertEquals(true, status.get("phoneLocation"));
        assertEquals(1, status.get("selfStatus"));
        assertEquals(1, status.get("markerDelete"));
        assertEquals(8, status.size());
        assertEquals(1, status.get("bloodhoundVersion"));
        assertEquals(true, status.get("bloodhoundControl"));
    }

    @Test public void markerDeleteCapabilityRequiresAValidWatchIdentity() throws Exception {
        assertFalse(GarminChatPolicy.relayStatus(new JSONObject()
                .put("relaySession", "watch")).containsKey("markerDelete"));
        assertFalse(GarminChatPolicy.relayStatus(new JSONObject()
                .put("relaySession", "watch").put("watchUid", "bad\nuid")).containsKey("markerDelete"));
        assertEquals(1, GarminChatPolicy.relayStatus(new JSONObject()
                .put("relaySession", "watch").put("watchUid", "device-uid")).get("markerDelete"));
    }

    @Test public void bloodhoundCapabilityRequiresVersionOneRequest() throws Exception {
        for (Object version : new Object[]{"1", 2, 1.5d, true}) {
            Map<String, Object> status = GarminChatPolicy.relayStatus(
                    new JSONObject().put("relaySession", "watch").put("bloodhoundVersion", version));
            assertFalse(status.containsKey("bloodhoundVersion"));
            assertFalse(status.containsKey("bloodhoundControl"));
            assertEquals(false, status.get("chatGroups"));
            assertEquals(true, status.get("reliableDelivery"));
        }
        Map<String, Object> ambiguous = GarminChatPolicy.relayStatus(
                new JSONObject().put("relaySession", "watch").put("bloodhoundVersion", 1), false);
        assertFalse(ambiguous.containsKey("bloodhoundVersion"));
        assertFalse(ambiguous.containsKey("bloodhoundControl"));
    }

    @Test public void capabilitiesWithoutARealSessionAreRejected() throws Exception {
        for (JSONObject hello : new JSONObject[]{new JSONObject(),
                new JSONObject().put("relaySession", 1),
                new JSONObject().put("relaySession", ""),
                new JSONObject().put("relaySession", "bad\nsession"),
                new JSONObject().put("relaySession", new String(new char[129]).replace('\0', 'x'))}) {
            try {
                GarminChatPolicy.relayStatus(hello);
                fail("Invalid relay session was accepted");
            } catch (IllegalArgumentException expected) {
                assertEquals("Missing Garmin relay session", expected.getMessage());
            }
        }
    }

    @Test public void directReplyRetainsCanonicalDestinationAndText() throws Exception {
        ChatReplyPayload reply = GarminChatPolicy.reply(new JSONObject()
                .put("roomUid", "person").put("recipientUid", "person").put("msg", "hello"), false);
        assertEquals("person", reply.roomUid);
        assertEquals("hello", reply.text);
        assertFalse(reply.isGroup);
    }

    @Test public void legacyAliasesAreNotReplacedWithBroadcast() throws Exception {
        ChatReplyPayload reply = GarminChatPolicy.reply(new JSONObject()
                .put("rmUid", "person").put("text", "hello"), false);
        assertEquals("person", reply.roomUid);
    }

    @Test public void explicitAllChatRoomsRemainsSupported() throws Exception {
        assertEquals("All Chat Rooms", GarminChatPolicy.reply(new JSONObject()
                .put("roomUid", "All Chat Rooms").put("msg", "hello").put("isGroup", true), true).roomUid);
    }

    @Test public void groupUidIsRejectedWithoutSenderOrBroadcastFallback() throws Exception {
        reject(new JSONObject().put("roomUid", "named-group").put("msg", "hello").put("isGroup", true), false);
        reject(new JSONObject().put("rmUid", "named-group").put("msg", "hello"), true);
    }

    @Test public void messageIdIsNeverUsedAsDestination() throws Exception {
        reject(new JSONObject().put("uid", "event-id").put("msg", "hello"), false);
    }

    @Test public void conflictingAliasesAndDirectGroupFallbackAreRejected() throws Exception {
        reject(new JSONObject().put("roomUid", "a").put("rmUid", "b").put("msg", "hello"), false);
        reject(new JSONObject().put("roomUid", "a").put("recipientUid", "b").put("msg", "hello"), false);
        reject(new JSONObject().put("roomUid", "a").put("msg", "hello").put("text", "different"), false);
        reject(new JSONObject().put("roomUid", "a").put("replyTo", "a").put("isGroup", true).put("msg", "hello"), false);
    }

    @Test public void receivedDirectChatUsesSharedWireContract() throws Exception {
        JSONObject envelope = ChatRelayPayloadBuilder.build("phone", "sender", "message-id",
                "SENDER", "hello", 1791750000123L, "phone", "Phone");
        Map<String, Object> payload = GarminChatPolicy.received(envelope);
        assertEquals("message-id", payload.get("uid"));
        assertEquals("sender", payload.get("senderUid"));
        assertEquals("SENDER", payload.get("senderCallsign"));
        assertEquals("hello", payload.get("msg"));
        assertEquals(1791750000123L, payload.get("receivedAt"));
        assertEquals("sender", payload.get("conversationUid"));
        assertEquals("SENDER", payload.get("conversationTitle"));
        assertEquals(false, payload.get("isGroup"));
    }

    @Test public void receivedBroadcastPreservesParticipantsAndRoom() throws Exception {
        JSONObject envelope = ChatRelayPayloadBuilder.build("phone", "sender", "message-id",
                "SENDER", "hello", 1L, "All Chat Rooms", "All Chat Rooms",
                true, new String[]{"phone", "sender"});
        Map<String, Object> payload = GarminChatPolicy.received(envelope);
        assertEquals("All Chat Rooms", payload.get("conversationUid"));
        assertEquals(true, payload.get("isGroup"));
        assertEquals(java.util.Arrays.asList("sender", "phone"), payload.get("participantUids"));
    }

    @Test public void receivedNamedGroupDoesNotEnterGarminOutbox() throws Exception {
        JSONObject envelope = ChatRelayPayloadBuilder.build("phone", "sender", "message-id",
                "SENDER", "hello", 1L, "named-group", "Team",
                true, new String[]{"phone", "sender"});
        try {
            GarminChatPolicy.received(envelope);
            fail("Named group must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("Named Garmin group chat is not supported", expected.getMessage());
            assertEquals("named-group", envelope.getJSONObject("payload").getString("conversationUid"));
        }
    }

    private void reject(JSONObject payload, boolean groupContact) {
        try {
            GarminChatPolicy.reply(payload, groupContact);
            fail("Unsafe Garmin reply was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected before native ATAK routing or SDK handoff.
        }
    }
}
