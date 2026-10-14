package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GatewayNegotiationTest {
    private JSONObject ack(String token, int version, boolean maps, boolean emergency, boolean chat) throws Exception {
        return new JSONObject().put("session", token).put("version", version).put("mapPosition", maps)
                .put("emergency", emergency).put("chat", chat)
                .put("uid", "watch").put("callsign", "WATCH");
    }
    @Test public void legacyReadyAndWrongOrOldAcksCannotUnlockMapTraffic() throws Exception {
        GatewayNegotiation session = new GatewayNegotiation();
        session.begin("nonce", 7);
        assertFalse(session.accepts("nonce", 7));
        assertFalse(session.acknowledge(new JSONObject().put("msgType", "watch_info"), 7));
        assertFalse(session.acknowledge(ack("old", 1, true, true, true), 7));
        assertFalse(session.acknowledge(ack("nonce", 2, true, true, true), 7));
        assertFalse(session.acknowledge(ack("nonce", 1, false, true, true), 7));
        assertFalse(session.acknowledge(ack("nonce", 1, true, false, true), 7));
        assertFalse(session.acknowledge(ack("nonce", 1, true, true, false), 7));
        assertFalse(session.acknowledge(ack("nonce", 1, true, true, true), 8));
        assertTrue(session.acknowledge(ack("nonce", 1, true, true, true), 7));
        assertTrue(session.accepts("nonce", 7));
        assertFalse(session.accepts("nonce", 8));
        session.clear();
        assertFalse(session.accepts("nonce", 7));
        session.begin("new", 9);
        assertFalse(session.acknowledge(ack("nonce", 1, true, true, true), 9));
    }
    @Test public void oneWatchNegotiationCannotAuthorizeAnotherWatch() throws Exception {
        GatewayNegotiation a = new GatewayNegotiation(), b = new GatewayNegotiation();
        a.begin("a", 1); b.begin("b", 1);
        assertTrue(a.acknowledge(ack("a", 1, true, true, true), 1));
        assertFalse(b.accepts("a", 1));
        assertFalse(b.acknowledge(ack("a", 1, true, true, true), 1));
        assertFalse(b.accepts("b", 1));
    }
}
