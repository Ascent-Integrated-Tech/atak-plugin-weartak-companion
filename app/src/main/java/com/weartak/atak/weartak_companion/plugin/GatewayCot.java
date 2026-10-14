package com.weartak.atak.weartak_companion.plugin;

import android.os.Bundle;
import com.atakmap.coremap.cot.event.CotEvent;
import org.json.JSONObject;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Supported Gateway CoT validation and recipient-preserving chat conversion. */
final class GatewayCot {
    private GatewayCot() {}

    static final class ChatRoute {
        final String uid;
        final String senderUid;
        final String messageId;
        final String xml;
        final boolean broadcast;
        final Set<String> recipients;

        ChatRoute(String uid, String senderUid, String messageId, String xml,
                  boolean broadcast, Set<String> recipients) {
            this.uid = uid;
            this.senderUid = senderUid;
            this.messageId = messageId;
            this.xml = xml;
            this.broadcast = broadcast;
            this.recipients = recipients;
        }
    }

    static boolean isPositionType(String type) {
        return "a-f-G-U-C".equals(type) || type.startsWith("a-f-G-U-C-")
                || "a-f-G-E".equals(type) || type.startsWith("a-f-G-E-");
    }

    static boolean isEmergencyType(String type) {
        return "b-a-g".equals(type) || "b-a-o".equals(type) || type.startsWith("b-a-o-");
    }

    static boolean isChatType(String type) { return "b-t-f".equals(type); }

    static boolean supportedType(String type) {
        return type != null && (PhoneMarkerRelay.supportedType(type)
                || isPositionType(type) || isEmergencyType(type) || isChatType(type));
    }

    static CotEvent validate(String xml) {
        if (xml == null || xml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
            throw new IllegalArgumentException("Gateway CoT exceeds 32 KiB");
        CotEvent event = CotEvent.parse(xml);
        if (event == null || !event.isValid() || !supportedType(event.getType()))
            throw new IllegalArgumentException("Unsupported gateway map, position, alert, or chat CoT");
        if (isChatType(event.getType())) readChat(xml);
        return event;
    }

    static void validatePeer(String xml, String peerUid) {
        CotEvent event = validate(xml);
        String type = event.getType();
        if (isPositionType(type)) {
            if (!peerUid.equals(event.getUID()))
                throw new IllegalArgumentException("Watch position UID does not match negotiated identity");
        } else if (PhoneMarkerRelay.supportedType(type)) {
            if (!peerUid.equals(firstLinkedUid(xml)))
                throw new IllegalArgumentException("Watch marker is not linked to negotiated identity");
        } else if (isEmergencyType(type)) {
            String sender = firstLinkedUid(xml);
            if (sender == null) sender = firstBiometricUid(xml);
            if (!peerUid.equals(sender))
                throw new IllegalArgumentException("Watch alert is not owned by negotiated identity");
        } else if (isChatType(type) && !peerUid.equals(readChat(xml).senderUid)) {
            throw new IllegalArgumentException("Watch chat sender does not match negotiated identity");
        }
    }

    static ChatRoute buildChat(Bundle message) {
        if (message == null) throw new IllegalArgumentException("Missing ATAK chat message");
        String conversationUid = message.getString("conversationId", "");
        boolean group = com.atakmap.android.contact.Contacts.getInstance()
                .getContactByUuid(conversationUid) instanceof com.atakmap.android.contact.GroupContact;
        return buildChat(message.getString("senderUid"), message.getString("senderCallsign"),
                message.getString("messageId"), message.getString("message"),
                message.getLong("receiveTime", System.currentTimeMillis()), conversationUid,
                message.getString("conversationName", ""), group, message.getStringArray("destinations"));
    }

    static ChatRoute buildChat(String senderUid, String senderCallsign, String messageId, String text,
                               long timeMillis, String conversationUid, String conversationTitle,
                               boolean group, String[] destinations) {
        require(senderUid, "Chat sender UID");
        require(senderCallsign, "Chat sender callsign");
        require(messageId, "Chat message ID");
        require(text, "Chat text");
        require(conversationTitle, "Chat room title");
        if (timeMillis <= 0L) throw new IllegalArgumentException("Invalid chat event time");
        String room = conversationUid == null ? "" : conversationUid.trim();
        boolean broadcast = "All Chat Rooms".equals(room);
        Set<String> recipients = new LinkedHashSet<>();
        if (!broadcast && destinations != null) {
            for (String destination : destinations) {
                if (destination != null && !destination.trim().isEmpty()
                        && !senderUid.equals(destination.trim())) recipients.add(destination.trim());
            }
        }
        if (!broadcast && recipients.isEmpty() && destinations == null && !group && !room.isEmpty()
                && !senderUid.equals(room)) recipients.add(room);
        if (!broadcast && recipients.isEmpty())
            throw new IllegalArgumentException("Gateway chat has no explicit recipient");
        if (room.isEmpty()) room = recipients.iterator().next();

        String when = Instant.ofEpochMilli(timeMillis).toString();
        String stale = Instant.ofEpochMilli(timeMillis + 900000L).toString();
        StringBuilder groupXml = new StringBuilder("<chatgrp uid0='")
                .append(xml(senderUid)).append("' id='").append(xml(room)).append("'");
        int index = 1;
        for (String recipient : recipients)
            groupXml.append(" uid").append(index++).append("='").append(xml(recipient)).append("'");
        groupXml.append("/>");
        StringBuilder detail = new StringBuilder("<__chat parent='RootContactGroup' groupOwner='false'")
                .append(" messageId='").append(xml(messageId)).append("' chatroom='")
                .append(xml(conversationTitle)).append("' id='").append(xml(room))
                .append("' senderCallsign='").append(xml(senderCallsign)).append("'>")
                .append(groupXml).append("</__chat><link uid='").append(xml(senderUid))
                .append("' type='a-f-G-U-C' relation='p-p'/><remarks source='")
                .append(xml(senderUid)).append("' to='").append(xml(broadcast ? "All Chat Rooms" : room))
                .append("' time='").append(xml(when)).append("'>").append(xml(text)).append("</remarks>");
        if (!broadcast) {
            detail.append("<marti>");
            for (String recipient : recipients)
                detail.append("<dest uid='").append(xml(recipient)).append("'/>");
            detail.append("</marti>");
        }
        String uid = "GeoChat." + senderUid + "." + room + "." + messageId;
        String cot = "<event version='2.0' uid='" + xml(uid) + "' type='b-t-f' time='"
                + xml(when) + "' start='" + xml(when) + "' stale='" + xml(stale)
                + "' how='h-g-i-g-o'><point lat='0' lon='0' hae='0' ce='999999' le='999999'/>"
                + "<detail>" + detail + "</detail></event>";
        return new ChatRoute(uid, senderUid, messageId, cot, broadcast, recipients);
    }

    static ChatRoute readChat(String xml) {
        try {
            Element root = document(xml).getDocumentElement();
            if (!"event".equals(root.getTagName()) || !"b-t-f".equals(root.getAttribute("type")))
                throw new IllegalArgumentException("Unsupported Gateway chat CoT");
            String uid = required(root.getAttribute("uid"), "Chat event UID");
            Instant.parse(required(root.getAttribute("time"), "Chat event time"));
            Element chat = first(root, "__chat");
            Element group = chat == null ? null : first(chat, "chatgrp");
            if (group == null) throw new IllegalArgumentException("Gateway chat sender is missing");
            String sender = required(group.getAttribute("uid0"), "Chat sender UID");
            required(chat == null ? null : chat.getAttribute("senderCallsign"), "Chat sender callsign");
            String messageId = required(chat == null ? null : chat.getAttribute("messageId"), "Chat message ID");
            String room = required(chat == null ? null : chat.getAttribute("id"), "Chat room UID");
            required(chat == null ? null : chat.getAttribute("chatroom"), "Chat room title");
            if (group != null && !group.getAttribute("id").isEmpty()
                    && !room.equals(group.getAttribute("id")))
                throw new IllegalArgumentException("Gateway chat room IDs do not match");
            Element remarks = first(root, "remarks");
            if (remarks == null || remarks.getTextContent().trim().isEmpty())
                throw new IllegalArgumentException("Gateway chat text is missing");
            Set<String> recipients = new LinkedHashSet<>();
            if (group != null) {
                NamedNodeMap attributes = group.getAttributes();
                for (int i = 0; i < attributes.getLength(); i++) {
                    Node attribute = attributes.item(i);
                    if (attribute.getNodeName().matches("uid[1-9][0-9]*")
                            && !sender.equals(attribute.getNodeValue()))
                        recipients.add(attribute.getNodeValue());
                }
            }
            Element marti = first(root, "marti");
            if (marti != null) {
                for (Element dest : children(marti, "dest")) {
                    String recipient = dest.getAttribute("uid");
                    if (!recipient.isEmpty() && !sender.equals(recipient)) recipients.add(recipient);
                }
            }
            String remarkTo = remarks.getAttribute("to");
            boolean broadcast = "All Chat Rooms".equals(room) || "All Chat Rooms".equals(remarkTo);
            if (!broadcast && recipients.isEmpty() && !remarkTo.isEmpty() && !sender.equals(remarkTo))
                recipients.add(remarkTo);
            if (!broadcast && recipients.isEmpty())
                throw new IllegalArgumentException("Gateway chat has no explicit recipient");
            return new ChatRoute(uid, sender, messageId, xml, broadcast, recipients);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed Gateway chat CoT", e);
        }
    }

    private static String firstLinkedUid(String xml) {
        try {
            Element link = first(document(xml).getDocumentElement(), "link");
            return link == null ? null : link.getAttribute("uid");
        } catch (Exception e) { return null; }
    }

    private static String firstBiometricUid(String xml) {
        try {
            Element biometrics = first(document(xml).getDocumentElement(), "biometrics");
            Element device = biometrics == null ? null : first(biometrics, "device");
            Element uid = device == null ? null : first(device, "uid");
            return uid == null ? null : uid.getTextContent().trim();
        } catch (Exception e) { return null; }
    }

    private static org.w3c.dom.Document document(String xml) throws Exception {
        if (xml == null || xml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
            throw new IllegalArgumentException("Gateway CoT exceeds 32 KiB");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setAttribute(factory, "http://javax.xml.XMLConstants/property/accessExternalDTD", "");
        setAttribute(factory, "http://javax.xml.XMLConstants/property/accessExternalSchema", "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try { factory.setFeature(name, value); } catch (Exception ignored) { }
    }

    private static void setAttribute(DocumentBuilderFactory factory, String name, String value) {
        try { factory.setAttribute(name, value); } catch (Exception ignored) { }
    }

    private static Element first(Element root, String name) {
        if (root == null) return null;
        org.w3c.dom.NodeList nodes = root.getElementsByTagName(name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static List<Element> children(Element root, String name) {
        List<Element> elements = new ArrayList<>();
        org.w3c.dom.NodeList nodes = root.getElementsByTagName(name);
        for (int i = 0; i < nodes.getLength(); i++) elements.add((Element) nodes.item(i));
        return elements;
    }

    static String build(String json, String watchUid, String watchCallsign) throws Exception {
        JSONObject env = new JSONObject(json);
        String kind = env.optString("msgType");
        if (!"pli".equals(kind) && !"marker".equals(kind)) return null;
        JSONObject p = env.getJSONObject("payload");
        String source = value(p, "uid", "pli".equals(kind) ? value(env, "msgUid", watchUid) : watchUid);
        String callsign = value(p, "cs", value(p, "callsign", watchCallsign));
        require(source, "Watch UID");
        require(callsign, "Watch callsign");
        String uid = value(p, "uid", "marker".equals(kind) ? env.optString("msgUid") : source);
        require(uid, "Event UID");
        String time = value(p, "tStart", Instant.now().toString());
        String start = value(p, "start", time);
        String stale = value(p, "tStale", Instant.now().plusSeconds(120).toString());
        Instant.parse(time);
        Instant.parse(start);
        Instant.parse(stale);
        String type = value(p, "type", "a-f-G-U-C");
        String detail = "<contact callsign='" + xml(callsign) + "'/>";
        if ("pli".equals(kind)) {
            if (p.has("team") && p.has("role")) {
                detail += "<__group name='" + xml(p.getString("team"))
                        + "' role='" + xml(p.getString("role")) + "'/>";
            }
            detail += "<track course='" + number(p, "course", 0)
                    + "' speed='" + number(p, "speed", 0) + "'/>";
        } else if ("marker".equals(kind)) {
            type = value(p, "type", "a-f-G");
            if (!PhoneMarkerRelay.supportedType(type)) {
                throw new IllegalArgumentException("Unsupported gateway marker type: " + type);
            }
            detail = "<contact callsign='" + xml(value(p, "title", callsign)) + "'/>";
            String parent = value(p, "parentUid", value(p, "linkUid", watchUid));
            if (parent != null && !parent.isEmpty()) {
                detail += "<link uid='" + xml(parent) + "' type='a-f-G-U-C' relation='p-p'/>";
            }
            if (p.has("icn")) detail += "<usericon iconsetpath='" + xml(p.getString("icn")) + "'/>";
        }
        if ("pli".equals(kind) && !("a-f-G-U-C".equals(type) || type.startsWith("a-f-G-U-C-")
                || "a-f-G-E".equals(type) || type.startsWith("a-f-G-E-"))) {
            throw new IllegalArgumentException("Unsupported gateway position type: " + type);
        }
        detail += "<remarks>" + xml(value(p, "remarks", "")) + "</remarks>";
        if (p.has("bat") || p.has("battery_percent")) {
            detail += "<status battery='" + number(p, p.has("bat") ? "bat" : "battery_percent", 0) + "'/>";
        }
        String heartRate = p.has("hr") ? "hr"
                : p.has("heart_rate") ? "heart_rate" : p.has("heartRateBpm") ? "heartRateBpm" : null;
        if (heartRate != null) detail += "<physio hr='" + number(p, heartRate, 0) + "'/>";
        // Missing watch fixes must never silently become the phone's position.
        double lat = p.getDouble("lat");
        double lon = p.getDouble("lon");
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw new IllegalArgumentException("Invalid watch coordinates");
        }
        return "<event version='2.0' uid='" + xml(uid) + "' type='" + xml(type)
                + "' time='" + xml(time) + "' start='" + xml(start) + "' stale='"
                + xml(stale) + "' how='h-g-i-g-o'><point lat='" + lat + "' lon='" + lon
                + "' hae='" + number(p, "hae", 9999999) + "' ce='"
                + number(p, "ce", 9999999) + "' le='" + number(p, "le", 9999999)
                + "'/><detail>" + detail + "</detail></event>";
    }

    private static double number(JSONObject p, String key, double fallback) throws Exception {
        double value = p.has(key) ? p.getDouble(key) : fallback;
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    private static String value(JSONObject p, String key, String fallback) {
        String value = p.optString(key, "");
        return value.isEmpty() ? fallback : value;
    }
    private static void require(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " missing");
    }
    private static String required(String value, String label) {
        require(value, label);
        return value;
    }
    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&apos;").replace("\"", "&quot;");
    }
}
