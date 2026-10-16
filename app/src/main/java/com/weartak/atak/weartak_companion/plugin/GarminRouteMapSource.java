package com.weartak.atak.weartak_companion.plugin;

import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Shape;
import com.atakmap.coremap.cot.event.CotEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Observes ATAK route CoT items and publishes normalized, bounded Garmin route records. */
final class GarminRouteMapSource implements GarminRouteForwarder.Source {
    private static final String[] ROUTE_METADATA = {
            "serverId", "serverUID", "serverUid", "routeName", "missionName", "senderUid"
    };
    private final MapView map;
    private final Set<MapItem> observed = new HashSet<>();
    private final Map<String, GarminRouteProtocol.Route> routes = new LinkedHashMap<>();
    private Consumer<GarminRouteProtocol.Route> changed;
    private Consumer<String> removed;
    private Consumer<String> rejected;
    private final GarminRouteSourceCache<MapItem> sourceCache = new GarminRouteSourceCache<>();
    private final MapEventDispatcher.MapEventDispatchListener events = this::onEvent;
    private final MapItem.OnMetadataChangedListener metadata = (item, key) -> refresh();
    private final MapItem.OnTypeChangedListener type = item -> refresh();
    private final PointMapItem.OnPointChangedListener point = item -> refresh();
    private final Shape.OnPointsChangedListener shapePoints = item -> refresh();

    GarminRouteMapSource(MapView map) { this.map = map; }

    @Override public synchronized void subscribe(Consumer<GarminRouteProtocol.Route> changed,
            Consumer<String> removed, Consumer<String> rejected) {
        if (this.changed != null) return;
        this.changed = changed;
        this.removed = removed;
        this.rejected = rejected;
        MapEventDispatcher dispatcher = map.getMapEventDispatcher();
        for (String event : new String[]{MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED,
                MapEvent.ITEM_REMOVED}) dispatcher.addMapEventListenerToBase(event, events);
        for (MapItem item : map.getRootGroup().getItemsRecursive()) observe(item);
        refresh();
    }

    @Override public synchronized void unsubscribe() {
        if (changed == null) return;
        MapEventDispatcher dispatcher = map.getMapEventDispatcher();
        for (String event : new String[]{MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED,
                MapEvent.ITEM_REMOVED}) dispatcher.removeMapEventListenerFromBase(event, events);
        for (MapItem item : new ArrayList<>(observed)) unobserve(item);
        changed = null;
        removed = null;
        rejected = null;
        routes.clear();
        sourceCache.clear();
    }

    @Override public synchronized List<GarminRouteProtocol.Route> snapshot() {
        return scan();
    }

    private void onEvent(MapEvent event) {
        if (event == null || event.getItem() == null) return;
        if (MapEvent.ITEM_REMOVED.equals(event.getType())) unobserveIfObserved(event.getItem());
        else observe(event.getItem());
        refresh();
    }

    private synchronized void observe(MapItem item) {
        if (changed == null || item == null || !observed.add(item)) return;
        MetadataObservers.add(item, metadata);
        for (String key : ROUTE_METADATA) item.addOnMetadataChangedListener(key, metadata);
        item.addOnTypeChangedListener(type);
        if (item instanceof PointMapItem) ((PointMapItem) item).addOnPointChangedListener(point);
        if (item instanceof Shape) ((Shape) item).addOnPointsChangedListener(shapePoints);
    }

    private synchronized void unobserveIfObserved(MapItem item) {
        if (observed.contains(item)) unobserve(item);
    }

    private synchronized void unobserve(MapItem item) {
        if (!observed.remove(item)) return;
        MetadataObservers.remove(item, metadata);
        for (String key : ROUTE_METADATA) item.removeOnMetadataChangedListener(key, metadata);
        item.removeOnTypeChangedListener(type);
        if (item instanceof PointMapItem) ((PointMapItem) item).removeOnPointChangedListener(point);
        if (item instanceof Shape) ((Shape) item).removeOnPointsChangedListener(shapePoints);
    }

    private synchronized void refresh() {
        if (changed == null) return;
        List<GarminRouteProtocol.Route> current = scan();
        Map<String, GarminRouteProtocol.Route> next = new LinkedHashMap<>();
        for (GarminRouteProtocol.Route route : current) next.put(route.key(), route);
        for (Map.Entry<String, GarminRouteProtocol.Route> previous : routes.entrySet()) {
            if (!next.containsKey(previous.getKey()) && removed != null) removed.accept(previous.getKey());
        }
        for (GarminRouteProtocol.Route route : current) {
            GarminRouteProtocol.Route previous = routes.get(route.key());
            if (previous == null || !previous.payload().equals(route.payload())) changed.accept(route);
        }
        routes.clear();
        routes.putAll(next);
    }

    private List<GarminRouteProtocol.Route> scan() {
        if (map == null || map.getRootGroup() == null) return Collections.emptyList();
        List<MapItem> items = new ArrayList<>();
        for (MapItem item : map.getRootGroup().getItemsRecursive()) {
            if (item == null || item.getType() == null || !item.getType().startsWith("b-m-r")) continue;
            items.add(item);
        }
        return sourceCache.scan(items, item -> {
            CotEvent event = CotEventFactory.createCotEvent(item);
            if (event == null) throw new IllegalArgumentException("Route cannot be exported");
            StringBuilder xml = new StringBuilder();
            event.buildXml(xml);
            Map<String, String> metadata = new HashMap<>();
            put(metadata, "serverId", first(item.getMetaString("serverId", ""),
                    item.getMetaString("serverUID", ""), item.getMetaString("serverUid", "")));
            put(metadata, "uid", item.getUID());
            put(metadata, "name", first(item.getMetaString("routeName", ""),
                    item.getMetaString("title", ""), item.getMetaString("callsign", "")));
            put(metadata, "missionName", item.getMetaString("missionName", ""));
            put(metadata, "senderUid", item.getMetaString("senderUid", ""));
            return GarminRouteProtocol.fromCotXml(xml.toString(), metadata, item.getUID());
        }, message -> {
            if (rejected != null) rejected.accept(message);
            android.util.Log.w("WTK/GarminRoute", "Omitting an invalid ATAK route");
        });
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return null;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.trim().isEmpty()) map.put(key, value.trim());
    }
}
