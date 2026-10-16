package com.weartak.atak.weartak_companion.plugin;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Offline ATAK map-observer test; captures envelopes instead of sending to a watch. */
final class PhoneUserRelayDiagnostics {
    private static final String TAG = "WTK/UserRelayTest";
    private static final String REMOTE = "SIMULATOR-REMOTE-USER";
    private static final String WATCH = "SIMULATOR-PAIRED-WATCH";
    private static final String POINT = "SIMULATOR-DROPPED-POINT";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SessionTaskQueue imports = new SessionTaskQueue();
    private final List<JSONObject> messages = new ArrayList<>();
    private PhoneUserMapRelay relay;
    private MapView map;
    private long session = 1;

    void start(MapView view) {
        if (map != null) {
            Log.w(TAG, "User relay experiment already running");
            return;
        }
        map = view;
        imports.start();
        imports.submit(() -> {
            importUser(REMOTE);
            importUser(WATCH);
        });
        handler.postDelayed(() -> step(() -> {
            MapItem imported = map.getRootGroup().deepFindUID(REMOTE);
            if (imported == null) throw new IllegalStateException("Synthetic user import missing");
            Marker point = new Marker(new GeoPoint(38.4, -77.1), POINT);
            point.setType("a-f-G-U-C");
            point.setMetaString("callsign", POINT);
            map.getRootGroup().addItem(point);
            check(!PhoneUserCot.isUser(point, PhoneUserCot.export(point)),
                    "ordinary dropped point stays outside user relay");
            relay = new PhoneUserMapRelay(new PhoneUserMapRelay.Transport() {
                @Override public long readySession() { return session; }
                @Override public boolean idle() { return true; }
                @Override public boolean write(String json, long expected) {
                    if (expected != session) return false;
                    try {
                        JSONObject message = new JSONObject(json);
                        messages.add(message);
                        Log.i(TAG, "Captured " + message.getString("msg_type"));
                        return true;
                    } catch (JSONException e) {
                        throw new IllegalArgumentException("Invalid user relay envelope", e);
                    }
                }
            });
            relay.start(map);
            relay.ready(WATCH, session);
            handler.postDelayed(() -> step(() -> {
                check(messages.size() == 1 && cot(0).getUID().equals(REMOTE),
                        "initial user snapshot excludes phone and paired watch; envelopes=" + messages.size());
                check("Blue".equals(cot(0).findDetail("__group").getAttribute("name"))
                                && "Team Member".equals(cot(0).findDetail("__group").getAttribute("role"))
                                && cot(0).findDetail("track") != null,
                        "imported user team, role and track survive Marker export");
                ((PointMapItem) map.getRootGroup().deepFindUID(REMOTE)).setPoint(new GeoPoint(38.6, -77.3));
                handler.postDelayed(() -> step(() -> {
                    check(messages.size() == 2
                                    && Math.abs(cot(1).getCotPoint().getLat() - 38.6) < 0.000001,
                            "live position update forwarded");
                    map.getRootGroup().deepFindUID(REMOTE).removeFromGroup();
                    handler.postDelayed(() -> step(() -> {
                        check(messages.size() == 3
                                        && "phone_user_remove".equals(messages.get(2).getString("msg_type"))
                                        && REMOTE.equals(messages.get(2).getJSONObject("payload").getString("targetUid")),
                                "removal is companion-user-only, not marker deletion");
                        session = 2;
                        relay.disconnected();
                        relay.ready(WATCH, session);
                        handler.postDelayed(() -> step(() -> {
                            check(messages.size() == 3, "reconnect does not replay removed user");
                            stop();
                        }), 500);
                    }), 500);
                }), 500);
            }), 1000);
        }), 1500);
    }

    private void importUser(String uid) {
        String start = new CoordinatedTime().toString();
        String stale = new CoordinatedTime(System.currentTimeMillis() + 60_000).toString();
        CotEvent event = CotEvent.parse("<event version='2.0' uid='" + uid
                + "' type='a-f-G-U-C' how='m-g' time='" + start + "' start='" + start
                + "' stale='" + stale + "'><point lat='38.5' lon='-77.2' hae='0' ce='10' le='10'/>"
                + "<detail><contact callsign='" + uid + "' endpoint='*:-1:stcp'/>"
                + "<__group name='Blue' role='Team Member'/><track course='0' speed='0'/></detail></event>");
        CotMapComponent.getParallelInternalDispatcher().dispatch(event, new Bundle());
    }

    private CotEvent cot(int index) throws JSONException {
        JSONObject payload = messages.get(index).getJSONObject("payload");
        check(map.getSelfMarker().getUID().equals(payload.getString("phoneUid")), "phone identity attached");
        return CotEvent.parse(payload.getString("cot"));
    }

    private static void check(boolean success, String message) {
        if (!success) throw new IllegalStateException(message);
        Log.i(TAG, "PASS: " + message);
    }

    private interface Step { void run() throws JSONException; }
    private void step(Step action) {
        try {
            action.run();
        } catch (RuntimeException | JSONException e) {
            Log.e(TAG, "FAIL: user relay experiment", e);
            stop();
        }
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        imports.stop();
        if (relay != null) relay.stop();
        relay = null;
        if (map != null) {
            for (String uid : new String[] { REMOTE, WATCH, POINT }) {
                MapItem item = map.getRootGroup().deepFindUID(uid);
                if (item != null) item.removeFromGroup();
            }
        }
        messages.clear();
        map = null;
    }
}
