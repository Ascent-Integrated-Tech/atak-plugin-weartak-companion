package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.SAXException;

/** Garmin route wire contract. Routes are atomic; invalid or oversized routes are rejected. */
final class GarminRouteProtocol {
    static final int VERSION = 1;
    static final int MAX_ENVELOPE_BYTES = 6000;
    static final int MAX_ROUTES = 8;
    static final int MAX_POINTS_PER_ROUTE = 64;
    static final int MAX_TOTAL_POINTS = 128;
    static final int MAX_SERVER_ID_BYTES = 64;
    static final int MAX_UID_BYTES = 128;
    static final int MAX_NAME_BYTES = 64;
    static final int MAX_MISSION_BYTES = 128;
    static final int MAX_SENDER_BYTES = 128;
    static final int MAX_ERROR_BYTES = 256;
    private static final Pattern DECLARATION = Pattern.compile("<!\\s*(DOCTYPE|ENTITY)",
            Pattern.CASE_INSENSITIVE);

    static final class Route {
        final String serverId;
        final String uid;
        final String name;
        final String missionName;
        final String senderUid;
        final List<Point> points;

        Route(String serverId, String uid, String name, String missionName,
                String senderUid, List<Point> points) {
            this.serverId = serverId;
            this.uid = uid;
            this.name = name;
            this.missionName = missionName;
            this.senderUid = senderUid;
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
        }

        String key() { return serverId + '\u0000' + uid; }

        Map<String, Object> payload() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("routeVersion", VERSION);
            payload.put("serverId", serverId);
            payload.put("uid", uid);
            payload.put("name", name);
            if (missionName != null) payload.put("missionName", missionName);
            if (senderUid != null) payload.put("senderUid", senderUid);
            List<Map<String, Object>> vertices = new ArrayList<>();
            for (Point point : points) vertices.add(point.payload());
            payload.put("points", vertices);
            return payload;
        }
    }

    static final class Point {
        final double lat;
        final double lon;
        final String name;

        Point(double lat, double lon, String name) {
            this.lat = lat;
            this.lon = lon;
            this.name = name;
        }

        Map<String, Object> payload() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("lat", lat);
            payload.put("lon", lon);
            if (name != null) payload.put("name", name);
            return payload;
        }
    }

    private GarminRouteProtocol() { }

    static Route route(String serverId, String uid, String name, String missionName,
            String senderUid, List<Point> points) {
        requireText(serverId, "serverId", MAX_SERVER_ID_BYTES, false);
        requireText(uid, "uid", MAX_UID_BYTES, false);
        requireText(name, "name", MAX_NAME_BYTES, false);
        if (missionName != null) requireText(missionName, "missionName", MAX_MISSION_BYTES, true);
        if (senderUid != null) requireText(senderUid, "senderUid", MAX_SENDER_BYTES, true);
        if (points == null || points.size() < 2 || points.size() > MAX_POINTS_PER_ROUTE) {
            throw new IllegalArgumentException("Route must contain 2 to 64 vertices");
        }
        List<Point> normalized = new ArrayList<>();
        for (Point point : points) {
            if (point == null || !Double.isFinite(point.lat) || !Double.isFinite(point.lon)
                    || point.lat < -90 || point.lat > 90 || point.lon < -180 || point.lon > 180) {
                throw new IllegalArgumentException("Route contains an invalid coordinate");
            }
            String pointName = point.name;
            if (pointName != null) requireText(pointName, "point name", MAX_NAME_BYTES, true);
            normalized.add(new Point(point.lat, point.lon, pointName));
        }
        Route route = new Route(serverId, uid, name, missionName, senderUid, normalized);
        if (!fitsEnvelope("route", route.payload())) {
            throw new IllegalArgumentException("Route exceeds the 6000-byte Garmin message limit");
        }
        return route;
    }

    static Map<String, Object> remove(String serverId, String uid) {
        requireText(serverId, "serverId", MAX_SERVER_ID_BYTES, false);
        requireText(uid, "uid", MAX_UID_BYTES, false);
        Map<String, Object> payload = new HashMap<>();
        payload.put("routeVersion", VERSION);
        payload.put("serverId", serverId);
        payload.put("uid", uid);
        return payload;
    }

    static Map<String, Object> reset() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("routeVersion", VERSION);
        return payload;
    }

    static Map<String, Object> error(String message) {
        String safe = message == null ? "Invalid route" : message.trim();
        if (safe.isEmpty()) safe = "Invalid route";
        while (utf8Length(safe) > MAX_ERROR_BYTES) safe = safe.substring(0, safe.length() - 1);
        Map<String, Object> payload = new HashMap<>();
        payload.put("routeVersion", VERSION);
        payload.put("error", safe);
        return payload;
    }

    static Route fromCotXml(String xml, Map<String, String> metadata, String fallbackUid) {
        if (xml == null || utf8Length(xml) > 32768) {
            throw new IllegalArgumentException("Route CoT exceeds 32 KiB");
        }
        if (DECLARATION.matcher(xml).find()) {
            throw new IllegalArgumentException("Route CoT must not contain DTD or entity declarations");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            // Android's DOM parser rejects the JAXP security feature flags.
            // Reject declarations above and independently block all external resolution.
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External route XML references are not allowed");
            });
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            if (!"event".equals(root.getTagName()) || !root.getAttribute("type").startsWith("b-m-r")) {
                throw new IllegalArgumentException("Unsupported route CoT type");
            }
            String uid = choose(value(metadata, "uid"), root.getAttribute("uid"), fallbackUid);
            Element detail = firstChild(root, "detail");
            String serverId = choose(value(metadata, "serverId"),
                    attribute(firstDescendant(detail, "__server"), "uid"), "local");
            String name = choose(value(metadata, "name"), attribute(firstDescendant(detail, "contact"), "callsign"),
                    attribute(firstDescendant(detail, "route"), "name"), uid);
            Element mission = firstDescendant(detail, "__mission");
            String missionName = choose(value(metadata, "missionName"),
                    attribute(mission, "missionName"), attribute(mission, "name"));
            String senderUid = value(metadata, "senderUid");
            if (senderUid == null) {
                NodeList links = detail == null ? null : detail.getElementsByTagName("link");
                if (links != null) {
                    for (int i = 0; i < links.getLength(); i++) {
                        Element link = (Element) links.item(i);
                        if ("p-p".equals(link.getAttribute("relation"))) {
                            senderUid = emptyToNull(link.getAttribute("uid"));
                            if (senderUid != null) break;
                        }
                    }
                }
            }
            List<Point> points = readPoints(detail);
            return route(serverId, uid, name, missionName, senderUid, points);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid route CoT", e);
        }
    }

    private static List<Point> readPoints(Element detail) {
        List<Point> points = new ArrayList<>();
        if (detail == null) return points;
        NodeList all = detail.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if ("point".equals(element.getTagName())
                    && element.hasAttribute("lat") && element.hasAttribute("lon")) {
                String name = choose(attribute(element, "name"), attribute(element, "callsign"));
                points.add(new Point(Double.parseDouble(element.getAttribute("lat")),
                        Double.parseDouble(element.getAttribute("lon")), name));
            } else if ("link".equals(element.getTagName()) && element.hasAttribute("point")) {
                String[] coordinates = element.getAttribute("point").split(",", -1);
                if (coordinates.length < 2) throw new IllegalArgumentException("Invalid route point");
                String name = choose(attribute(element, "name"), attribute(element, "callsign"));
                points.add(new Point(Double.parseDouble(coordinates[0]),
                        Double.parseDouble(coordinates[1]), name));
            }
        }
        return points;
    }

    private static Element firstChild(Element parent, String tag) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && tag.equals(((Element) child).getTagName()))
                return (Element) child;
        }
        return null;
    }

    private static Element firstDescendant(Element parent, String tag) {
        if (parent == null) return null;
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String attribute(Element element, String name) {
        return element == null ? null : emptyToNull(element.getAttribute(name));
    }

    private static String value(Map<String, String> map, String key) {
        return map == null ? null : emptyToNull(map.get(key));
    }

    private static String choose(String... values) {
        for (String candidate : values) {
            String value = emptyToNull(candidate);
            if (value != null) return value;
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    static boolean fitsEnvelope(String msgType, Map<String, Object> payload) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("msgType", msgType);
        envelope.put("payload", payload);
        return utf8Length(new JSONObject(envelope).toString()) <= MAX_ENVELOPE_BYTES;
    }

    static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void requireText(String value, String field, int maxBytes, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.trim().isEmpty())
                || !value.equals(value.trim()) || utf8Length(value) > maxBytes) {
            throw new IllegalArgumentException("Invalid route " + field);
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("Invalid route " + field);
            }
        }
    }
}
