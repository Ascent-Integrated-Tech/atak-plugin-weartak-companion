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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Single ATAK map subscription. Watch-owned UIDs are never exported from ATAK back to BLE. */
final class GatewayMapBridge {
    private static final int LIMIT = 128;
    private final MapView map;
    private final BiConsumer<String, String> publish;
    private final Consumer<String> error;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<PointMapItem, Boolean> observed = new LinkedHashMap<>();
    private final GatewayOrigins origins;
    private final Map<String, PointMapItem> pending = new LinkedHashMap<>();
    private boolean running;
    private boolean flushScheduled;
    private int deferred;
    private final PointMapItem.OnPointChangedListener point = this::changed;
    private final MapItem.OnMetadataChangedListener metadata = (item, key) -> changed(item);
    private final MapEventDispatcher.MapEventDispatchListener events = this::event;
    private final Runnable flush = this::flushPending;

    GatewayMapBridge(MapView map, BiConsumer<String, String> publish, Consumer<String> error) {
        this(map, publish, error, null);
    }
    GatewayMapBridge(MapView map, BiConsumer<String, String> publish,
                     Consumer<String> error, SharedPreferences originPreferences) {
        this.map = map; this.publish = publish; this.error = error;
        origins = new GatewayOrigins(originPreferences);
    }
    synchronized void start() {
        running = true;
        for (String type : new String[]{MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED, MapEvent.ITEM_REMOVED})
            map.getMapEventDispatcher().addMapEventListenerToBase(type, events);
        for (MapItem item : map.getRootGroup().getItemsRecursive()) observe(item);
    }
    synchronized void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        for (String type : new String[]{MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED, MapEvent.ITEM_REMOVED})
            map.getMapEventDispatcher().removeMapEventListenerFromBase(type, events);
        for (PointMapItem item : new ArrayList<>(observed.keySet())) detach(item);
        pending.clear(); origins.clear();
        deferred = 0;
        flushScheduled = false;
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
    synchronized boolean origin(String uid, String address) {
        if (map.getSelfMarker() != null && uid.equals(map.getSelfMarker().getUID()))
            throw new IllegalArgumentException("Watch UID conflicts with phone identity");
        origins.claim(uid, address);
        return true;
    }
    synchronized void snapshot() {
        for (MapItem item : map.getRootGroup().getItemsRecursive()) {
            if (item instanceof PointMapItem) changed(item);
        }
    }
    synchronized boolean hasOrigin(String uid) { return origins.contains(uid); }
    synchronized boolean allowsPhoneEvent(String uid) { return origins.allowsPhoneEvent(uid); }
    synchronized void forgetPeer(String address) { origins.forgetPeer(address); }
    private void observe(MapItem item) {
        if (!(item instanceof PointMapItem) || observed.containsKey(item)) return;
        PointMapItem p = (PointMapItem) item;
        observed.put(p, false);
        p.addOnPointChangedListener(point);
        MetadataObservers.add(p, metadata);
    }
    private void detach(PointMapItem item) {
        if (Boolean.TRUE.equals(observed.remove(item))) deferred--;
        item.removeOnPointChangedListener(point);
        MetadataObservers.remove(item, metadata);
    }
    private synchronized void event(MapEvent event) {
        if (!running || event == null) return;
        MapItem item = event.getItem();
        if (MapEvent.ITEM_REMOVED.equals(event.getType())) {
            if (item instanceof PointMapItem) detach((PointMapItem) item);
            if (item != null) pending.remove(item.getUID());
            return; // No destructive deletion protocol.
        }
        observe(item); changed(item);
    }
    private synchronized void changed(MapItem item) {
        if (!running || !eligible(item) || item.getGroup() == null) return;
        observe(item);
        PointMapItem p = (PointMapItem) item;
        if (!pending.containsKey(item.getUID()) && pending.size() >= LIMIT) {
            if (!observed.get(p)) {
                observed.put(p, true);
                if (deferred++ == 0)
                    error.accept("Gateway map update queue full (128); retrying current map state");
            }
        } else {
            if (observed.put(p, false)) deferred--;
            pending.put(item.getUID(), p);
        }
        scheduleFlush();
    }
    private boolean eligible(MapItem item) {
        return item instanceof PointMapItem && supportedType(item.getType())
                && !origins.contains(item.getUID()) && !item.hasMetaValue("nevercot");
    }
    private void scheduleFlush() {
        if (!running || flushScheduled || (pending.isEmpty() && deferred == 0)) return;
        flushScheduled = true;
        main.postDelayed(flush, 100);
    }
    private synchronized void flushPending() {
        flushScheduled = false;
        if (!running) return;
        ArrayList<PointMapItem> batch = new ArrayList<>(pending.values());
        pending.clear();
        recoverDeferred();
        for (PointMapItem item : batch) export(item);
        scheduleFlush();
    }
    private void recoverDeferred() {
        // Reuse the observer ledger's dirty bits, rotating a bounded scan for fairness.
        Map<PointMapItem, Boolean> scanned = new LinkedHashMap<>();
        Iterator<Map.Entry<PointMapItem, Boolean>> iterator = observed.entrySet().iterator();
        while (deferred > 0 && pending.size() < LIMIT && scanned.size() < LIMIT && iterator.hasNext()) {
            Map.Entry<PointMapItem, Boolean> entry = iterator.next();
            PointMapItem item = entry.getKey();
            if (entry.getValue()) {
                deferred--;
                if (item.getGroup() != null && eligible(item)) pending.put(item.getUID(), item);
            }
            scanned.put(item, false);
            iterator.remove();
        }
        observed.putAll(scanned);
    }
    private void export(PointMapItem item) {
        if (!running || item.getGroup() == null || !eligible(item)) return;
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
