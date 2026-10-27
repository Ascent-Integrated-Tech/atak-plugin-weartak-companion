package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

public class CompanionPaneLayoutTest {
    private static Document layout(String name) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Paths.get("src", "main", "res", "layout", name).toFile());
    }

    private static Element byId(Document document, String id) {
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (("@+id/" + id).equals(element.getAttribute("android:id"))) return element;
        }
        throw new AssertionError("Missing view: " + id);
    }

    @Test public void deviceContentHasOneScrollOwnerAndNoCollapsedWeightedPanels() throws Exception {
        Document document = layout("main_layout.xml");
        Element scroll = byId(document, "deviceScreenScroll");
        Element root = byId(document, "deviceScreenRoot");
        assertEquals("ScrollView", scroll.getTagName());
        assertSame(scroll, root.getParentNode());
        assertEquals("wrap_content", root.getAttribute("android:layout_height"));
        assertEquals(0, root.getElementsByTagName("ScrollView").getLength());
        for (String id : new String[]{"gatewayScroll", "garminPanel", "deviceList"}) {
            assertEquals(id, "wrap_content", byId(document, id).getAttribute("android:layout_height"));
        }
        assertEquals("false", byId(document, "deviceList").getAttribute("android:nestedScrollingEnabled"));
        assertSame(document.getDocumentElement(), byId(document, "settingsScreenRoot").getParentNode());
    }

    @Test public void purposeSelectorHasItsOwnFullWidthWrappingRow() throws Exception {
        Document document = layout("main_layout.xml");
        Element purpose = byId(document, "purposeButton");
        assertSame(byId(document, "deviceScreenRoot"), purpose.getParentNode());
        assertNotSame(byId(document, "diagnosticsToggle").getParentNode(), purpose.getParentNode());
        assertEquals("match_parent", purpose.getAttribute("android:layout_width"));
        assertEquals("wrap_content", purpose.getAttribute("android:layout_height"));
        assertFalse(purpose.hasAttribute("android:maxLines"));
        assertFalse(purpose.hasAttribute("android:ellipsize"));
        assertEquals("false", purpose.getAttribute("android:textAllCaps"));
    }

    @Test public void smallLogGlyphsRetainAccessibleTouchTargets() throws Exception {
        Document document = layout("main_layout.xml");
        for (String id : new String[]{"diagnosticsToggle", "diagnosticsExportButton"}) {
            Element icon = byId(document, id);
            assertEquals("48dp", icon.getAttribute("android:layout_width"));
            assertEquals("48dp", icon.getAttribute("android:layout_height"));
            assertEquals("16dp", icon.getAttribute("android:padding"));
            assertFalse(icon.getAttribute("android:contentDescription").isEmpty());
        }
    }

    @Test public void deviceIdentityAndActionsDoNotCompeteForHorizontalSpace() throws Exception {
        Document document = layout("item_ble_device.xml");
        Element name = byId(document, "deviceName");
        Element actions = byId(document, "actionRow");
        assertEquals("parent", name.getAttribute("app:layout_constraintEnd_toEndOf"));
        assertEquals("0dp", actions.getAttribute("android:layout_width"));
        assertEquals("@id/rssiBadge", actions.getAttribute("app:layout_constraintTop_toBottomOf"));
        for (String id : new String[]{"actionButton", "disconnectButton"}) {
            Element button = byId(document, id);
            assertEquals("0dp", button.getAttribute("android:layout_width"));
            assertEquals("1", button.getAttribute("android:layout_weight"));
            assertEquals("wrap_content", button.getAttribute("android:layout_height"));
            assertEquals("48dp", button.getAttribute("android:minHeight"));
        }
    }
}
