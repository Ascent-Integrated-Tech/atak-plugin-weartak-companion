package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.*;

public class RouteNavigationCotTest {
    private static final String XML = "<event uid='r' type='b-m-r'><detail>"
            + "<link point='38,-77' type='b-m-p-w' uid='p1'/>"
            + "<link point='38.01,-77.01' type='b-m-p-c' uid='p2'/>"
            + "<link point='38.02,-77.02' type='b-m-p-w' uid='p3'/></detail></event>";

    @Test public void preservesRawControlAndCheckpointIndicesAndAcceptsExportPrecision() {
        RouteNavigationCot route = RouteNavigationCot.read(XML, "r");
        assertEquals(3, route.vertices.size());
        assertEquals("38.01,-77.01", route.vertices.get(1));
        assertTrue(route.sameGeometry(RouteNavigationCot.read(XML.replace("38.01,", "38.0100001,"), "r")));
        assertFalse(route.sameGeometry(RouteNavigationCot.read(XML.replace("38.01,", "38.011,"), "r")));
    }

    @Test public void missingInvalidReorderedAndExtraVerticesCannotSilentlyChangeIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
                RouteNavigationCot.read(XML.replace("38.01", "NaN"), "r"));
        assertThrows(IllegalArgumentException.class, () -> RouteNavigationCot.read(XML, "wrong"));
        assertThrows(IllegalArgumentException.class, () ->
                RouteNavigationCot.read(XML.replace("b-m-r", "a-f-G"), "r"));
        RouteNavigationCot original = RouteNavigationCot.read(XML, "r");
        assertFalse(original.sameGeometry(RouteNavigationCot.read(
                XML.replace("<link point='38.01,-77.01' type='b-m-p-c' uid='p2'/>", ""), "r")));
        assertFalse(original.sameGeometry(RouteNavigationCot.read(
                XML.replace("38,-77", "38.02,-77.02").replace("38.01,-77.01", "38,-77"), "r")));
    }

    @Test public void declarationsAndExternalEntitiesAreRejectedBeforeParsing() {
        assertThrows(IllegalArgumentException.class, () ->
                RouteNavigationCot.read("<!DOCTYPE event SYSTEM 'file:///private'>" + XML, "r"));
        assertThrows(IllegalArgumentException.class, () ->
                RouteNavigationCot.read("<!ENTITY x SYSTEM 'https://example.invalid'>" + XML, "r"));
        assertThrows(IllegalArgumentException.class, () -> RouteNavigationCot.read("<bad", "r"));
    }
}
