package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class GarminMarkerDeleteTest {
    private static final String UID = "garmin-marker-pin-1";
    private static final long NOW_SECONDS = 1792843200L;

    private GarminMarkerDelete registry() {
        GarminMarkerDelete registry = new GarminMarkerDelete();
        assertTrue(registry.record(UID, "watch-1", "a-f-G", 38.0, -77.0, 12, 5, 6));
        return registry;
    }

    private JSONObject deletion(String uid, String session, Object createdAt) throws Exception {
        return new JSONObject().put("uid", uid).put("messageId", "delete-1")
                .put("createdAt", createdAt).put("relaySession", session);
    }

    @Test public void authorizationRequiresCurrentDeviceSessionAndCurrentDeleteTimestamp() throws Exception {
        GarminMarkerDelete registry = registry();
        assertNotNull(registry.authorize(deletion(UID, "session-2", NOW_SECONDS),
                "watch-1", "session-2", NOW_SECONDS));
        assertNull(registry.authorize(deletion(UID, "old-session", NOW_SECONDS),
                "watch-1", "session-2", NOW_SECONDS));
        assertNull(registry.authorize(deletion(UID, "session-2", NOW_SECONDS),
                "watch-2", "session-2", NOW_SECONDS));
        assertNull(registry.authorize(deletion(UID, "session-2", NOW_SECONDS - 301),
                "watch-1", "session-2", NOW_SECONDS));
        assertNull(registry.authorize(deletion(UID, "session-2", NOW_SECONDS + 301),
                "watch-1", "session-2", NOW_SECONDS));
        assertNull(registry.authorize(deletion(UID, "session-2", "1792843200"),
                "watch-1", "session-2", NOW_SECONDS));
        assertNull(registry.authorize(deletion("garmin-marker-peer-pin", "session-2", NOW_SECONDS),
                "watch-1", "session-2", NOW_SECONDS));
        assertNull(registry.authorize(deletion("garmin-marker-", "session-2", NOW_SECONDS),
                "watch-1", "session-2", NOW_SECONDS));
        assertNull(registry.authorize(deletion(UID, "session-2", NOW_SECONDS)
                .put("messageId", ""), "watch-1", "session-2", NOW_SECONDS));
    }

    @Test public void anotherWatchCannotClaimAnExistingUid() {
        GarminMarkerDelete registry = registry();
        assertFalse(registry.record(UID, "watch-2", "a-f-G", 38.0, -77.0, 12, 5, 6));
    }

    @Test public void deleteUsesUniqueEventUidAndForcedTargetLink() throws Exception {
        String xml = GarminMarkerDelete.deleteCot(UID,
                new GarminMarkerDelete.Owner("watch-1", "a-f-G", 38.0, -77.0, 12, 5, 6),
                1792843200000L);
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        org.w3c.dom.Element event = document.getDocumentElement();
        assertEquals("t-x-d-d", event.getAttribute("type"));
        assertNotEquals(UID, event.getAttribute("uid"));
        org.w3c.dom.Element point = (org.w3c.dom.Element) event.getElementsByTagName("point").item(0);
        assertEquals("38.0", point.getAttribute("lat"));
        org.w3c.dom.Element link = (org.w3c.dom.Element) event.getElementsByTagName("link").item(0);
        assertEquals(UID, link.getAttribute("uid"));
        assertEquals("none", link.getAttribute("relation"));
        assertEquals("none", link.getAttribute("type"));
        assertEquals(1, event.getElementsByTagName("__forcedelete").getLength());
    }
}
