package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class GarminRouteSourceCacheTest {
    @Test public void invalidSameUidItemCannotPreserveDeletedRouteFromAnotherServer() {
        GarminRouteSourceCache<Item> cache = new GarminRouteSourceCache<>();
        Item first = new Item("server-a", "shared");
        Item second = new Item("server-b", "shared");
        List<String> errors = new ArrayList<>();
        assertEquals(2, cache.scan(Arrays.asList(first, second), Item::normalize, errors::add).size());
        first.invalid = true;

        List<GarminRouteProtocol.Route> routes =
                cache.scan(Collections.singletonList(first), Item::normalize, errors::add);

        assertEquals(1, routes.size());
        assertEquals("server-a", routes.get(0).serverId);
        assertEquals(1, errors.size());
        cache.scan(Collections.singletonList(first), Item::normalize, errors::add);
        assertEquals(1, errors.size());
        first.invalid = false;
        first.server = "server-c";
        assertEquals("server-c", cache.scan(Collections.singletonList(first),
                Item::normalize, errors::add).get(0).serverId);
        first.invalid = true;
        assertEquals("server-c", cache.scan(Collections.singletonList(first),
                Item::normalize, errors::add).get(0).serverId);
        assertEquals(2, errors.size());
    }

    @Test public void removedOrRecreatedItemsCannotInheritOldGeometry() {
        GarminRouteSourceCache<Item> cache = new GarminRouteSourceCache<>();
        Item first = new Item("s", "r");
        cache.scan(Collections.singletonList(first), Item::normalize, null);
        cache.scan(Collections.emptyList(), Item::normalize, null);
        first.invalid = true;
        assertTrue(cache.scan(Collections.singletonList(first), Item::normalize, null).isEmpty());
        Item replacement = new Item("s", "r");
        replacement.invalid = true;
        assertTrue(cache.scan(Collections.singletonList(replacement), Item::normalize, null).isEmpty());
    }

    @Test public void unsubscribeClearsLastValidStateAndRejectionDeduplication() {
        GarminRouteSourceCache<Item> cache = new GarminRouteSourceCache<>();
        Item item = new Item("s", "r");
        cache.scan(Collections.singletonList(item), Item::normalize, null);
        cache.clear();
        item.invalid = true;
        List<String> errors = new ArrayList<>();
        assertTrue(cache.scan(Collections.singletonList(item), Item::normalize, errors::add).isEmpty());
        assertEquals(1, errors.size());
    }

    private static final class Item {
        String server;
        final String uid;
        boolean invalid;

        Item(String server, String uid) { this.server = server; this.uid = uid; }

        GarminRouteProtocol.Route normalize() {
            if (invalid) throw new IllegalArgumentException("Invalid geometry");
            return GarminRouteProtocol.route(server, uid, uid, null, null, Arrays.asList(
                    new GarminRouteProtocol.Point(0, 0, null),
                    new GarminRouteProtocol.Point(1, 1, null)));
        }
    }
}
