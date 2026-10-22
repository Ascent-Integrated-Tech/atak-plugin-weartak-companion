package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import static org.junit.Assert.*;

public class GatewayReplayTest {
    private static final long NOW = 1792674120000L;

    private String chat(long eventTime, String room, String[] recipients) {
        return GatewayCot.buildChat("sender", "Sender", "message-" + eventTime, "Message",
                eventTime, room, room, true, recipients).xml;
    }

    @Test public void replayUsesTheSameDirectAndBroadcastRecipientRulesAsLiveFanout() {
        String direct = chat(NOW, "room", new String[]{"watch-a"});
        GatewayCot.ChatRoute directRoute = GatewayCot.readChat(direct);
        assertTrue(GatewayCot.chatAllowsRecipient(directRoute, "watch-a"));
        assertFalse(GatewayCot.chatAllowsRecipient(directRoute, "watch-b"));
        assertTrue(GatewayReplay.allows(direct, "watch-a", NOW));
        assertFalse(GatewayReplay.allows(direct, "watch-b", NOW));

        String broadcast = chat(NOW, "All Chat Rooms", null);
        GatewayCot.ChatRoute broadcastRoute = GatewayCot.readChat(broadcast);
        assertTrue(GatewayCot.chatAllowsRecipient(broadcastRoute, "watch-a"));
        assertTrue(GatewayCot.chatAllowsRecipient(broadcastRoute, "watch-b"));
        assertTrue(GatewayReplay.allows(broadcast, "watch-a", NOW));
        assertTrue(GatewayReplay.allows(broadcast, "watch-b", NOW));
    }

    @Test public void replaySkipsExpiredChatAndMapEventsButKeepsFreshMapEvents() throws Exception {
        String expiredChat = chat(NOW - 900000L, "room", new String[]{"watch-a"});
        assertFalse(GatewayReplay.allows(expiredChat, "watch-a", NOW));

        String expiredMap = GatewayCot.build("{\"msgType\":\"pli\",\"payload\":{\"uid\":\"entity\","
                + "\"cs\":\"ENTITY\",\"lat\":1,\"lon\":2,\"tStart\":\"2026-10-22T13:00:00Z\","
                + "\"tStale\":\"2026-10-22T13:01:00Z\"}}", "watch-a", "WATCH");
        assertFalse(GatewayReplay.allows(expiredMap, "watch-b", NOW));

        String freshMap = GatewayCot.build("{\"msgType\":\"pli\",\"payload\":{\"uid\":\"entity\","
                + "\"cs\":\"ENTITY\",\"lat\":1,\"lon\":2,\"tStart\":\"2026-10-22T13:00:00Z\","
                + "\"tStale\":\"2026-10-22T13:01:00Z\"}}", "watch-a", "WATCH");
        assertTrue(GatewayReplay.allows(freshMap, "watch-b", NOW - 3600000L));
        assertFalse(GatewayReplay.allows(freshMap, "entity", NOW - 3600000L));
    }
}
