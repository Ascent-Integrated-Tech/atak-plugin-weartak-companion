package com.weartak.atak.weartak_companion.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Manually admitted, independently negotiated, bounded per-watch CoT gateway. */
final class MultiwatchGateway {
    private static final String TAG = "WTK/Gateway";
    private final Context context;
    private final BleCotBridge bridge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final GatewayPolicy policy = new GatewayPolicy();
    private final SharedPreferences prefs;
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Map<String, WearTakBleClient.DiscoveredDevice> discoveries = new LinkedHashMap<>();
    private final LinkedHashSet<String> chatOrigins = new LinkedHashSet<>();
    private WearTakBleClient scanner;
    private GatewayMapBridge maps;
    private LinearLayout panel;
    private long token, scanGeneration;
    private boolean scanning;
    private String status = "";
    private final Runnable pump = new Runnable() {
        @Override public void run() {
            if (!policy.current(token)) return;
            for (Peer peer : peers.values()) {
                if (!current(peer) || !peer.negotiated || peer.client == null
                        || !peer.client.gatewayTxIdle()) continue;
                boolean accepted = peer.fanout.drain(xml -> sendMap(peer, xml));
                if (!accepted) peer.error = "Map output blocked; retrying paced handoff";
            }
            main.postDelayed(this, 250);
        }
    };

    private static final class Peer {
        final WearTakBleClient.DiscoveredDevice device;
        WearTakBleClient client;
        long session = -1;
        long forwarded;
        String uid, callsign;
        String status = "NOT CONNECTED";
        String error;
        boolean paused;
        boolean negotiated;
        String protocolSession;
        long bleSession = -1;
        Runnable negotiationTimeout;
        final GatewayFanout fanout = new GatewayFanout();
        final GatewayNegotiation negotiation = new GatewayNegotiation();
        final Map<String, String> received = new LinkedHashMap<String, String>(512, 0.75f, true);
        String team, role;
        long delivered;
        Peer(WearTakBleClient.DiscoveredDevice device) { this.device = device; }
    }

    MultiwatchGateway(Context context, BleCotBridge bridge) {
        this.context = context;
        this.bridge = bridge;
        prefs = PluginPrefs.get(context, "wtk_gateway_v1");
    }

    void start() {
        token = policy.start();
        maps = new GatewayMapBridge(com.atakmap.android.maps.MapView.getMapView(),
                (uid, xml) -> fanout(uid, xml, null), this::error, prefs);
        maps.start();
        bridge.startGatewayChatForwarding(bundle -> onMain(() -> forwardPhoneChat(bundle)));
        main.postDelayed(pump, 250);
        scanner = new WearTakBleClient(context, null, "scanner");
        scanner.start();
        for (String address : new LinkedHashSet<>(prefs.getStringSet("admitted", new LinkedHashSet<>()))) {
            if (peers.size() >= GatewayPolicy.CAP) break;
            Peer peer = new Peer(new WearTakBleClient.DiscoveredDevice(
                    prefs.getString("name_" + address, "WearTAK"), address, 0));
            peer.uid = prefs.getString("uid_" + address, null);
            peer.callsign = prefs.getString("callsign_" + address, null);
            peer.team = prefs.getString("team_" + address, null);
            peer.role = prefs.getString("role_" + address, null);
            peers.put(address, peer);
            String ready = new AutoReconnectPreference(
                    PluginPrefs.get(context, GatewayPolicy.retryPreferences(address))).armedAddress();
            if (address.equals(ready)) activate(peer, false);
            else peer.paused = true;
        }
        render();
    }

    private void rememberChatOrigin(String origin) {
        chatOrigins.add(origin);
        while (chatOrigins.size() > 512)
            chatOrigins.remove(chatOrigins.iterator().next());
    }

    private void forwardPhoneChat(android.os.Bundle bundle) {
        if (!policy.current(token) || maps == null) return;
        try {
            GatewayCot.ChatRoute chat = GatewayCot.buildChat(bundle);
            String origin = chat.senderUid + "|" + chat.messageId;
            if (maps.hasOrigin(chat.uid) || chatOrigins.contains(origin)) return;
            rememberChatOrigin(origin);
            fanoutChat(chat, null);
        } catch (RuntimeException e) {
            error("Chat not forwarded: " + e.getMessage());
        }
    }

    private void fanoutChat(GatewayCot.ChatRoute chat, Peer source) {
        for (Peer peer : peers.values()) {
            if (peer == source || !peer.negotiated) continue;
            if (GatewayCot.chatAllowsRecipient(chat, peer.uid)) {
                if (!peer.fanout.offer(chat.uid, chat.xml)) {
                    peer.error = "Chat output queue full (128); message not queued";
                    Log.w(TAG, peer.device.address + ": " + peer.error);
                }
            }
        }
        render();
    }

    void stop() {
        policy.stop();
        bridge.stopGatewayChatForwarding();
        if (maps != null) maps.stop();
        maps = null;
        ++scanGeneration;
        scanning = false;
        if (scanner != null) scanner.stop();
        scanner = null;
        for (Peer peer : peers.values()) stopPeer(peer);
        peers.clear();
        discoveries.clear();
        chatOrigins.clear();
        main.removeCallbacksAndMessages(null);
        panel = null;
    }

    void bind(LinearLayout panel) { this.panel = panel; render(); }

    void forwardPhoneEmergency(String xml) {
        if (!policy.current(token) || maps == null) return;
        try {
            com.atakmap.coremap.cot.event.CotEvent event = GatewayCot.validate(xml);
            if (!GatewayCot.isEmergencyType(event.getType()))
                throw new IllegalArgumentException("Unsupported Gateway alert type: " + event.getType());
            if (!maps.allowsPhoneEvent(event.getUID())) return;
            fanout(event.getUID(), xml, null);
        } catch (RuntimeException e) {
            error("Alert not forwarded: " + e.getMessage());
        }
    }

    private void saveAdmissions() {
        SharedPreferences.Editor editor = prefs.edit().putStringSet("admitted",
                new LinkedHashSet<>(peers.keySet()));
        for (Peer peer : peers.values()) editor.putString("name_" + peer.device.address, peer.device.name);
        editor.apply();
    }

    private boolean current(Peer peer) {
        return policy.current(token, peer.device.address, peer.session)
                && peers.get(peer.device.address) == peer;
    }

    private void activate(Peer peer, boolean manual) {
        peer.session = policy.admit(peer.device.address, manual, !manual);
        if (peer.session < 0) { error("Watch admission rejected"); return; }
        peer.paused = false;
        peer.status = manual ? "Connecting…" : "Reconnecting bonded peer…";
        final long owner = token;
        final long session = peer.session;
        WearTakBleClient client = new WearTakBleClient(context, null, peer.device.address);
        peer.client = client;
        client.setStatusListener(connected -> onMain(() -> {
            if (!policy.current(owner, peer.device.address, session) || !current(peer)) return;
            peer.status = connected ? "CONNECTED" : "NOT CONNECTED";
            if (!connected) {
                peer.negotiated = false;
                peer.negotiation.clear();
                peer.protocolSession = null;
                peer.fanout.clear();
                peer.received.clear();
                if (peer.negotiationTimeout != null) main.removeCallbacks(peer.negotiationTimeout);
            } else peer.status = "Negotiating…";
            render();
        }));
        client.setJsonListener(new WearTakBleClient.JsonListener() {
            private boolean valid() {
                return policy.current(owner, peer.device.address, session) && current(peer)
                        && peer.client == client;
            }
            @Override public void onReady() { onMain(() -> {
                if (!valid()) return;
                peer.status = "Negotiating…";
                peer.error = null;
                peer.negotiated = false;
                peer.protocolSession = java.util.UUID.randomUUID().toString();
                peer.bleSession = client.getReadySession();
                client.setGatewayHealthSession(peer.protocolSession);
                peer.negotiation.begin(peer.protocolSession, peer.bleSession);
                final String handshake = peer.protocolSession;
                peer.negotiationTimeout = () -> {
                    if (!valid() || peer.negotiated || !handshake.equals(peer.protocolSession)) return;
                    pause(peer);
                    peer.error = "Unsupported watch: Gateway v1 handshake timed out (10s). Update WearTAK.";
                    Log.w(TAG, peer.error);
                    render();
                };
                main.postDelayed(peer.negotiationTimeout, 10000);
                try {
                    String hello = new JSONObject().put("msg_type", "gateway_hello")
                            .put("version", 1).put("session", handshake).toString();
                    if (!client.writeJsonLineToWatch(hello, peer.bleSession))
                        peer.error = "Gateway handshake could not be queued";
                } catch (org.json.JSONException e) {
                    peer.error = "Gateway handshake failed: " + e.getMessage();
                    Log.e(TAG, peer.error, e);
                }
                render();
            }); }
            @Override public void onJson(String json) {
                final long receiveSession = client.getReadySession();
                onMain(() -> {
                    if (!valid() || receiveSession < 0 || receiveSession != client.getReadySession()) return;
                    try {
                        JSONObject env = new JSONObject(json);
                        String type = env.optString("msgType");
                        if ("gateway_ready".equals(type)) {
                            if (!peer.negotiation.acknowledge(env, receiveSession))
                                throw new IllegalArgumentException("Unsupported/stale gateway acknowledgement");
                            String uid = env.getString("uid");
                            if (uid.trim().isEmpty()) throw new IllegalArgumentException("Watch identity missing");
                            for (Peer other : peers.values()) {
                                if (other != peer && uid.equals(other.uid))
                                    throw new IllegalArgumentException("Duplicate watch UID");
                            }
                            maps.origin(uid, peer.device.address);
                            peer.uid = uid;
                            peer.callsign = env.getString("callsign");
                            peer.team = env.optString("team", "");
                            peer.role = env.optString("role", "");
                            prefs.edit().putString("uid_" + peer.device.address, peer.uid)
                                    .putString("callsign_" + peer.device.address, peer.callsign)
                                    .putString("team_" + peer.device.address, peer.team)
                                    .putString("role_" + peer.device.address, peer.role).apply();
                            peer.negotiated = true;
                            peer.status = "CONNECTED";
                            main.removeCallbacks(peer.negotiationTimeout);
                            for (Peer other : peers.values()) {
                                if (other == peer || !other.negotiated) continue;
                                for (Map.Entry<String, String> previous : other.received.entrySet()) {
                                    if (GatewayReplay.allows(previous.getValue(), peer.uid,
                                            System.currentTimeMillis())
                                            && !peer.fanout.offer(previous.getKey(), previous.getValue()))
                                        peer.error = "Map snapshot queue full (128)";
                                }
                            }
                            maps.snapshot();
                        } else if ("gateway_error".equals(type)) {
                            if (peer.protocolSession != null && peer.protocolSession.equals(env.optString("session")))
                                throw new IllegalStateException(env.optString("error", "Watch rejected gateway data"));
                        } else if ("gateway_cot".equals(type)) {
                            if (!peer.negotiation.accepts(env.getString("session"), receiveSession))
                                throw new IllegalArgumentException("Unnegotiated/stale gateway data");
                            String cot = env.getString("cot");
                            com.atakmap.coremap.cot.event.CotEvent event = GatewayCot.validate(cot);
                            GatewayCot.validatePeer(cot, peer.uid);
                            GatewayCot.ChatRoute chat = GatewayCot.isChatType(event.getType())
                                    ? GatewayCot.readChat(cot) : null;
                            if (chat == null) maps.origin(event.getUID(), peer.device.address);
                            String previous = peer.received.get(event.getUID());
                            if (cot.equals(previous)) return;
                            if (previous != null && GatewayCot.isChatType(event.getType()))
                                throw new IllegalArgumentException("Conflicting duplicate chat message");
                            if (previous != null && event.getTime().getMilliseconds()
                                    < GatewayCot.validate(previous).getTime().getMilliseconds()) return;
                            if (chat != null) rememberChatOrigin(chat.senderUid + "|" + chat.messageId);
                            bridge.forwardGatewayCot(cot);
                            peer.received.put(event.getUID(), cot);
                            while (peer.received.size() > 512)
                                peer.received.remove(peer.received.keySet().iterator().next());
                            if (chat == null) fanout(event.getUID(), cot, peer);
                            else fanoutChat(chat, peer);
                            ++peer.forwarded;
                        }
                    } catch (Exception e) {
                        String message = "Gateway protocol error: " + e.getMessage();
                        Log.e(TAG, peer.device.address + " watch forward failed", e);
                        pause(peer);
                        peer.error = message;
                    }
                    render();
                });
            }
            @Override public void onPaired(String uid, String callsign) { }
            @Override public void onError(String message) { onMain(() -> {
                if (!valid()) return;
                peer.error = "BLE error: " + message;
                Log.w(TAG, peer.device.address + ": " + message);
                render();
            }); }
            @Override public void onTrustLost(String address, String reason) { onMain(() -> {
                if (!valid()) return;
                pause(peer);
                peer.error = "Trust lost: " + reason + ". Connect again to pair.";
                Log.w(TAG, peer.device.address + ": " + peer.error);
                render();
            }); }
        });
        client.setAutomaticReconnectAllowed(true);
        client.start();
        if (manual) client.connectToSelectedDevice(peer.device);
    }

    private void stopPeer(Peer peer) {
        if (peer.negotiationTimeout != null) main.removeCallbacks(peer.negotiationTimeout);
        peer.negotiated = false;
        peer.negotiation.clear();
        peer.protocolSession = null;
        peer.fanout.clear();
        if (peer.client == null) return;
        peer.client.setGatewayHealthSession(null);
        peer.client.setJsonListener(null);
        peer.client.setStatusListener(null);
        peer.client.stop();
        peer.client = null;
    }

    private void pause(Peer peer) {
        policy.remove(peer.device.address);
        peer.session = -1;
        if (peer.client != null) peer.client.disconnect();
        stopPeer(peer);
        peer.paused = true;
        peer.status = "NOT CONNECTED / paused";
    }

    private void remove(Peer peer) {
        pause(peer);
        peers.remove(peer.device.address);
        if (maps != null) maps.forgetPeer(peer.device.address);
        prefs.edit().remove("name_" + peer.device.address)
                .remove("uid_" + peer.device.address).remove("callsign_" + peer.device.address)
                .remove("team_" + peer.device.address).remove("role_" + peer.device.address).apply();
        PluginPrefs.get(context, GatewayPolicy.retryPreferences(peer.device.address)).edit().clear().apply();
        saveAdmissions();
        render();
    }

    private void scan() {
        if (scanner == null || scanning) return;
        discoveries.clear();
        scanning = true;
        status = "";
        final long owner = token, scan = ++scanGeneration;
        render();
        scanner.scanForDevices(new WearTakBleClient.ScanListener() {
            private boolean valid() { return policy.current(owner) && scan == scanGeneration; }
            @Override public void onDeviceFound(WearTakBleClient.DiscoveredDevice device) {
                onMain(() -> {
                    if (!valid()) return;
                    discoveries.put(device.address, device);
                    render();
                });
            }
            @Override public void onScanFinished(List<WearTakBleClient.DiscoveredDevice> devices) {
                onMain(() -> {
                    if (!valid()) return;
                    scanning = false;
                    status = discoveries.isEmpty() ? "No WearTAK watches found. Open WearTAK on the watch."
                            : "";
                    render();
                });
            }
            @Override public void onScanError(String message) { onMain(() -> {
                if (!valid()) return;
                scanning = false;
                error("Scan error: " + message);
            }); }
        });
    }

    private void select(WearTakBleClient.DiscoveredDevice device) {
        if (peers.containsKey(device.address)) return;
        if (peers.size() >= GatewayPolicy.CAP) { error("Nine-watch admission limit reached. Remove a watch first."); return; }
        if (!discoveries.containsKey(device.address)) { error("Scan and select this watch again."); return; }
        Peer peer = new Peer(device);
        peers.put(device.address, peer);
        activate(peer, true);
        saveAdmissions();
        render();
    }

    private void error(String message) { status = message; Log.w(TAG, message); render(); }
    private void fanout(String uid, String xml, Peer source) {
        for (Peer peer : peers.values()) {
            if (peer == source || !peer.negotiated || uid.equals(peer.uid)) continue;
            if (!peer.fanout.offer(uid, xml)) {
                peer.error = "Map output queue full (128); latest new entity not queued";
                Log.w(TAG, peer.device.address + ": " + peer.error);
            }
        }
        render();
    }
    private boolean sendMap(Peer peer, String xml) {
        if (!peer.negotiated || peer.bleSession != peer.client.getReadySession()) return false;
        try {
            String envelope = new JSONObject().put("msg_type", "gateway_cot")
                    .put("session", peer.protocolSession).put("cot", xml).toString();
            if (!peer.client.writeJsonLineToWatch(envelope, peer.bleSession)) return false;
            ++peer.delivered;
            render();
            return true;
        } catch (org.json.JSONException e) {
            peer.error = "Map envelope failed: " + e.getMessage();
            Log.e(TAG, peer.error, e);
            return false;
        }
    }
    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }
    private void text(LinearLayout root, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(android.graphics.Color.WHITE);
        view.setPadding(8, 8, 8, 8);
        root.addView(view);
    }
    private Button button(LinearLayout root, String label, Runnable action) {
        Button view = new Button(context);
        view.setText(label);
        view.setBackgroundResource(R.drawable.btn_gray);
        view.setTextColor(android.graphics.Color.WHITE);
        view.setOnClickListener(v -> action.run());
        root.addView(view);
        return view;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void render() {
        if (panel == null) return;
        panel.removeAllViews();
        if (!status.isEmpty()) text(panel, status);
        button(panel, scanning ? "Scanning…" : "Scan", this::scan).setEnabled(!scanning);
        List<Peer> orderedPeers = new ArrayList<>(peers.values());
        orderedPeers.sort((left, right) -> Boolean.compare(right.negotiated, left.negotiated));
        for (Peer peer : orderedPeers) {
            text(panel, (scanner == null ? peer.device : scanner.withSessionIdentity(peer.device)).primaryName()
                    + " • " + peer.device.address + "\n"
                    + (peer.callsign == null ? "" : peer.callsign + " • " + peer.uid
                    + (peer.team == null || peer.team.isEmpty() ? "" : " • " + peer.team)
                    + (peer.role == null || peer.role.isEmpty() ? "" : " / " + peer.role) + "\n")
                    + peer.forwarded + " received • " + peer.delivered + " queued to watch"
                    + (peer.error == null ? "" : "\n" + peer.error));
            LinearLayout actions = new LinearLayout(context);
            actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
            panel.addView(actions);
            TextView badge = new TextView(context);
            badge.setText(peer.paused ? "NOT CONNECTED" : peer.status);
            badge.setGravity(android.view.Gravity.CENTER_VERTICAL);
            badge.setPadding(dp(8), 0, dp(8), 0);
            badge.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1));
            WearTakPlugin.styleConnectionStatus(badge);
            actions.addView(badge);
            Button connect = button(actions, peer.paused ? "Connect" : "Disconnect", () -> {
                if (peer.paused) activate(peer, true); else pause(peer);
                render();
            });
            LinearLayout.LayoutParams connectSize = new LinearLayout.LayoutParams(dp(112), dp(48));
            connectSize.setMargins(dp(8), 0, dp(8), 0);
            connect.setLayoutParams(connectSize);
            button(actions, "Remove", () -> remove(peer))
                    .setLayoutParams(new LinearLayout.LayoutParams(dp(80), dp(48)));
        }
        for (WearTakBleClient.DiscoveredDevice device : discoveries.values()) {
            if (peers.containsKey(device.address)) continue;
            button(panel, "Connect " + device.primaryName() + "\n" + device.address, () -> select(device))
                    .setEnabled(peers.size() < GatewayPolicy.CAP);
        }
    }
}
