package com.weartak.atak.weartak_companion.plugin;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class PhoneUserMapRelay {
    interface Transport {
        long readySession();
        boolean idle();
        boolean write(String envelope, long session);
    }
    private static final String TAG = "WTK/UserRelay";
    private static final String[] EVENTS = { MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED,
            MapEvent.ITEM_REFRESH, MapEvent.ITEM_PERSIST, MapEvent.ITEM_REMOVED };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Transport transport;
    private final PhoneUserRelay policy = new PhoneUserRelay();
    private final Set<Marker> observed = new LinkedHashSet<>();
    private final Set<MapItem> dirty = new LinkedHashSet<>();
    private final Map<String, PhoneUserRelay.User> pending = new LinkedHashMap<>();
    private final PointMapItem.OnPointChangedListener points = this::changed;
    private final MapItem.OnMetadataChangedListener metadata = (item, key) -> changed(item);
    private final MapItem.OnTypeChangedListener types = this::changed;
    private final Marker.OnTrackChangedListener tracks = this::changed;
    private final com.atakmap.android.maps.MapEventDispatcher.MapEventDispatchListener events = this::onEvent;
    private final Runnable pump = this::pump;
    private MapView map;
    private long session = -1;
    private String watchUid;
    private boolean capacityReported;

    PhoneUserMapRelay(WearTakBleClient client) {
        this(new Transport() {
            @Override public long readySession() { return client.getReadySession(); }
            @Override public boolean idle() { return client.gatewayTxIdle(); }
            @Override public boolean write(String envelope, long session) {
                return client.writeJsonLineToWatch(envelope, session);
            }
        });
    }

    PhoneUserMapRelay(Transport transport) {
        this.transport = transport;
    }

    void start(MapView view) {
        stop();
        map = view;
        for (String event : EVENTS) view.getMapEventDispatcher().addMapEventListenerToBase(event, events);
        for (MapItem item : view.getRootGroup().getItemsRecursive()) observe(item);
        handler.post(pump);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        if (map != null) {
            for (String event : EVENTS) map.getMapEventDispatcher().removeMapEventListenerFromBase(event, events);
        }
        for (Marker marker : new ArrayList<>(observed)) unobserve(marker);
        pending.clear();
        synchronized (dirty) { dirty.clear(); }
        session = -1;
        watchUid = null;
        policy.reset(-1);
        map = null;
    }

    void ready(String uid, long readySession) {
        if (map == null || readySession < 0 || readySession != transport.readySession()
                || uid == null || uid.trim().isEmpty()) return;
        if (session == readySession && uid.equals(watchUid)) return;
        pending.clear();
        session = readySession;
        watchUid = uid;
        capacityReported = false;
        policy.reset(session);
        for (Marker marker : new ArrayList<>(observed)) changed(marker);
    }

    void disconnected() {
        session = -1;
        watchUid = null;
        pending.clear();
        policy.reset(-1);
    }

    private void observe(MapItem item) {
        if (!(item instanceof Marker) || !observed.add((Marker) item)) return;
        Marker marker = (Marker) item;
        marker.addOnPointChangedListener(points);
        MetadataObservers.add(marker, metadata);
        marker.addOnTypeChangedListener(types);
        marker.addOnTrackChangedListener(tracks);
    }

    private void unobserve(Marker marker) {
        observed.remove(marker);
        marker.removeOnPointChangedListener(points);
        MetadataObservers.remove(marker, metadata);
        marker.removeOnTypeChangedListener(types);
        marker.removeOnTrackChangedListener(tracks);
    }

    private void onEvent(MapEvent event) {
        if (map == null || event == null || !(event.getItem() instanceof Marker)) return;
        Marker marker = (Marker) event.getItem();
        handler.post(() -> {
            if (map == null) return;
            if (MapEvent.ITEM_REMOVED.equals(event.getType())) {
                unobserve(marker);
                queue(marker.getUID(), null);
            } else {
                observe(marker);
                changed(marker);
            }
        });
    }

    private void changed(MapItem item) {
        // ATAK may deliver point mutations off the UI thread.
        synchronized (dirty) {
            if (!dirty.add(item)) return;
        }
        handler.post(() -> {
            synchronized (dirty) { dirty.remove(item); }
            if (map == null || session < 0 || session != transport.readySession()
                    || !(item instanceof Marker) || !observed.contains(item)) return;
            try {
                queue(item.getUID(), snapshot((Marker) item));
            } catch (RuntimeException e) {
                LogX.e(TAG, "Could not export live ATAK user", e);
            }
        });
    }

    private void queue(String uid, PhoneUserRelay.User user) {
        if (session < 0) return;
        if (user == null && !policy.contains(uid) && !pending.containsKey(uid)) return;
        if (!pending.containsKey(uid) && pending.size() >= PhoneUserRelay.CAPACITY) {
            reportCapacity();
            return;
        }
        pending.put(uid, user);
    }

    private PhoneUserRelay.User snapshot(Marker marker) {
        if (marker.getGroup() == null || marker.getType() == null
                || !marker.getType().startsWith("a-")
                || marker.getUID().equals(phoneUid()) || marker.getUID().equals(watchUid)) return null;
        CotEvent event = PhoneUserCot.export(marker);
        if (event == null || event.getCotPoint() == null || !marker.getUID().equals(event.getUID())) return null;
        boolean user = PhoneUserCot.isUser(marker, event);
        if (!user) return null;
        long updated = marker.getMetaLong("lastUpdateTime", 0);
        long duration = marker.getMetaLong("autoStaleDuration", 0);
        if (updated <= 0 || duration <= 0 || updated > Long.MAX_VALUE - duration) {
            LogX.w(TAG, "User not relayed: no valid source lifetime uid=" + marker.getUID());
            return null;
        }
        event.setTime(new CoordinatedTime(updated));
        event.setStart(new CoordinatedTime(updated));
        event.setStale(new CoordinatedTime(updated + duration));
        boolean excluded = marker.hasMetaValue("nevercot") || marker.hasMetaValue("emergency")
                || event.findDetail("emergency") != null;
        long stale = event.getStale() == null ? 0 : event.getStale().getMilliseconds();
        if (!PhoneUserRelay.eligible(event.getUID(), phoneUid(), watchUid, event.getType(),
                user, excluded, event.getCotPoint().getLat(), event.getCotPoint().getLon(),
                stale, System.currentTimeMillis())) return null;
        String xml = event.toString();
        if (xml.length() > 64 * 1024) {
            throw new IllegalArgumentException("Live user CoT exceeds 64 KiB character limit");
        }
        return new PhoneUserRelay.User(event.getUID(), xml, stale);
    }

    private String phoneUid() {
        return map.getSelfMarker().getUID();
    }

    private void pump() {
        if (map == null) return;
        try {
            if (session >= 0 && session != transport.readySession()) disconnected();
            if (session >= 0 && transport.idle()) {
                // One complete envelope at a time keeps snapshots out of the GATT chunk backlog.
                if (!pending.isEmpty()) {
                    String uid = pending.keySet().iterator().next();
                    PhoneUserRelay.User user = pending.get(uid);
                    boolean accepted = user == null
                            ? policy.remove(uid, phoneUid(), session, transport::write)
                            : policy.forward(user, phoneUid(), session, transport.readySession(),
                                    System.currentTimeMillis(), transport::write);
                    if (accepted) pending.remove(uid);
                } else {
                    policy.expire(phoneUid(), session, System.currentTimeMillis(), transport::write);
                }
            }
        } catch (IllegalStateException e) {
            if (!pending.isEmpty()) pending.remove(pending.keySet().iterator().next());
            reportCapacity();
            LogX.e(TAG, "Live user relay capacity exceeded", e);
        } catch (JSONException e) {
            LogX.e(TAG, "Could not encode live user relay envelope", e);
        } finally {
            if (map != null) handler.postDelayed(pump, 100);
        }
    }

    private void reportCapacity() {
        if (capacityReported) return;
        capacityReported = true;
        LogX.e(TAG, "Live user relay limited to 512 contacts; additional contacts were not sent");
        Toast.makeText(map.getContext(), "Watch user relay full (512 contacts). Some users were not sent.",
                Toast.LENGTH_LONG).show();
    }
}
