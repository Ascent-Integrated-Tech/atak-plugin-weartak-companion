package com.weartak.atak.weartak_companion.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.chat.ChatDatabase;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** Emulator-only entry point for exercising the real ATAK import path without a BLE watch. */
final class SimulatorDiagnostics {
    private static final String TAG = "WTK/Simulator";
    private static final String ACTION = "com.weartak.companion.SIMULATE_WATCH_POINT";
    private static final String CHAT = "com.weartak.companion.SIMULATE_WATCH_CHAT";
    private static final String ALERT = "com.weartak.companion.SIMULATE_WATCH_ALERT";
    private static final String CANCEL = "com.weartak.companion.SIMULATE_WATCH_CANCEL";
    private static final String USERS = "com.weartak.companion.SIMULATE_PHONE_USERS";
    private static final String CHAT_ECHO_TEST = "com.weartak.companion.SIMULATE_CHAT_ECHO_TEST";
    private static final String RELEASE_TEST = "com.weartak.companion.SIMULATE_PHONE_MANAGEMENT_RELEASE";
    private static final String ROUTE_TEST = "com.weartak.companion.SIMULATE_GARMIN_ROUTES";
    private static final String UID = "SIMULATOR-WATCH-POINT";
    private Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private final PhoneUserRelayDiagnostics users = new PhoneUserRelayDiagnostics();
    private WatchChatEchoSuppressor chatEchoSuppressor;
    private WatchChatEchoPrototype echoPrototype;
    private BleCotBridge echoBridge;
    private boolean echoTestRunning;

    void start(MapView map, BleCotBridge bridge, WatchChatEchoSuppressor suppressor) {
        if (context != null || !SamsungWearablePreview.isEnabled(true, Build.HARDWARE, true)) return;
        context = map.getContext();
        chatEchoSuppressor = suppressor;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                String action = intent.getAction();
                if (!ACTION.equals(action) && !CHAT.equals(action)
                        && !ALERT.equals(action) && !CANCEL.equals(action)
                        && !USERS.equals(action) && !CHAT_ECHO_TEST.equals(action)
                        && !RELEASE_TEST.equals(action) && !ROUTE_TEST.equals(action)) return;
                if (Settings.Global.getInt(context.getContentResolver(), "wifi_on", 1) != 0
                        || Settings.Global.getInt(context.getContentResolver(), "mobile_data", 1) != 0) {
                    LogX.e(TAG, "Refusing synthetic traffic: disable emulator Wi-Fi and mobile data first");
                    return;
                }
                if (USERS.equals(action)) {
                    users.start(map);
                    return;
                }
                if (ROUTE_TEST.equals(action)) {
                    GarminRouteDiagnostics.run(map);
                    return;
                }
                if (CHAT_ECHO_TEST.equals(action)) {
                    echoStep(() -> runChatEchoTest(map, bridge));
                    return;
                }
                if (RELEASE_TEST.equals(action)) {
                    runPhoneManagementReleaseTest();
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
                    LogX.i(TAG, type + " injection returned to UI thread");
                    main.postDelayed(() -> {
                        if (ACTION.equals(action)) {
                            MapItem item = map.getRootGroup().deepFindUID(UID);
                            boolean correct = item instanceof PointMapItem
                                    && Math.abs(((PointMapItem) item).getPoint().getLatitude() - 38.5) < 0.000001
                                    && Math.abs(((PointMapItem) item).getPoint().getLongitude() + 77.2) < 0.000001;
                            LogX.i(TAG, correct
                                    ? "PASS: watch point preserved selected coordinates; UI responsive"
                                    : "FAIL: watch point missing or moved");
                        } else {
                            LogX.i(TAG, type + " UI heartbeat; inspect dispatch logs for outcome");
                        }
                    }, 2000);
                } catch (JSONException e) {
                    LogX.e(TAG, "Could not build simulated watch message", e);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        filter.addAction(CHAT);
        filter.addAction(ALERT);
        filter.addAction(CANCEL);
        filter.addAction(USERS);
        filter.addAction(CHAT_ECHO_TEST);
        filter.addAction(RELEASE_TEST);
        filter.addAction(ROUTE_TEST);
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        LogX.i(TAG, "Emulator point injection available");
    }

    private void runPhoneManagementReleaseTest() {
        PhoneManagementRelease release = new PhoneManagementRelease();
        Object peer = new Object();
        String refusedId = "12345678-1234-1234-1234-123456789abc";
        long refusedToken = release.begin(peer, refusedId, 0);
        checkRelease(release.acknowledge(new Object(), refusedId, refusedToken, 1, true)
                        == PhoneManagementRelease.Result.IGNORED && release.isPending(),
                "ack from another peer cannot release phone-managed settings");
        checkRelease(release.acknowledge(peer, refusedId, refusedToken, 2, false)
                        == PhoneManagementRelease.Result.REFUSED && !release.isPending(),
                "watch refusal ends the request without disconnecting");

        String staleId = "abcdefab-cdef-abcd-efab-cdefabcdefab";
        long staleToken = release.begin(peer, staleId, 3);
        release.cancel();
        checkRelease(release.acknowledge(peer, staleId, staleToken, 4, true)
                        == PhoneManagementRelease.Result.IGNORED,
                "ack after transient teardown is ignored");

        String successId = "fedcba98-7654-3210-fedc-ba9876543210";
        long successToken = release.begin(peer, successId, 5);
        boolean routeConnected = true;
        PhoneManagementRelease.Result result =
                release.acknowledge(peer, successId, successToken, 6, true);
        if (result == PhoneManagementRelease.Result.SUCCEEDED) routeConnected = false;
        checkRelease(result == PhoneManagementRelease.Result.SUCCEEDED && !routeConnected,
                "matching affirmative ack permits route disconnect");
        LogX.i(TAG, "Synthetic release acknowledgements only; no BLE watch is connected");
    }

    private void checkRelease(boolean passed, String description) {
        LogX.i(TAG, (passed ? "PASS: " : "FAIL: ") + description);
    }

    private void runChatEchoTest(MapView map, BleCotBridge bridge) {
        if (chatEchoSuppressor == null || !chatEchoSuppressor.isActive()) {
            LogX.e(TAG, "Chat echo experiment requires the production single-watch filter");
            return;
        }
        if (echoTestRunning) {
            LogX.w(TAG, "Chat echo test already running");
            return;
        }
        echoTestRunning = true;
        echoPrototype = new WatchChatEchoPrototype(chatEchoSuppressor);
        echoPrototype.start();
        echoBridge = bridge;
        bridge.setWatchChatSend(echoPrototype::send);
        int unreadBefore = ChatManagerMapComponent.getChatBroadcastContact().getUnreadCount();
        bridge.sendChat("{\"payload\":{\"roomUid\":\"All Chat Rooms\","
                + "\"msg\":\"SIMULATOR CHAT ECHO " + UUID.randomUUID() + "\"}}");
        CotEvent sent = echoPrototype.lastWatchEvent();
        String messageId = sent.findDetail("__chat").getAttribute("messageId");
        LogX.i(TAG, "Echo experiment: unread before=" + unreadBefore);
        CommsMapComponent.getInstance().sendCoTInternally(sent, new Bundle());
        CommsMapComponent.getInstance().sendCoTInternally(echoPrototype.lastWatchEvent(), new Bundle());
        main.postDelayed(() -> echoStep(() -> {
            int unreadAfter = ChatManagerMapComponent.getChatBroadcastContact().getUnreadCount();
            boolean persisted = false;
            for (Bundle line : ChatDatabase.getInstance(map.getContext()).getHistory("All Chat Rooms")) {
                if (messageId.equals(line.getString("messageId"))) persisted = true;
            }
            LogX.i(TAG, (unreadBefore == unreadAfter && persisted ? "PASS" : "FAIL")
                    + ": repeated watch echoes; unread=" + unreadAfter + "; sent history=" + persisted);
            CotEvent incoming = echoPrototype.lastWatchEvent();
            String incomingId = UUID.randomUUID().toString();
            String sender = "SIMULATOR-OTHER-TAK";
            CotDetail chat = incoming.findDetail("__chat");
            chat.setAttribute("messageId", incomingId);
            chat.setAttribute("senderCallsign", "SIMULATOR OTHER TAK");
            chat.getFirstChildByName(0, "chatgrp").setAttribute("uid0", sender);
            incoming.findDetail("remarks").setAttribute("source", "BAO.F.ATAK." + sender);
            incoming.setUID("GeoChat." + sender + ".All Chat Rooms." + incomingId);
            CommsMapComponent.getInstance().sendCoTInternally(incoming, new Bundle());
            main.postDelayed(() -> echoStep(() -> {
                int unreadReply = ChatManagerMapComponent.getChatBroadcastContact().getUnreadCount();
                LogX.i(TAG, (unreadReply == unreadAfter + 1 ? "PASS" : "FAIL")
                        + ": new incoming message; unread=" + unreadReply);
                ChatManagerMapComponent.getInstance().sendMessage(
                        "SIMULATOR ORDINARY PHONE SEND " + UUID.randomUUID(),
                        java.util.Collections.singletonList(
                                ChatManagerMapComponent.getChatBroadcastContact()));
                CommsMapComponent.getInstance().sendCoTInternally(
                        echoPrototype.lastOutgoingEvent(), new Bundle());
                main.postDelayed(() -> echoStep(() -> {
                    int unreadPhone = ChatManagerMapComponent.getChatBroadcastContact().getUnreadCount();
                    LogX.i(TAG, (unreadPhone == unreadReply + 1 ? "PASS" : "FAIL")
                            + ": ordinary phone send echo not filtered; unread=" + unreadPhone);
                    stopEchoPrototype();
                    chatEchoSuppressor.stop();
                    String stoppedId = UUID.randomUUID().toString();
                    CotEvent afterStop = CotEvent.parse(incoming.toString());
                    afterStop.findDetail("__chat").setAttribute("messageId", stoppedId);
                    afterStop.setUID("GeoChat." + sender + ".All Chat Rooms." + stoppedId);
                    CommsMapComponent.getInstance().sendCoTInternally(afterStop, new Bundle());
                    main.postDelayed(() -> echoStep(() -> {
                        int unreadRestored = ChatManagerMapComponent.getChatBroadcastContact().getUnreadCount();
                        LogX.i(TAG, (unreadRestored == unreadPhone + 1 ? "PASS" : "FAIL")
                                + ": suppressor stop restores ATAK receiver; unread=" + unreadRestored);
                        chatEchoSuppressor.start();
                        echoPrototype = new WatchChatEchoPrototype(chatEchoSuppressor);
                        echoPrototype.start();
                        echoBridge = bridge;
                        bridge.setWatchChatSend(echoPrototype::send);
                        bridge.sendChat("{\"payload\":{\"roomUid\":\"All Chat Rooms\","
                                + "\"msg\":\"SIMULATOR CHAT RESTART " + UUID.randomUUID() + "\"}}");
                        CotEvent restartedEcho = echoPrototype.lastWatchEvent();
                        int beforeRestartEcho =
                                ChatManagerMapComponent.getChatBroadcastContact().getUnreadCount();
                        CommsMapComponent.getInstance().sendCoTInternally(restartedEcho, new Bundle());
                        main.postDelayed(() -> echoStep(() -> {
                            int afterRestartEcho =
                                    ChatManagerMapComponent.getChatBroadcastContact().getUnreadCount();
                            LogX.i(TAG, (afterRestartEcho == beforeRestartEcho ? "PASS" : "FAIL")
                                    + ": suppressor restart filters a new watch echo; unread="
                                    + afterRestartEcho);
                            stopEchoPrototype();
                            echoTestRunning = false;
                        }), 1500);
                    }), 1500);
                }), 1500);
            }), 1500);
        }), 1500);
    }

    private void echoStep(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            LogX.e(TAG, "FAIL: chat echo experiment could not complete", e);
            stopEchoPrototype();
            echoTestRunning = false;
        }
    }

    private void stopEchoPrototype() {
        if (echoBridge != null) {
            if (chatEchoSuppressor != null && chatEchoSuppressor.isActive()) {
                echoBridge.setWatchChatSend(chatEchoSuppressor::send);
            } else {
                echoBridge.setWatchChatSend(Runnable::run);
            }
        }
        if (echoPrototype != null) echoPrototype.stop();
        echoPrototype = null;
        echoBridge = null;
    }

    void stop() {
        main.removeCallbacksAndMessages(null);
        users.stop();
        stopEchoPrototype();
        echoTestRunning = false;
        if (context != null) {
            context.unregisterReceiver(receiver);
            context = null;
            receiver = null;
        }
    }
}
