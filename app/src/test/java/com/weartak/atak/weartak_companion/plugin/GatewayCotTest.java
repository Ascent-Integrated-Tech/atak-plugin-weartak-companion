package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import static org.junit.Assert.*;

public class GatewayCotTest {
    @Test public void pliRetainsWatchIdentityAndCoordinates() throws Exception {
        String cot = GatewayCot.build("{\"msgType\":\"pli\",\"payload\":{\"uid\":\"watch-one\","
                + "\"cs\":\"WATCH & ONE\",\"lat\":12.3,\"lon\":45.6,\"hae\":123,"
                + "\"tStart\":\"2026-10-14T12:00:00Z\",\"tStale\":\"2026-10-14T12:02:00Z\"}}",
                "other", "OTHER");
        assertTrue(cot.contains("uid='watch-one'"));
        assertTrue(cot.contains("callsign='WATCH &amp; ONE'"));
        assertTrue(cot.contains("lat='12.3' lon='45.6' hae='123.0'"));
        assertTrue(cot.contains("time='2026-10-14T12:00:00Z'"));
        assertFalse(cot.contains("OTHER"));
    }

    @Test public void watchesNeverCollapseToPhoneOrEachOther() throws Exception {
        String json = "{\"msgType\":\"pli\",\"payload\":{\"lat\":1,\"lon\":2}}";
        String a = GatewayCot.build(json, "one", "ONE");
        String b = GatewayCot.build(json, "two", "TWO");
        assertTrue(a.contains("uid='one'"));
        assertTrue(b.contains("uid='two'"));
        assertNotEquals(a, b);
    }

    @Test public void markerPreservesEventUidTitleTypeParentAndWatchFix() throws Exception {
        String cot = GatewayCot.build("{\"msgType\":\"marker\",\"payload\":{\"uid\":\"point-id\","
                + "\"title\":\"Point <A>\",\"cs\":\"WATCH\",\"type\":\"a-h-G\","
                + "\"parentUid\":\"watch-id\",\"lat\":-10,\"lon\":20}}", "watch-id", "WATCH");
        assertTrue(cot.contains("uid='point-id' type='a-h-G'"));
        assertTrue(cot.contains("callsign='Point &lt;A&gt;'"));
        assertTrue(cot.contains("<link uid='watch-id'"));
        assertTrue(cot.contains("lat='-10.0' lon='20.0'"));
    }

    @Test public void realWatchPliEnvelopeUsesMsgUidAndNeverInventsTeamOrRole() throws Exception {
        String cot = GatewayCot.build("{\"msgType\":\"pli\",\"msgUid\":\"watch-one\","
                + "\"payload\":{\"cs\":\"WATCH\",\"lat\":1,\"lon\":2}}", null, null);
        assertTrue(cot.contains("uid='watch-one'"));
        assertFalse(cot.contains("__group"));
        String supplied = GatewayCot.build("{\"msgType\":\"pli\",\"msgUid\":\"watch-one\","
                + "\"payload\":{\"cs\":\"WATCH\",\"lat\":1,\"lon\":2,\"team\":\"Orange\","
                + "\"role\":\"Medic\"}}", null, null);
        assertTrue(supplied.contains("<__group name='Orange' role='Medic'/>"));
    }

    @Test public void missingIdentityOrPositionFailsInsteadOfSubstitutingPhone() {
        assertThrows(Exception.class, () -> GatewayCot.build(
                "{\"msgType\":\"pli\",\"payload\":{\"lat\":1,\"lon\":2}}", null, null));
        assertThrows(Exception.class, () -> GatewayCot.build(
                "{\"msgType\":\"pli\",\"payload\":{\"uid\":\"watch\",\"cs\":\"WATCH\"}}", null, null));
        assertThrows(Exception.class, () -> GatewayCot.build(
                "{\"msgType\":\"marker\",\"payload\":{\"uid\":\"m\",\"cs\":\"W\",\"lat\":91,\"lon\":2}}",
                "watch", "WATCH"));
    }

    @Test public void controlAndSettingsRequestsCannotBecomeCot() throws Exception {
        for (String type : new String[]{"settings_request", "settings_changed", "watch_info",
                "entity_sync_request", "marker_delete", "bloodhound_request", "chat", "emergency"}) {
            assertNull(GatewayCot.build("{\"msgType\":\"" + type + "\"}", "watch", "WATCH"));
        }
    }

    @Test public void mapEnvelopeCannotSmuggleAlertOrChatTypes() {
        for (String kind : new String[]{"pli", "marker"}) {
            for (String type : new String[]{"b-a-o", "b-t-f", "u-d-p"}) {
                assertThrows(IllegalArgumentException.class, () -> GatewayCot.build(
                        "{\"msgType\":\"" + kind + "\",\"payload\":{\"uid\":\"watch\","
                                + "\"cs\":\"WATCH\",\"lat\":1,\"lon\":2,\"type\":\"" + type + "\"}}",
                        "watch", "WATCH"));
            }
        }
    }

    @Test public void chatCoTRetainsDirectRecipientsAndTextEscaping() {
        GatewayCot.ChatRoute route = GatewayCot.buildChat(
                "watch-a", "Watch A", "message-1", "Check <north> & hold",
                1791982800000L, "watch-b", "Watch B", false, null);
        assertFalse(route.broadcast);
        assertTrue(route.recipients.contains("watch-b"));
        assertTrue(route.xml.contains("uid0='watch-a'"));
        assertTrue(route.xml.contains("uid1='watch-b'"));
        assertTrue(route.xml.contains("Check &lt;north&gt; &amp; hold"));
        GatewayCot.ChatRoute parsed = GatewayCot.readChat(route.xml);
        assertEquals(route.uid, parsed.uid);
        assertEquals(route.recipients, parsed.recipients);
        assertFalse(parsed.broadcast);
    }

    @Test public void groupAndBroadcastChatRoutingIsExplicit() {
        GatewayCot.ChatRoute group = GatewayCot.buildChat(
                "sender", "Sender", "group-1", "Group", 1791982800000L,
                "team-1", "Team", true, new String[]{"watch-a", "watch-b"});
        assertEquals(2, group.recipients.size());
        assertTrue(group.recipients.contains("watch-a"));
        assertTrue(group.recipients.contains("watch-b"));
        GatewayCot.ChatRoute broadcast = GatewayCot.buildChat(
                "sender", "Sender", "broadcast-1", "Everyone", 1791982800000L,
                "All Chat Rooms", "All Chat Rooms", true, null);
        assertTrue(broadcast.broadcast);
        assertTrue(GatewayCot.readChat(broadcast.xml).broadcast);
        assertThrows(IllegalArgumentException.class, () -> GatewayCot.buildChat(
                "sender", "Sender", "group-2", "No recipients", 1791982800000L,
                "team-1", "Team", true, null));
    }

    @Test public void gatewayScopeIncludesOnlyDefinedAlertAndChatTypes() {
        assertTrue(GatewayCot.supportedType("b-a-o"));
        assertTrue(GatewayCot.supportedType("b-a-o-can"));
        assertTrue(GatewayCot.supportedType("b-a-g"));
        assertTrue(GatewayCot.supportedType("b-t-f"));
        assertFalse(GatewayCot.supportedType("u-d-p"));
        assertFalse(GatewayCot.supportedType("t-x-g-c"));
    }
}
