package com.weartak.atak.weartak_companion.plugin;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;

/**
 * While a watch is bonded through Companion its identity is the phone's self marker, so any
 * separate track the same watch publishes over Ditto or TAK is removed from this phone's map.
 * Only the exact authorized watch UID is matched; its emergency UID and other devices are kept.
 */
final class PairedWatchTrackSuppressor {
    private static final String TAG = "WTK/PairedTrack";
    private static final String[] EVENTS = {MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED,
            MapEvent.ITEM_REFRESH};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final MapEventDispatcher.MapEventDispatchListener events = this::onEvent;
    private MapView map;
    private volatile String pairedUid;

    static boolean suppresses(String pairedUid, String itemUid, String selfUid) {
        return pairedUid != null && !pairedUid.isEmpty() && pairedUid.equals(itemUid)
                && !pairedUid.equals(selfUid);
    }

    synchronized void start(MapView view) {
        stop();
        if (view == null || view.getMapEventDispatcher() == null) return;
        map = view;
        for (String event : EVENTS) map.getMapEventDispatcher().addMapEventListenerToBase(event, events);
    }

    synchronized void stop() {
        if (map != null && map.getMapEventDispatcher() != null) {
            for (String event : EVENTS)
                map.getMapEventDispatcher().removeMapEventListenerFromBase(event, events);
        }
        map = null;
        pairedUid = null;
        main.removeCallbacksAndMessages(null);
    }

    /** Sets the authorized paired watch UID, or null when no watch is bonded and connected. */
    void setPairedWatch(String uid) {
        String next = uid == null || uid.isEmpty() ? null : uid;
        if (next == null ? pairedUid == null : next.equals(pairedUid)) return;
        pairedUid = next;
        if (next != null) {
            LogX.i(TAG, "Merging paired watch into phone marker: " + next);
            main.post(() -> removeIfPaired(next));
        }
    }

    private void onEvent(MapEvent event) {
        MapItem item = event.getItem();
        String uid = pairedUid;
        if (item == null || uid == null || !uid.equals(item.getUID())) return;
        main.post(() -> removeIfPaired(uid));
    }

    private void removeIfPaired(String uid) {
        MapView view;
        synchronized (this) {
            view = map;
        }
        if (view == null || !uid.equals(pairedUid)) return;
        MapItem self = view.getSelfMarker();
        MapItem item = view.getRootGroup().deepFindUID(uid);
        if (item == null || !suppresses(pairedUid, item.getUID(), self == null ? null : self.getUID())) return;
        if (item.removeFromGroup())
            LogX.i(TAG, "Removed duplicate track for paired watch " + uid);
    }
}
