package com.weartak.atak.weartak_companion.plugin;

import android.content.Context;

import com.atakmap.android.maps.MapView;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GarminConnectIqClient {

    private static final String TAG = "WTK/GarminCIQ";
    private static final String WATCH_APP_ID = "5721f67e-bcc4-47e8-b337-2ad96ee77c0a";
    private static final String WATCH_RELAY_INSTRUCTIONS =
            "On WearTAK-Garmin, select Settings, select Network Preferences, then toggle on ATAK Relay.";

    public interface Listener {
        void onStatusChanged(String status);
        void onConnectionChanged(boolean connected);
        void onMessageReceived(JSONObject envelope);
        default void onMessageReceived(JSONObject envelope, GarminEntityForwarder.Link link) {
            onMessageReceived(envelope);
        }
    }

    private static final class DeviceApp {
        final IQDevice device;
        IQApp app;
        boolean appEventsRegistered;
        boolean appInfoPending;
        volatile boolean connected;
        volatile int routeVersion;
        volatile int routeNavigationVersion;
        GarminEntityForwarder.Link entityLink;
        volatile String relaySession = "";
        volatile int entitySyncVersion = 1;
        volatile String watchUid = "";
        String standaloneCallsign;
        long linkGeneration;
        DeviceApp(IQDevice device) {
            this.device = device;
        }
    }

    private final Context appContext;
    private final Listener listener;
    private final Object lock = new Object();
    private final Map<Long, DeviceApp> devices = new HashMap<>();

    private ConnectIQ connectIQ;
    private boolean started;
    private boolean sdkReady;
    private boolean initializing;
    private long generation;
    private String status = "Garmin Connect IQ is off.";

    public GarminConnectIqClient(Context context, Listener listener) {
        this(resolveHostContext(context), listener, null);
    }

    GarminConnectIqClient(Context hostContext, Listener listener, ConnectIQ sdk) {
        this.appContext = hostContext;
        this.listener = listener;
        this.connectIQ = sdk != null ? sdk
                : ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS);
    }

    // ATAK plugin contexts return null from getApplicationContext(), so the Connect IQ SDK
    // must be given the host ATAK application context to register receivers and bind services.
    private static Context resolveHostContext(Context context) {
        MapView mapView = MapView.getMapView();
        if (mapView != null && mapView.getContext() != null) {
            Context hostApp = mapView.getContext().getApplicationContext();
            return hostApp != null ? hostApp : mapView.getContext();
        }
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    public synchronized void start() {
        boolean alreadyStarted;
        boolean refresh;
        synchronized (lock) {
            alreadyStarted = started;
            refresh = started && sdkReady;
            if (!started) {
                started = true;
            }
        }
        if (alreadyStarted) {
            if (refresh) refreshDevices();
            else initializeSdk();
            return;
        }

        publishStatus("Connecting to Garmin Connect Mobile.");
        initializeSdk();
    }

    private synchronized void initializeSdk() {
        final long session;
        synchronized (lock) {
            if (!started || sdkReady || initializing) return;
            initializing = true;
            session = ++generation;
        }

        try {
            connectIQ.initialize(appContext, false, createSdkListener(session));
        } catch (RuntimeException e) {
            LogX.e(TAG, "Connect IQ initialization failed", e);
            synchronized (lock) {
                initializing = false;
            }
            publishConnectionState();
            publishStatus("Garmin Connect IQ failed to start: " + e.getMessage());
        }
    }

    private ConnectIQ.ConnectIQListener createSdkListener(long session) {
        return new ConnectIQ.ConnectIQListener() {
            @Override
            public void onSdkReady() {
                synchronized (GarminConnectIqClient.this) {
                    synchronized (lock) {
                        if (!started || generation != session) return;
                        initializing = false;
                        sdkReady = true;
                    }
                    refreshDevices();
                }
            }

            @Override
            public void onInitializeError(ConnectIQ.IQSdkErrorStatus errorStatus) {
                synchronized (GarminConnectIqClient.this) {
                    synchronized (lock) {
                        if (!started || generation != session) return;
                        initializing = false;
                    }
                    publishConnectionState();
                    publishStatus("Garmin Connect IQ unavailable: " + errorStatus);
                }
            }

            @Override
            public void onSdkShutDown() {
                synchronized (GarminConnectIqClient.this) {
                    synchronized (lock) {
                        if (!started || generation != session) return;
                        sdkReady = false;
                        initializing = false;
                        devices.clear();
                    }
                    publishConnectionState();
                    publishStatus("Garmin Connect IQ stopped.");
                }
            }
        };
    }

    // The SDK owns a mutable listener/receiver; finish shutdown before a new initialize.
    public synchronized void stop() {
        synchronized (lock) {
            if (!started) return;
            started = false;
            generation++;
            sdkReady = false;
            initializing = false;
            devices.clear();
        }
        publishConnectionState();
        publishStatus("Garmin Connect IQ is off.");

        try {
            connectIQ.unregisterAllForEvents();
        } catch (InvalidStateException e) {
            LogX.w(TAG, "Unable to unregister Connect IQ event listeners", e);
        }

        try {
            connectIQ.shutdown(appContext);
        } catch (InvalidStateException e) {
            LogX.w(TAG, "Unable to shut down Connect IQ SDK", e);
        }
    }

    public synchronized void refreshDevices() {
        boolean ready;
        synchronized (lock) {
            if (!started) return;
            ready = sdkReady;
        }
        if (!ready) {
            publishStatus("Waiting for Garmin Connect Mobile; retrying SDK initialization.");
            initializeSdk();
            return;
        }

        final List<IQDevice> knownDevices;
        try {
            knownDevices = connectIQ.getKnownDevices();
        } catch (InvalidStateException | ServiceUnavailableException e) {
            LogX.e(TAG, "Unable to list paired Garmin devices", e);
            publishStatus("Could not read Garmin devices: " + e.getMessage());
            return;
        }

        Map<Long, IQDevice> paired = new HashMap<>();
        if (knownDevices != null) {
            for (IQDevice device : knownDevices) {
                if (device != null) paired.put(device.getDeviceIdentifier(), device);
            }
        }
        List<DeviceApp> removed = new ArrayList<>();
        synchronized (lock) {
            for (DeviceApp entry : new ArrayList<>(devices.values())) {
                if (!paired.containsKey(entry.device.getDeviceIdentifier())) {
                    devices.remove(entry.device.getDeviceIdentifier());
                    entry.connected = false;
                    removed.add(entry);
                }
            }
        }
        for (DeviceApp entry : removed) {
            try {
                connectIQ.unregisterForEvents(entry.device);
            } catch (InvalidStateException e) {
                LogX.w(TAG, "Unable to unregister removed Garmin device", e);
            }
        }

        if (paired.isEmpty()) {
            publishConnectionState();
            publishStatus("No Garmin watches paired in Garmin Connect Mobile.");
            return;
        }

        for (IQDevice device : paired.values()) {
            registerDevice(device);
        }
        publishConnectionState();
        publishStatus("Found " + paired.size()
                + " Garmin device(s). " + WATCH_RELAY_INSTRUCTIONS);
    }

    public String getStatus() {
        synchronized (lock) {
            return status;
        }
    }

    static final class WatchIdentity {
        final long deviceId;
        final String product, callsign;
        final boolean connected;
        WatchIdentity(long deviceId, String product, String callsign, boolean connected) {
            this.deviceId = deviceId;
            this.product = product;
            this.callsign = callsign;
            this.connected = connected;
        }
    }

    synchronized List<WatchIdentity> watchIdentities() {
        List<WatchIdentity> result = new ArrayList<>();
        synchronized (lock) {
            for (DeviceApp entry : devices.values()) {
                result.add(new WatchIdentity(entry.device.getDeviceIdentifier(),
                        entry.device.getFriendlyName(), entry.standaloneCallsign, entry.connected));
            }
        }
        result.sort(java.util.Comparator.comparingLong(identity -> identity.deviceId));
        return result;
    }

    static String standaloneIdentity(JSONObject envelope, String previous) {
        String type = envelope.optString("msgType");
        if (!"watch_settings".equals(type) && !"relay_hello".equals(type)) return previous;
        JSONObject payload = envelope.optJSONObject("payload");
        if (payload == null) return previous;
        Object explicit = payload.opt("standaloneCallsign");
        if (explicit instanceof String) return ((String) explicit).trim();
        if ("watch_settings".equals(type) && Boolean.FALSE.equals(payload.opt("phoneManagedSettings"))) {
            Object callsign = payload.opt("callsign");
            if (callsign instanceof String && !((String) callsign).trim().isEmpty())
                return ((String) callsign).trim();
        }
        return previous;
    }

    public boolean isConnected() {
        synchronized (lock) {
            for (DeviceApp device : devices.values()) {
                if (device.connected) return true;
            }
            return false;
        }
    }

    public synchronized boolean sendMessage(String msgType, Map<String, Object> payload) {
        final List<DeviceApp> targets = new ArrayList<>();
        synchronized (lock) {
            if (!started || !sdkReady) return false;
            for (DeviceApp entry : devices.values()) {
                if (entry.app != null && entry.connected) {
                    targets.add(entry);
                }
            }
        }

        if (targets.isEmpty()) {
            LogX.w(TAG, "No connected Garmin watch with WearTAK available for " + msgType);
            return false;
        }

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("msgType", msgType);
        envelope.put("payload", payload == null ? new HashMap<String, Object>() : payload);
        if ("route".equals(msgType)
                && !GarminRouteProtocol.fitsEnvelope(msgType, payload)) {
            LogX.w(TAG, "Garmin message exceeds the 6000-byte envelope limit: " + msgType);
            return false;
        }

        boolean accepted = false;
        for (DeviceApp target : targets) {
            final long link = target.linkGeneration;
            try {
                connectIQ.sendMessage(target.device, target.app, envelope,
                        (device, app, messageStatus) -> {
                            synchronized (GarminConnectIqClient.this) {
                                if (!isCurrentDevice(target) || !target.connected
                                        || target.linkGeneration != link) return;
                                if (messageStatus == ConnectIQ.IQMessageStatus.SUCCESS) {
                                    LogX.i(TAG, "Sent " + msgType + " to " + device.getFriendlyName());
                                } else {
                                    LogX.w(TAG, "Message " + msgType + " to "
                                            + device.getFriendlyName() + " failed: " + messageStatus);
                                    publishStatus("Garmin message failed: " + messageStatus);
                                }
                            }
                        });
                accepted = true;
            } catch (InvalidStateException | ServiceUnavailableException | IllegalArgumentException e) {
                LogX.e(TAG, "Unable to send " + msgType + " to "
                        + target.device.getFriendlyName(), e);
                publishStatus("Unable to send Garmin message: " + e.getMessage());
            }
        }
        return accepted;
    }

    synchronized boolean stopOnlyReadyLink(GarminEntityForwarder.Link expected) {
        List<GarminEntityForwarder.Link> links = entityLinks();
        if (links.size() != 1 || links.get(0) != expected || !expected.isCurrent()) return false;
        stop();
        return true;
    }

    synchronized boolean sendOnlyReadyLink(GarminEntityForwarder.Link expected,
            String type, Map<String, Object> payload) {
        List<GarminEntityForwarder.Link> links = entityLinks();
        return links.size() == 1 && links.get(0) == expected && expected.isCurrent()
                && expected.send(type, payload);
    }

    synchronized List<GarminEntityForwarder.Link> entityLinks() {
        List<GarminEntityForwarder.Link> links = new ArrayList<>();
        synchronized (lock) {
            for (DeviceApp entry : devices.values()) {
                if (!isCurrentDevice(entry) || !entry.connected || entry.app == null
                        || !entry.appEventsRegistered) continue;
                if (entry.entityLink == null) {
                    entry.entityLink = new GarminEntityForwarder.Link() {
                        @Override public boolean isCurrent() {
                            synchronized (GarminConnectIqClient.this) {
                                return isCurrentDevice(entry) && entry.connected
                                        && entry.appEventsRegistered && entry.entityLink == this;
                            }
                        }
                        @Override public int routeVersion() {
                            return isCurrent() ? entry.routeVersion : 0;
                        }
                        @Override public int routeNavigationVersion() {
                            return isCurrent() ? entry.routeNavigationVersion : 0;
                        }
                        @Override public boolean send(String type, Map<String, Object> payload) {
                            synchronized (GarminConnectIqClient.this) {
                                if (!isCurrent()) return false;
                                Map<String, Object> envelope = new HashMap<>();
                                envelope.put("msgType", type);
                                envelope.put("payload", payload);
                                if ("route".equals(type)
                                        && !GarminRouteProtocol.fitsEnvelope(type, payload)) {
                                    LogX.w(TAG, "Garmin message exceeds the 6000-byte envelope limit: " + type);
                                    return false;
                                }
                                if (entry.entitySyncVersion >= 2 && ("entity".equals(type)
                                        || "entities".equals(type) || "entity_remove".equals(type))) {
                                    if (entry.relaySession.isEmpty()) return false;
                                    envelope.put("relaySession", entry.relaySession);
                                }
                                try {
                                    connectIQ.sendMessage(entry.device, entry.app, envelope,
                                            (device, app, status) -> {
                                                if (isCurrent() && status != ConnectIQ.IQMessageStatus.SUCCESS)
                                                    LogX.w(TAG, "Garmin entity handoff failed: " + status);
                                            });
                                    return true;
                                } catch (InvalidStateException | ServiceUnavailableException
                                        | IllegalArgumentException e) {
                                    LogX.w(TAG, "Garmin entity handoff rejected", e);
                                    return false;
                                }
                            }
                        }
                        @Override public int entitySyncVersion() { return entry.entitySyncVersion; }
                        @Override public String relaySession() { return entry.relaySession; }
                        @Override public String watchUid() { return entry.watchUid; }
                        @Override public boolean sendForSession(String type, Map<String, Object> payload,
                                String expectedSession) {
                            synchronized (GarminConnectIqClient.this) {
                                return entry.relaySession.equals(expectedSession) && send(type, payload);
                            }
                        }
                    };
                }
                links.add(entry.entityLink);
            }
        }
        return links;
    }

    synchronized boolean setRouteVersion(GarminEntityForwarder.Link link, int version) {
        synchronized (lock) {
            for (DeviceApp entry : devices.values()) {
                if (entry.entityLink == link && isCurrentDevice(entry) && entry.connected
                        && entry.appEventsRegistered) {
                    entry.routeVersion = version == GarminRouteProtocol.VERSION ? version : 0;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isCurrentDevice(DeviceApp entry) {
        synchronized (lock) {
            return started && sdkReady
                    && devices.get(entry.device.getDeviceIdentifier()) == entry;
        }
    }

    private synchronized void registerDevice(IQDevice device) {
        final DeviceApp entry;
        boolean isNewDevice;
        synchronized (lock) {
            if (!started || !sdkReady) return;
            long deviceId = device.getDeviceIdentifier();
            DeviceApp existing = devices.get(deviceId);
            if (existing == null) {
                entry = new DeviceApp(device);
                devices.put(deviceId, entry);
                isNewDevice = true;
            } else {
                entry = existing;
                isNewDevice = false;
            }
        }

        if (!isNewDevice) {
            loadApplication(entry);
            return;
        }

        try {
            connectIQ.registerForDeviceEvents(device, (changedDevice, newStatus) -> {
                synchronized (GarminConnectIqClient.this) {
                    if (!isCurrentDevice(entry)) return;
                    boolean connected = newStatus == IQDevice.IQDeviceStatus.CONNECTED;
                    if (entry.connected != connected) {
                        entry.linkGeneration++;
                        entry.entityLink = null;
                        entry.routeVersion = 0;
                        entry.routeNavigationVersion = 0;
                        if (entry.appEventsRegistered && entry.app != null) {
                            try {
                                connectIQ.unregisterForApplicationEvents(entry.device, entry.app);
                            } catch (InvalidStateException | ServiceUnavailableException e) {
                                LogX.w(TAG, "Unable to retire Garmin app subscription", e);
                            }
                        }
                        entry.appEventsRegistered = false;
                    }
                    entry.connected = connected;
                    publishConnectionState();
                    if (entry.connected) {
                        loadApplication(entry);
                        publishStatus("Garmin connected: " + changedDevice.getFriendlyName()
                                + ". " + WATCH_RELAY_INSTRUCTIONS);
                    } else {
                        publishStatus("Garmin not connected: " + changedDevice.getFriendlyName());
                    }
                }
            });
            try {
                entry.connected = connectIQ.getDeviceStatus(device)
                        == IQDevice.IQDeviceStatus.CONNECTED;
            } catch (InvalidStateException | ServiceUnavailableException e) {
                LogX.w(TAG, "Unable to read Garmin device status", e);
            }
            publishConnectionState();
            loadApplication(entry);
        } catch (InvalidStateException e) {
            synchronized (lock) {
                devices.remove(device.getDeviceIdentifier());
            }
            LogX.e(TAG, "Unable to register Garmin device events", e);
            publishStatus("Could not monitor Garmin device: " + e.getMessage());
        }
    }

    private synchronized void loadApplication(DeviceApp entry) {
        boolean appKnown;
        synchronized (lock) {
            if (!isCurrentDevice(entry)) return;
            appKnown = entry.app != null;
            if (!appKnown) {
                if (entry.appInfoPending) return;
                entry.appInfoPending = true;
            }
        }
        if (appKnown) {
            registerAppEvents(entry);
            return;
        }

        try {
            connectIQ.getApplicationInfo(WATCH_APP_ID, entry.device,
                    new ConnectIQ.IQApplicationInfoListener() {
                        @Override
                        public void onApplicationInfoReceived(IQApp app) {
                            synchronized (GarminConnectIqClient.this) {
                                synchronized (lock) {
                                    if (!isCurrentDevice(entry)) return;
                                    entry.appInfoPending = false;
                                    if (app == null) return;
                                    entry.app = app;
                                }
                                registerAppEvents(entry);
                            }
                        }

                        @Override
                        public void onApplicationNotInstalled(String applicationId) {
                            synchronized (GarminConnectIqClient.this) {
                                synchronized (lock) {
                                    if (!isCurrentDevice(entry)) return;
                                    entry.appInfoPending = false;
                                }
                                publishStatus("WearTAK is not installed on "
                                        + entry.device.getFriendlyName() + ".");
                            }
                        }
                    });
        } catch (InvalidStateException | ServiceUnavailableException e) {
            synchronized (lock) {
                entry.appInfoPending = false;
            }
            LogX.e(TAG, "Unable to check for WearTAK on Garmin watch", e);
            publishStatus("Could not check WearTAK on Garmin: " + e.getMessage());
        }
    }

    private synchronized void registerAppEvents(DeviceApp entry) {
        final long link;
        synchronized (lock) {
            if (!isCurrentDevice(entry) || entry.app == null || entry.appEventsRegistered) return;
            entry.appEventsRegistered = true;
            link = entry.linkGeneration;
        }

        try {
            connectIQ.registerForAppEvents(entry.device, entry.app,
                    (device, app, messageData, messageStatus) -> {
                        synchronized (GarminConnectIqClient.this) {
                            if (!isCurrentDevice(entry) || entry.linkGeneration != link) return;
                            if (messageStatus != ConnectIQ.IQMessageStatus.SUCCESS) {
                                LogX.w(TAG, "Garmin message receive failed: " + messageStatus);
                                publishStatus("Garmin receive error: " + messageStatus);
                                return;
                            }
                            if (messageData == null) return;
                            // A message from the watch proves the link is up even if no
                            // device status event has been delivered yet.
                            entry.connected = true;
                            publishConnectionState();
                            for (Object item : messageData) {
                                if (item instanceof Map) {
                                    JSONObject envelope = toJsonObject((Map<?, ?>) item);
                                    if (envelope != null && listener != null) {
                                        entry.standaloneCallsign = standaloneIdentity(envelope, entry.standaloneCallsign);
                                        if ("relay_hello".equals(envelope.optString("msgType"))) {
                                            JSONObject hello = envelope.optJSONObject("payload");
                                            entry.relaySession = hello == null ? "" : hello.optString("relaySession", "");
                                            entry.entitySyncVersion = hello == null ? 1 : hello.optInt("entitySyncVersion", 1);
                                            entry.watchUid = hello == null ? "" : hello.optString("watchUid", "");
                                            Object navigationVersion = hello == null ? null
                                                    : hello.opt("routeNavigationVersion");
                                            entry.routeNavigationVersion = navigationVersion instanceof Number
                                                    && ((Number) navigationVersion).doubleValue() == 1.0d ? 1 : 0;
                                        }
                                        entityLinks();
                                        listener.onMessageReceived(envelope, entry.entityLink);
                                    }
                                }
                            }
                        }
                    });
            publishConnectionState();
            publishStatus("WearTAK found on " + entry.device.getFriendlyName()
                    + ". " + WATCH_RELAY_INSTRUCTIONS);
        } catch (InvalidStateException | ServiceUnavailableException e) {
            synchronized (lock) {
                entry.appEventsRegistered = false;
            }
            LogX.e(TAG, "Unable to register Garmin app events", e);
            publishStatus("Could not listen for Garmin messages: " + e.getMessage());
        }
    }

    private JSONObject toJsonObject(Map<?, ?> values) {
        JSONObject result = new JSONObject();
        for (Map.Entry<?, ?> value : values.entrySet()) {
            if (!(value.getKey() instanceof String)) continue;
            try {
                result.put((String) value.getKey(), toJsonValue(value.getValue()));
            } catch (JSONException e) {
                LogX.w(TAG, "Skipping unsupported Garmin message value", e);
            }
        }
        return result;
    }

    private Object toJsonValue(Object value) {
        if (value instanceof Map) return toJsonObject((Map<?, ?>) value);
        if (value instanceof List) {
            JSONArray array = new JSONArray();
            for (Object item : (List<?>) value) array.put(toJsonValue(item));
            return array;
        }
        return value;
    }

    private void publishStatus(String newStatus) {
        synchronized (lock) {
            status = newStatus;
        }
        if (listener != null) listener.onStatusChanged(newStatus);
    }

    private void publishConnectionState() {
        boolean connected = false;
        synchronized (lock) {
            for (DeviceApp device : devices.values()) {
                if (device.connected) {
                    connected = true;
                    break;
                }
            }
        }
        if (listener != null) listener.onConnectionChanged(connected);
    }
}
