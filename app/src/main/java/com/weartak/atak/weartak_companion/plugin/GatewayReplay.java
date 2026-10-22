package com.weartak.atak.weartak_companion.plugin;

/** Eligibility for cached CoT sent to a newly negotiated watch. */
final class GatewayReplay {
    private GatewayReplay() {}

    static boolean allows(String xml, String peerUid, long nowMillis) {
        GatewayCot.ReplayEvent event = GatewayCot.readReplayEvent(xml);
        if (event.staleMillis <= nowMillis)
            return false;
        if (GatewayCot.isChatType(event.type))
            return GatewayCot.chatAllowsRecipient(GatewayCot.readChat(xml), peerUid);
        return !event.uid.equals(peerUid);
    }
}
