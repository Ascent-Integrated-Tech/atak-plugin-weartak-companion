package com.weartak.atak.weartak_companion.plugin;

import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.cot.event.CotEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Single ATAK map subscription. Watch-owned UIDs are never exported from ATAK back to BLE. */
final class GatewayMapBridge {
    private final MapView map;
    private final BiConsumer<String, String> publish;
    private final Consumer<String> error;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<PointMapItem, Boolean> observed = new LinkedHashMap<>();
    private final GatewayOrigins origins;
    private final Map<String, PointMapItem> pending = new LinkedHashMap<>();
    private boolean running;
    private final PointMapItem.OnPointChangedListener point = this::changed;
    private final MapItem.OnMetadataChangedListener metadata = (item, key) -> changed(item);
    private final MapEventDispatcher.MapEventDispatchListener events = this::event;
    private final Runnable flush = () -> {
        for (PointMapItem item : new ArrayList<>(pending.values())) export(item);
        pending.clear();
    };

    GatewayMapBridge(MapView map, BiConsumer<String, String> publish, Consumer<String> error) {
        this(map, publish, error, null);
    }
    GatewayMapBridge(MapView map, BiConsumer<String, String> publish,
                     Consumer<String> error, SharedPreferences originPreferences) {
        this.map = map; this.publish = publish; this.error = error;
        origins = new GatewayOrigins(originPreferences);
    }
    void start() {
        running = true;
        for (String type : new String[]{MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED, MapEvent.ITEM_REMOVED})
            map.getMapEventDispatcher().addMapEventListenerToBase(type, events);
        for (MapItem item : map.getRootGroup().getItemsRecursive()) observe(item);
    }
    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        for (String type : new String[]{MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED, MapEvent.ITEM_REMOVED})
            map.getMapEventDispatcher().removeMapEventListenerFromBase(type, events);
        for (PointMapItem item : new ArrayList<>(observed.keySet())) detach(item);
        pending.clear(); origins.clear();
    }
    static boolean supportedType(String type) {
        return GatewayCot.supportedType(type) && !GatewayCot.isChatType(type);
    }
    static CotEvent validate(String xml) {
        CotEvent event = GatewayCot.validate(xml);
        if (!supportedType(event.getType()))
            throw new IllegalArgumentException("Unsupported gateway map/position CoT");
        return event;
    }
    boolean origin(String uid, String address) {
        if (map.getSelfMarker() != null && uid.equals(map.getSelfMarker().getUID()))
            throw new IllegalArgumentException("Watch UID conflicts with phone identity");
        origins.claim(uid, address);
        return true;
    }
    void snapshot() {
        for (MapItem item : map.getRootGroup().getItemsRecursive()) {
            if (item instanceof PointMapItem) changed(item);
        }
    }
    boolean hasOrigin(String uid) { return origins.contains(uid); }
    void forgetPeer(String address) { origins.forgetPeer(address); }
    private void observe(MapItem item) {
        if (!(item instanceof PointMapItem) || observed.containsKey(item)) return;
        PointMapItem p = (PointMapItem) item;
        observed.put(p, true);
        p.addOnPointChangedListener(point);
        MetadataObservers.add(p, metadata);
    }
    private void detach(PointMapItem item) {
        observed.remove(item);
        item.removeOnPointChangedListener(point);
        MetadataObservers.remove(item, metadata);
    }
    private void event(MapEvent event) {
        if (!running || event == null) return;
        MapItem item = event.getItem();
        if (MapEvent.ITEM_REMOVED.equals(event.getType())) {
            if (item instanceof PointMapItem) detach((PointMapItem) item);
            if (item != null) pending.remove(item.getUID());
            return; // No destructive deletion protocol.
        }
        observe(item); changed(item);
    }
    private void changed(MapItem item) {
        if (!running || !(item instanceof PointMapItem) || !supportedType(item.getType())
                || origins.contains(item.getUID()) || item.hasMetaValue("nevercot")) return;
        if (!pending.containsKey(item.getUID()) && pending.size() >= 128) {
            error.accept("Gateway map update queue full (128); rescan snapshot when capacity is available");
            return;
        }
        pending.put(item.getUID(), (PointMapItem) item);
        main.removeCallbacks(flush);
        main.postDelayed(flush, 100);
    }
    private void export(PointMapItem item) {
        if (!running || item.getGroup() == null || origins.contains(item.getUID())) return;
        try {
            CotEvent event = CotEventFactory.createCotEvent(item);
            if (event == null || !supportedType(event.getType())) return;
            StringBuilder xml = new StringBuilder();
            event.buildXml(xml);
            validate(xml.toString());
            publish.accept(event.getUID(), xml.toString());
        } catch (RuntimeException e) { error.accept("Map export error: " + e.getMessage()); }
    }
}
