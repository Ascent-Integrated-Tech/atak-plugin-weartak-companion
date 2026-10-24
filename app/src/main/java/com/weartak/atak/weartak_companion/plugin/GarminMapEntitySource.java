package com.weartak.atak.weartak_companion.plugin;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.ContactUtil;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import com.atakmap.comms.NetConnectString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Owns only map observers; emergency ingress uses the plugin's existing broadcast/CoT listener. */
final class GarminMapEntitySource implements GarminEntityForwarder.Source {
    private final MapView map;
    private final BleCotBridge origin;
    private final ReceivedCotTiming sourceTiming;
    private final Set<MapItem> observed = new HashSet<>();
    private Consumer<Map<String, Object>> listener;
    private Consumer<String> removed;
    private final MapEventDispatcher.MapEventDispatchListener events = this::onEvent;
    private final MapItem.OnMetadataChangedListener metadata = (item, key) -> changed(item);
    private final MapItem.OnTypeChangedListener type = this::changed;
    private final PointMapItem.OnPointChangedListener point = this::changed;
    private final Shape.OnPointsChangedListener shape = this::changed;

    GarminMapEntitySource(MapView map, BleCotBridge origin, ReceivedCotTiming sourceTiming) {
        this.map = map;
        this.origin = origin;
        this.sourceTiming = sourceTiming;
    }

    @Override public synchronized void subscribe(Consumer<Map<String, Object>> listener, Consumer<String> removed) {
        if (this.listener != null) return;
        this.listener = listener;
        this.removed = removed;
        for (String event : new String[]{MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED,
                MapEvent.ITEM_REMOVED}) map.getMapEventDispatcher().addMapEventListenerToBase(event, events);
        for (MapItem item : map.getRootGroup().getItemsRecursive()) observe(item);
    }

    @Override public synchronized void unsubscribe() {
        if (listener == null) return;
        listener = null;
        removed = null;
        for (String event : new String[]{MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED,
                MapEvent.ITEM_REMOVED}) map.getMapEventDispatcher().removeMapEventListenerFromBase(event, events);
        for (MapItem item : new ArrayList<>(observed)) unobserve(item);
    }

    private synchronized void observe(MapItem item) {
        if (listener == null || item == null || !observed.add(item)) return;
        MetadataObservers.add(item, metadata);
        item.addOnTypeChangedListener(type);
        if (item instanceof PointMapItem) ((PointMapItem) item).addOnPointChangedListener(point);
        if (item instanceof Shape) ((Shape) item).addOnPointsChangedListener(shape);
    }

    private void unobserve(MapItem item) {
        observed.remove(item);
        MetadataObservers.remove(item, metadata);
        item.removeOnTypeChangedListener(type);
        if (item instanceof PointMapItem) ((PointMapItem) item).removeOnPointChangedListener(point);
        if (item instanceof Shape) ((Shape) item).removeOnPointsChangedListener(shape);
    }

    private void onEvent(MapEvent event) {
        synchronized (this) {
            if (listener == null || event == null || event.getItem() == null) return;
        }
        MapItem item = event.getItem();
        if (MapEvent.ITEM_REMOVED.equals(event.getType())) {
            Consumer<String> removal;
            synchronized (this) {
                unobserve(item);
                removal = removed;
            }
            if (removal != null) removal.accept(item.getUID());
            // The forwarder emits scoped removals only for published remote users.
            return;
        }
        observe(item);
        changed(item);
    }

    private void changed(MapItem item) {
        Consumer<Map<String, Object>> target;
        synchronized (this) {
            if (listener == null || !observed.contains(item)
                    || item.getGroup() == null) return;
            target = listener;
        }
        Map<String, Object> entity = normalize(item);
        if (entity != null) target.accept(entity);
        else {
            Consumer<String> removal;
            synchronized (this) { removal = removed; }
            if (removal != null) removal.accept(item.getUID());
        }
    }

    private Map<String, Object> normalize(MapItem item) {
        if (item.getUID() == null || item == map.getSelfMarker()
                || item.hasMetaValue("nevercot")) return null;
        try {
            CotEvent event = item instanceof Marker ? PhoneUserCot.export((Marker) item)
                    : CotEventFactory.createCotEvent(item);
            if (event == null || !item.getUID().equals(event.getUID())) return null;
            long updated = item.getMetaLong("lastUpdateTime", 0);
            long duration = item.getMetaLong("autoStaleDuration", 0);
            boolean user = GarminEntityAdapter.groundUserType(event.getType())
                    && item instanceof Marker && PhoneUserCot.isUser(item, event)
                    && event.findDetail("emergency") == null;
            boolean sourceTimed = !user && event.findDetail("emergency") == null
                    && !PhoneEmergencyRelay.isEmergencyType(event.getType());
            if (user && !GarminEntityAdapter.validUserLifetime(updated, duration)) {
                warning("Garmin user omitted: missing or invalid source lifetime for " + item.getUID());
                return null;
            }
            if (!sourceTimed && GarminEntityAdapter.validUserLifetime(updated, duration)) {
                event.setTime(new CoordinatedTime(updated));
                event.setStart(new CoordinatedTime(updated));
                event.setStale(new CoordinatedTime(updated + duration));
            }
            if (sourceTimed && !sourceTiming.apply(item, event)) return null;
            return normalize(event, item);
        } catch (RuntimeException e) {
            LogX.w("WTK/GarminEntity", "Unable to normalize map entity", e);
            return null;
        }
    }

    void cot(CotEvent event) {
        Consumer<Map<String, Object>> target;
        synchronized (this) { target = listener; }
        if (target == null || event == null || !GarminEntityAdapter.supportedType(event.getType())) return;
        try {
            Map<String, Object> entity = normalize(event, null);
            if (entity != null) target.accept(entity);
        } catch (RuntimeException e) {
            LogX.w("WTK/GarminEntity", "Unable to normalize CoT event", e);
        }
    }

    private Map<String, Object> normalize(CotEvent event, MapItem item) {
        String uid = event.getUID();
        boolean self = map.getSelfMarker() != null
                && uid != null && uid.equals(map.getSelfMarker().getUID());
        boolean watch = origin != null && (origin.isWatchOriginEmergency(uid)
                || origin.isGarminOriginMarker(uid));
        Map<String, Object> meta = new HashMap<>();
        CotDetail contact = event.findDetail("contact"), group = event.findDetail("__group");
        CotDetail emergency = event.findDetail("emergency"), link = event.findDetail("link");
        boolean user = GarminEntityAdapter.groundUserType(event.getType())
                && item instanceof Marker && PhoneUserCot.isUser(item, event)
                && !PhoneEmergencyRelay.isEmergencyType(event.getType()) && emergency == null;
        meta.put("isUser", user);
        if (user) {
            Map<String, Object> userContact = detail(contact, "callsign", "endpoint");
            if (!userContact.containsKey("endpoint")) {
                Contact known = Contacts.getInstance().getContactByUuid(uid);
                if (known instanceof IndividualContact) {
                    NetConnectString endpoint = ContactUtil.getIpAddress((IndividualContact) known);
                    if (endpoint != null) userContact.put("endpoint", endpoint.toString());
                }
            }
            meta.put("contact", userContact);
            meta.put("__group", detail(group, "name", "role"));
            meta.put("track", detail(event.findDetail("track"), "course", "speed"));
        }
        put(meta, "callSign", attribute(contact, "callsign"));
        put(meta, "team", attribute(group, "name"));
        put(meta, "role", attribute(group, "role"));
        put(meta, "senderUID", attribute(link, "uid"));
        meta.put("link", detail(link, "uid", "relation", "type"));
        CotDetail ellipse = event.findDetail("ellipse");
        if (ellipse == null) {
            CotDetail geometry = event.findDetail("shape");
            if (geometry != null) ellipse = geometry.getFirstChildByName(0, "ellipse");
        }
        if (ellipse != null) {
            Map<String, Object> axes = new HashMap<>();
            number(axes, "major", attribute(ellipse, "major"));
            number(axes, "minor", attribute(ellipse, "minor"));
            number(axes, "angle", attribute(ellipse, "angle"));
            meta.put("shape", Collections.singletonMap("ellipse", axes));
        }
        CotDetail radius = event.findDetail("radius");
        number(meta, "radius", attribute(radius, "value"));
        if (event.findDetail("__video") != null) meta.put("__video", true);
        put(meta, "category", attribute(emergency, "type"));
        put(meta, "how", event.getHow());
        if (event.getTime() != null) meta.put("time", event.getTime().toString());
        if (event.getStart() != null) meta.put("start", event.getStart().toString());
        if (event.getStale() != null) meta.put("stale", event.getStale().toString());
        if (emergency != null) {
            meta.put("isAlert", true);
            meta.put("cancel", "true".equalsIgnoreCase(attribute(emergency, "cancel")));
        }
        if (item != null) {
            boolean captured = !user && emergency == null
                    && !PhoneEmergencyRelay.isEmergencyType(event.getType())
                    && sourceTiming.source(item) != null;
            String revision = item.getMetaString("time", "");
            if (revision.isEmpty()) {
                long updated = item.getMetaLong("lastUpdateTime", 0);
                if (updated > 0) revision = java.time.Instant.ofEpochMilli(updated).toString();
            }
            // Exporters may synthesize new times; a map update's own revision wins.
            if (!revision.isEmpty() && !captured) meta.put("time", revision);
            String stale = item.getMetaString("stale", "");
            if (!stale.isEmpty() && !captured) meta.put("stale", stale);
            meta.put("state", item.getMetaString("state", ""));
            meta.put("alertState", item.getMetaString("alertState", ""));
            meta.put("active", item.getMetaBoolean("active", true));
            if (item.getMetaBoolean("emergency.cancel", false)) meta.put("cancel", true);
        }
        GeoPoint location = event.getGeoPoint();
        return GarminEntityAdapter.normalize(uid, location == null ? Double.NaN : location.getLatitude(),
                location == null ? Double.NaN : location.getLongitude(), event.getType(),
                meta, self && emergency == null && !PhoneEmergencyRelay.isEmergencyType(event.getType()), watch);
    }

    @Override public synchronized List<Map<String, Object>> snapshot(int limit) {
        if (map.getSelfMarker() == null) return Collections.emptyList();
        GeoPoint self = map.getSelfMarker().getPoint();
        if (self == null || !self.isValid()
                || GarminEntityAdapter.expired(map.getSelfMarker().getMetaString("stale", "")))
            return Collections.emptyList();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (MapItem item : map.getRootGroup().getItemsRecursive()) {
            Map<String, Object> entity = normalize(item);
            if (entity != null) candidates.add(entity);
        }
        return GarminEntityAdapter.nearestCandidates(candidates, self.getLatitude(), self.getLongitude(), limit);
    }

    @Override public List<String> excludedUids() {
        return map.getSelfMarker() == null ? Collections.emptyList()
                : Collections.singletonList(map.getSelfMarker().getUID());
    }

    @Override public void warning(String message) {
        LogX.w("WTK/GarminEntity", message);
    }

    private static Map<String, Object> detail(CotDetail detail, String... attributes) {
        Map<String, Object> result = new HashMap<>();
        for (String key : attributes) put(result, key, attribute(detail, key));
        return result;
    }

    private static String attribute(CotDetail detail, String key) {
        return detail == null ? null : detail.getAttribute(key);
    }
    private static void put(Map<String, Object> meta, String key, String value) {
        if (value != null && !value.trim().isEmpty()) meta.put(key, value);
    }
    private static void number(Map<String, Object> meta, String key, String value) {
        if (value == null || value.isEmpty()) return;
        try {
            double number = Double.parseDouble(value);
            if (Double.isFinite(number) && number >= 0) meta.put(key, number);
            else LogX.w("WTK/GarminEntity", "Invalid geometry " + key);
        } catch (NumberFormatException error) {
            LogX.w("WTK/GarminEntity", "Invalid geometry " + key, error);
        }
    }
}
