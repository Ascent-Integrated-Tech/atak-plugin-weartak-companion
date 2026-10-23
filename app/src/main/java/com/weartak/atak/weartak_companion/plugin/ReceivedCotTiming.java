package com.weartak.atak.weartak_companion.plugin;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CommsLogger;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.text.ParseException;

/** Observes network ingress without replacing or consuming ATAK's import processing. */
final class ReceivedCotTiming implements CommsLogger {
    static final String META = "weartak.sourceCotTiming";
    private static final String TAG = "WTK/SourceTiming";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SourceCotTiming.Cache received = new SourceCotTiming.Cache();
    private final PhoneUserRelay.LifetimeWarnings warnings = new PhoneUserRelay.LifetimeWarnings();
    private final MapEventDispatcher.MapEventDispatchListener events = event -> {
        if (event.getItem() != null) {
            if (MapEvent.ITEM_REMOVED.equals(event.getType())) {
                synchronized (ReceivedCotTiming.this) {
                    received.remove(event.getItem().getUID());
                    warnings.remove(event.getItem().getUID());
                }
            } else retain(event.getItem());
        }
    };
    private MapView map;
    private CommsMapComponent comms;

    synchronized void start(MapView view) {
        stop();
        map = view;
        comms = CommsMapComponent.getInstance();
        if (map == null || comms == null) throw new IllegalStateException("ATAK communications unavailable");
        map.getMapEventDispatcher().addMapEventListenerToBase(MapEvent.ITEM_REMOVED, events);
        map.getMapEventDispatcher().addMapEventListenerToBase(MapEvent.ITEM_IMPORTED, events);
        comms.registerCommsLogger(this);
    }

    synchronized void stop() {
        if (comms != null) comms.unregisterCommsLogger(this);
        if (map != null) map.getMapEventDispatcher()
                .removeMapEventListenerFromBase(MapEvent.ITEM_REMOVED, events);
        if (map != null) map.getMapEventDispatcher()
                .removeMapEventListenerFromBase(MapEvent.ITEM_IMPORTED, events);
        comms = null;
        map = null;
        handler.removeCallbacksAndMessages(null);
        received.clear();
        warnings.clear();
    }

    @Override public void dispose() { stop(); }
    @Override public void logSend(CotEvent event, String destination) {}
    @Override public void logSend(CotEvent event, String[] destinations) {}

    @Override public synchronized void logReceive(CotEvent event, String source, String server) {
        if (map == null || event == null || event.getUID() == null
                || !GarminEntityAdapter.supportedType(event.getType())) return;
        final SourceCotTiming timing;
        try {
            timing = new SourceCotTiming(milliseconds(event.getTime()), milliseconds(event.getStart()),
                    milliseconds(event.getStale()));
        } catch (IllegalArgumentException e) {
            warn(event.getUID(), "Invalid received CoT timing");
            return;
        }
        String uid = event.getUID();
        received.put(uid, timing);
        warnings.remove(uid);
        MapView owner = map;
        handler.post(() -> {
            MapItem item;
            synchronized (ReceivedCotTiming.this) {
                if (map != owner || received.get(uid) != timing) return;
                item = owner.getRootGroup().deepFindUID(uid);
            }
            if (item != null) retain(item);
        });
    }

    private void retain(MapItem item) {
        SourceCotTiming timing = received.get(item.getUID());
        if (timing != null && !timing.encode().equals(item.getMetaString(META, ""))) {
            item.setMetaString(META, timing.encode());
        }
    }

    synchronized SourceCotTiming source(MapItem item) {
        SourceCotTiming timing = received.get(item.getUID());
        if (timing != null) return timing;
        String saved = item.getMetaString(META, "");
        try {
            if (!saved.isEmpty()) return SourceCotTiming.decode(saved);
            String stale = item.getMetaString("stale", "");
            if (!stale.isEmpty()) {
                String time = item.getMetaString("time", "");
                String start = item.getMetaString("start", "");
                long updated = time.isEmpty() ? item.getMetaLong("lastUpdateTime", 0)
                        : CoordinatedTime.fromCot(time).getMilliseconds();
                return new SourceCotTiming(updated,
                        start.isEmpty() ? updated : CoordinatedTime.fromCot(start).getMilliseconds(),
                        CoordinatedTime.fromCot(stale).getMilliseconds());
            }
        } catch (ParseException | IllegalArgumentException e) {
            warn(item.getUID(), "Invalid retained CoT timing");
            return null;
        }
        return null;
    }

    synchronized boolean apply(MapItem item, CotEvent event) {
        return apply(item, event, source(item));
    }

    synchronized boolean apply(MapItem item, CotEvent event, SourceCotTiming timing) {
        boolean local = ((item.getMetaBoolean("archive", false)
                && !"CoT".equals(item.getMetaString("entry", "")))
                || item.getMetaBoolean(PhoneMarkerMapRelay.WATCH_IMPORT, false))
                && !item.hasMetaValue(META) && item.getMetaString("stale", "").isEmpty();
        SourceCotTiming exported = new SourceCotTiming(milliseconds(event.getTime()),
                milliseconds(event.getStart()), milliseconds(event.getStale()));
        SourceCotTiming selected = SourceCotTiming.select(timing, exported, local);
        // Network imports can race the receive callback. Never send a synthesized year
        // while waiting for it, or for a track's next source update after plugin startup.
        if (selected == null) {
            warn(item.getUID(), "Source expiry unavailable; deferring map export");
            return false;
        }
        event.setTime(new CoordinatedTime(selected.time));
        event.setStart(new CoordinatedTime(selected.start));
        event.setStale(new CoordinatedTime(selected.stale));
        return true;
    }

    private void warn(String uid, String message) {
        if (warnings.shouldReport(uid, android.os.SystemClock.elapsedRealtime())) {
            LogX.w(TAG, message + " uid=" + uid);
        }
    }

    private static long milliseconds(CoordinatedTime time) {
        return time == null ? 0 : time.getMilliseconds();
    }
}
