package com.weartak.atak.weartak_companion.plugin;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Strict raw-vertex identity for watch-only imports and existing-route comparisons. */
final class RouteNavigationCot {
    private static final Pattern DECLARATION = Pattern.compile("<!\\s*(DOCTYPE|ENTITY)", Pattern.CASE_INSENSITIVE);
    final String uid;
    final List<String> vertices;

    private RouteNavigationCot(String uid, List<String> vertices) {
        this.uid = uid;
        this.vertices = vertices;
    }

    static RouteNavigationCot read(String xml, String expectedUid) {
        if (xml == null || xml.getBytes(StandardCharsets.UTF_8).length
                > PhoneRouteNavigationRelay.MAX_FRAME_BYTES || DECLARATION.matcher(xml).find()) {
            throw new IllegalArgumentException("Invalid route CoT size or declaration");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External route XML is not allowed");
            });
            Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (!"event".equals(root.getTagName()) || !root.getAttribute("type").startsWith("b-m-r")
                    || !expectedUid.equals(root.getAttribute("uid"))) {
                throw new IllegalArgumentException("Route UID or type mismatch");
            }
            List<String> vertices = new ArrayList<>();
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element) || !"detail".equals(child.getNodeName())) continue;
                for (Node link = child.getFirstChild(); link != null; link = link.getNextSibling()) {
                    if (link instanceof Element && "link".equals(link.getNodeName())
                            && ((Element) link).hasAttribute("point")) {
                        vertices.add(((Element) link).getAttribute("point"));
                    }
                }
            }
            PhoneRouteNavigationRelay.validateVertices(vertices, vertices.size());
            return new RouteNavigationCot(expectedUid, vertices);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed route CoT", e);
        }
    }

    boolean sameGeometry(RouteNavigationCot other) {
        if (!uid.equals(other.uid) || vertices.size() != other.vertices.size()) return false;
        for (int i = 0; i < vertices.size(); i++) {
            String[] a = vertices.get(i).split(",", -1);
            String[] b = other.vertices.get(i).split(",", -1);
            if (Math.abs(Double.parseDouble(a[0]) - Double.parseDouble(b[0])) > 0.000001
                    || Math.abs(Double.parseDouble(a[1]) - Double.parseDouble(b[1])) > 0.000001) return false;
        }
        return true;
    }
}
