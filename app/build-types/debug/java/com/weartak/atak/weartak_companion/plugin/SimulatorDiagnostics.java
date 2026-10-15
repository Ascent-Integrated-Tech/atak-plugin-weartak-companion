package com.weartak.atak.weartak_companion.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.PointMapItem;

import org.json.JSONException;
import org.json.JSONObject;

/** Emulator-only entry point for exercising the real ATAK import path without a BLE watch. */
final class SimulatorDiagnostics {
    private static final String TAG = "WTK/Simulator";
    private static final String ACTION = "com.weartak.companion.SIMULATE_WATCH_POINT";
    private static final String CHAT = "com.weartak.companion.SIMULATE_WATCH_CHAT";
    private static final String ALERT = "com.weartak.companion.SIMULATE_WATCH_ALERT";
    private static final String CANCEL = "com.weartak.companion.SIMULATE_WATCH_CANCEL";
    private static final String UID = "SIMULATOR-WATCH-POINT";
    private Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;

    void start(MapView map, BleCotBridge bridge) {
        if (context != null || !SamsungWearablePreview.isEnabled(true, Build.HARDWARE, true)) return;
        context = map.getContext();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                String action = intent.getAction();
                if (!ACTION.equals(action) && !CHAT.equals(action)
                        && !ALERT.equals(action) && !CANCEL.equals(action)) return;
                if (Settings.Global.getInt(context.getContentResolver(), "wifi_on", 1) != 0
                        || Settings.Global.getInt(context.getContentResolver(), "mobile_data", 1) != 0) {
                    Log.e(TAG, "Refusing synthetic traffic: disable emulator Wi-Fi and mobile data first");
                    return;
                }
                try {
                    JSONObject payload = new JSONObject();
                    String type;
                    if (CHAT.equals(action)) {
                        type = "chat";
                        payload.put("roomUid", "All Chat Rooms")
                                .put("msg", "SIMULATOR OFFLINE CHAT TEST");
                    } else if (ALERT.equals(action) || CANCEL.equals(action)) {
                        type = "emergency";
                        payload.put("uid", "SIMULATOR-WATCH-ALERT")
                                .put("state", ALERT.equals(action) ? "ALERT" : "CANCEL")
                                .put("desc", "911 Alert").put("catg", "SIMULATOR OFFLINE TEST")
                                .put("callsign", "SIMULATOR OFFLINE TEST");
                    } else {
                        type = "marker";
                        payload.put("uid", UID).put("type", "a-f-G")
                                .put("title", "SIMULATOR & \"POINT\"")
                                .put("lat", 38.5).put("lon", -77.2);
                    }
                    JSONObject envelope = new JSONObject().put("msgType", type).put("payload", payload);
                    bridge.handleJsonFromWearTak(envelope.toString());
                    Log.i(TAG, type + " injection returned to UI thread");
                    main.postDelayed(() -> {
                        if (ACTION.equals(action)) {
                            MapItem item = map.getRootGroup().deepFindUID(UID);
                            boolean correct = item instanceof PointMapItem
                                    && Math.abs(((PointMapItem) item).getPoint().getLatitude() - 38.5) < 0.000001
                                    && Math.abs(((PointMapItem) item).getPoint().getLongitude() + 77.2) < 0.000001;
                            Log.i(TAG, correct
                                    ? "PASS: watch point preserved selected coordinates; UI responsive"
                                    : "FAIL: watch point missing or moved");
                        } else {
                            Log.i(TAG, type + " UI heartbeat; inspect dispatch logs for outcome");
                        }
                    }, 2000);
                } catch (JSONException e) {
                    Log.e(TAG, "Could not build simulated watch message", e);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        filter.addAction(CHAT);
        filter.addAction(ALERT);
        filter.addAction(CANCEL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        Log.i(TAG, "Emulator point injection available");
    }

    void stop() {
        main.removeCallbacksAndMessages(null);
        if (context != null) {
            context.unregisterReceiver(receiver);
            context = null;
            receiver = null;
        }
    }
}
