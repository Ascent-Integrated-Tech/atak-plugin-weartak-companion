package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class PhoneUserRelayTest {
    private final PhoneUserRelay relay = new PhoneUserRelay();
    private final List<String> writes = new ArrayList<>();
    private final PhoneUserRelay.Writer writer = (json, session) -> { writes.add(json); return true; };

    private static PhoneUserRelay.User user(String uid, String xml, long stale) {
        return new PhoneUserRelay.User(uid, xml, stale);
    }

    @Test public void supportsRemotePliButExcludesLocalIdentitiesPointsAlertsAndExpiredUsers() {
        assertTrue(eligible("remote", "a-f-G-U-C", true, false, 20, 50, 100));
        assertTrue(eligible("remote", "a-h-G-U", true, false, 20, 50, 100));
        assertFalse(eligible("phone", "a-f-G-U-C", true, false, 20, 50, 100));
        assertFalse(eligible("watch", "a-f-G-U-C", true, false, 20, 50, 100));
        assertFalse(eligible("remote", "a-f-G", false, false, 20, 50, 100));
        assertFalse(eligible("remote", "b-a-o", true, false, 20, 50, 100));
        assertFalse(eligible("remote", "a-f-G-U-C", true, true, 20, 50, 100));
        assertFalse(eligible("remote", "a-f-G-U-C", true, false, Double.NaN, 50, 100));
        assertFalse(eligible("remote", "a-f-G-U-C", true, false, 20, 181, 100));
        assertFalse(eligible("remote", "a-f-G-U-C", true, false, 20, 50, 1));
    }

    private static boolean eligible(String uid, String type, boolean user, boolean excluded,
                                    double lat, double lon, long stale) {
        return PhoneUserRelay.eligible(uid, "phone", "watch", type, user, excluded, lat, lon, stale, 1);
    }

    @Test public void snapshotAndUpdatesPreserveXmlIdentityAndDoNotAuthorizePointDeletion() throws Exception {
        assertTrue(relay.forward(user("remote", "<event/>", 100), "phone", 5, 5, 0, writer));
        JSONObject message = new JSONObject(writes.get(0));
        assertEquals("phone_user_cot", message.getString("msg_type"));
        assertEquals("<event/>", message.getJSONObject("payload").getString("cot"));
        assertEquals("phone", message.getJSONObject("payload").getString("phoneUid"));
        assertFalse(message.getJSONObject("payload").has("revision"));
        assertTrue(relay.forward(user("remote", "<event/>", 100), "phone", 5, 5, 1, writer));
        assertEquals(1, writes.size());
        assertTrue(relay.forward(user("remote", "<changed/>", 200), "phone", 5, 5, 1, writer));
        assertEquals(2, writes.size());
    }

    @Test public void reconnectReplaysSameUsersButStaleSessionCannotSend() throws Exception {
        relay.forward(user("remote", "xml", 100), "phone", 5, 5, 0, writer);
        relay.reset(-1);
        assertFalse(relay.forward(user("remote", "xml", 100), "phone", 5, 6, 0, writer));
        assertTrue(relay.forward(user("remote", "xml", 100), "phone", 6, 6, 0, writer));
        assertEquals(2, writes.size());
        relay.remove("remote", "phone", 5, writer);
        assertEquals(2, writes.size());
    }

    @Test public void handoffFailureRetriesAndDeletionOnlyTargetsPublishedCopy() throws Exception {
        assertFalse(relay.forward(user("remote", "xml", 100), "phone", 5, 5, 0, (json, s) -> false));
        assertFalse(relay.contains("remote"));
        relay.remove("unknown", "phone", 5, writer);
        assertTrue(writes.isEmpty());
        relay.forward(user("remote", "xml", 100), "phone", 5, 5, 0, writer);
        assertFalse(relay.remove("remote", "phone", 5, (json, s) -> false));
        assertTrue(relay.contains("remote"));
        assertTrue(relay.remove("remote", "phone", 5, writer));
        assertFalse(relay.contains("remote"));
        JSONObject message = new JSONObject(writes.get(1));
        assertEquals("phone_user_remove", message.getString("msg_type"));
        assertEquals("remote", message.getJSONObject("payload").getString("targetUid"));
        assertFalse(message.toString().contains("marker_delete"));
    }

    @Test public void expiryRemovesOneCopyPerPumpAndNeverRefreshesStaleData() throws Exception {
        relay.forward(user("one", "xml", 100), "phone", 5, 5, 0, writer);
        relay.forward(user("two", "xml", 100), "phone", 5, 5, 0, writer);
        relay.expire("phone", 5, 99, writer);
        assertEquals(2, writes.size());
        relay.expire("phone", 5, 100, writer);
        assertEquals(3, writes.size());
        relay.expire("phone", 5, 100, writer);
        assertEquals(4, writes.size());
        relay.forward(user("stale", "xml", 100), "phone", 5, 5, 100, writer);
        assertEquals(4, writes.size());
    }

    @Test public void capacityIsExplicitAndPublishedUsersCanStillUpdateOrLeave() throws Exception {
        for (int i = 0; i < PhoneUserRelay.CAPACITY; i++) {
            relay.forward(user("u" + i, "xml", 100), "phone", 5, 5, 0, writer);
        }
        assertThrows(IllegalStateException.class,
                () -> relay.forward(user("overflow", "xml", 100), "phone", 5, 5, 0, writer));
        relay.forward(user("u0", "new", 100), "phone", 5, 5, 0, writer);
        relay.remove("u1", "phone", 5, writer);
        assertTrue(relay.forward(user("overflow", "xml", 100), "phone", 5, 5, 0, writer));
    }
}
