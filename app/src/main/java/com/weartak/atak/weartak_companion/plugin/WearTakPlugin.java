package com.weartak.atak.weartak_companion.plugin;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atak.plugins.impl.IToolbarItem;
import com.atakmap.android.gui.ImportFileBrowserDialog;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.missionpackage.api.MissionPackageApi;
import com.atakmap.android.missionpackage.api.ToastSaveCallback;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.platform.ui.MotionEvent;

public class WearTakPlugin implements IPlugin, IToolbarItem {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    Pane templatePane;

    private static final String TAG = "WTK/Plugin";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Boolean lastSettingsApplyResult = null;
    private CompanionDiagnosticLogger diagnosticLogger;
    private Button loggingButton;
    private boolean identityObserverActive;
    private List<String> lastSentPhoneIdentity;
    private final Runnable liveIdentityUpdate = this::syncLivePhoneIdentity;
    private final PhoneIdentityObserver phoneIdentityObserver = new PhoneIdentityObserver(() -> {
        mainHandler.removeCallbacks(liveIdentityUpdate);
        mainHandler.post(liveIdentityUpdate);
    });

    private WearTakBleClient bleClient;
    private SystemBondedWatchConnection systemBondedConnection;
    private boolean systemBondedEnabled;
    private TextView systemBondedWatchStatus;
    private TextView systemBondedIdentity;
    private LinearLayout garminIdentityRows;
    private Button systemBondedConnectButton;
    private View systemBondedSettingsButton;
    private View bleSettingsButton;
    private GarminConnectIqClient garminClient;
    private GarminEntityForwarder garminEntities;
    private GarminMapEntitySource garminEntitySource;
    private GarminRouteForwarder garminRoutes;
    private GarminRouteMapSource garminRouteSource;
    private GarminEntityForwarder.Link garminSettingsLink;
    private final GarminSettingsTransaction garminSettingsTransaction = new GarminSettingsTransaction();
    private Map<String, Object> garminSettingsDraftAtSend;
    private boolean garminSettingsSnapshotLoaded;
    private volatile boolean garminEnabled;
    private Button connectionModeButton;
    private Button purposeButton;
    private boolean setupPurpose;
    private boolean gatewayPurpose;
    private final PhoneGnssDiagnostics locationDiagnostics = new PhoneGnssDiagnostics();
    private JSONObject watchLocationDiagnostics;
    private MultiwatchGateway gateway;
    private View garminSettingsButton;
    private View serverSettingsGroup;
    private Button standaloneSetupButton;
    private final SettingsSessionGuard settingsSession = new SettingsSessionGuard();
    private final PhoneManagementRelease managementRelease = new PhoneManagementRelease();
    private Object releasePeer;
    private BooleanSupplier releaseCurrent;
    private BooleanSupplier releaseClose;
    private Runnable releaseTimeout;
    private static final String KEY_GARMIN_PAUSED = "garmin_connection_paused";
    private final SimulatorDiagnostics simulatorDiagnostics = new SimulatorDiagnostics();
    private final ReceivedCotTiming receivedCotTiming = new ReceivedCotTiming();
    private final ConnectionModeController connectionModes = new ConnectionModeController(
            new ConnectionModeController.Transport() {
                @Override public void stop() {
                    stopLocationDiagnostics("connection route stopped");
                    failManagementRelease("The route stopped before unlock was confirmed.");
                    ++scanSessionCount;
                    if (gateway != null) gateway.stop();
                    gateway = null;
                    systemBondedEnabled = garminEnabled = false;
                    garminSettingsTransaction.cancel();
                    garminSettingsLink = null;
                    garminSettingsSnapshotLoaded = false;
                    if (garminEntities != null) garminEntities.close();
                    garminEntities = null;
                    garminEntitySource = null;
                    if (garminRoutes != null) garminRoutes.close();
                    garminRoutes = null;
                    garminRouteSource = null;
                    if (bloodhoundBridge != null) {
                        bloodhoundBridge.close();
                        bloodhoundBridge = null;
                    }
                    unregisterMapItemListener();
                    stopPhoneAlertForwarding();
                    if (bleCotBridge != null) bleCotBridge.stopChatForwarding();
                    if (systemBondedConnection != null) systemBondedConnection.stop();
                    if (bleClient != null) {
                        bleClient.setAutomaticReconnectAllowed(false);
                        bleClient.stop();
                    }
                    if (garminClient != null) garminClient.stop();
                    systemBondedConnection = null;
                    bleClient = null;
                    garminClient = null;
                    currentConnectedAddress = null;
                    selectedDevice = null;
                    clearSessionSettings();
                    hideConnectedCard();
                    showDeviceScreen();
                    scannedDevices.clear();
                    if (deviceAdapter != null) {
                        deviceAdapter.setDevices(scannedDevices);
                        deviceAdapter.clearConnectionMarkers();
                    }
                }
                @Override public void start(ConnectionModeController.Mode mode, long token) {
                    if (mode == ConnectionModeController.Mode.GARMIN) startGarmin(token);
                    else initializeBleTransport(token);
                    updateConnectionModeUi();
                }
            });
    private BleCotBridge bleCotBridge;
    private volatile BloodhoundBridge bloodhoundBridge;
    private final PhoneMarkerRelay phoneMarkerPolicy = new PhoneMarkerRelay();
    private PhoneMarkerMapRelay phoneMarkerRelay;
    private PhoneRouteNavigationBridge phoneRouteNavigation;
    private GarminRouteNavigationTransport garminRouteNavigationTransport;
    private PhoneUserMapRelay phoneUserRelay;
    private WatchChatEchoSuppressor chatEchoSuppressor;
    private CotServiceRemote cotServiceRemote;
    private final PhoneEmergencyRelay phoneEmergencyRelay = new PhoneEmergencyRelay();
    private final BroadcastReceiver phoneEmergencyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            CotEvent event;
            if ("com.atakmap.android.emergency.tool.SEND_EMERGENCY".equals(intent.getAction())) {
                String xml = intent.getStringExtra("MESSAGE");
                if (xml == null || xml.isEmpty()) return;
                try {
                    event = CotEvent.parse(xml);
                } catch (Exception e) {
                    LogX.w(TAG, "Invalid phone emergency notification", e);
                    return;
                }
            } else {
                event = intent.getParcelableExtra("cotevent");
            }
            forwardPhoneEmergencyToWatch(event, null);
        }
    };

    // ------------------ UI ROOT ------------------
    // IMPORTANT: This is the inflated main_layout.xml root view.
    private View paneView = null;

    // Screen roots
    private View deviceScreenRoot;
    private View settingsScreenRoot;

    // Device screen
    private TextView connectionStatusTV;
    private TextView locationDiagnosticsStatus;
    private TextView garminConnectionStatusTV;
    private TextView garminConnectionBadgeTV;
    private Button garminToggleButton;
    private Button scanButton;
    private RecyclerView deviceList;
    private TextView emptyState;

    private BleDeviceAdapter deviceAdapter;
    private final ArrayList<WearTakBleClient.DiscoveredDevice> scannedDevices = new ArrayList<>();
    private WearTakBleClient.DiscoveredDevice selectedDevice = null;

    // Settings screen
    private Button settingsBackButton;
//    private TextView settingsDeviceSubtitle;

    private RecyclerView takServerListRV;
    private TakServerListAdapter takServerAdapter;

    private EditText takNameET;
    private EditText takAddressET;
    private EditText takPortET;
    private EditText takUsernameET;
    private EditText takPasswordET;
    private CheckBox takUseP12CB;
    private EditText takP12PasswordET;
    private TextView takP12StatusTV;
    private Button chooseP12Button;
    private Button clearP12Button;
    private EditText reportIntervalET;

    private Button sendSettingsButton;
    private Button refreshSettingsButton;
    private TextView phoneManagedSettingsDescription;
    private EditText watchCallsignET;
    private TextView phoneTeamTV;
    private TextView phoneRoleTV;
    private TextView settingsStatusTV;
    private CheckBox phoneManagedSettingsCB;

    // State
    private int scanSessionCount = 0;

    // last received snapshot settings
    private volatile JSONObject lastSettingsPayload = null;
    private List<TakServerItem> currentServerList = new ArrayList<>();
    private int currentSelectedServerIndex = -1;
    private volatile boolean phoneSettingsPushedThisConnection;
    private boolean phoneManagedSettingsRequested = true;
    private boolean settingsModeLoadedThisConnection;
    private final PhoneSettingsHandshake phoneSettingsHandshake = new PhoneSettingsHandshake();
    private Runnable phoneSettingsTimeout;
    private String phoneSettingsRetryJson;

    // Connection state
    private String currentConnectedAddress = null;
    private View connectedCardView = null;

    // Strings (keep literal for now; you can move to strings.xml later)
    private static final String SCAN_TEXT_DEFAULT = "Scan";
    private static final String PREFS_NAME = "weartak_companion_prefs";
    private static final String KEY_PREF_ADDR = "preferred_device_address";
    private static final String KEY_PREF_NAME = "preferred_device_name";
    private static final String KEY_PREF_SET_AT = "preferred_device_set_at_ms";
    private static final String KEY_SYSTEM_BONDED_ENABLED = "use_system_bonded_watch";
    private static final String KEY_WEARABLE_PAUSED = "wearable_connection_paused";
    private static final String SAMSUNG_WEARABLE_PACKAGE = "com.samsung.android.app.watchmanager";
    private static final String GARMIN_CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile";
    private static final String NO_P12_SELECTED = "No certificate selected.";
    private static final String WATCH_P12_SELECTED = "Certificate loaded from watch sync.";

    // ------------------ Connected card helpers ------------------

    private void showConnectedCard(WearTakBleClient.DiscoveredDevice d) {
        if (paneView == null) return;
        if (d == null) return;
        if (connectionModes.getMode() != ConnectionModeController.Mode.TRADITIONAL) return;

        // already shown -> just rebind
        if (connectedCardView != null) {
            bindConnectedCard(connectedCardView, d);
            return;
        }

        ViewGroup deviceRoot = paneView.findViewById(R.id.deviceScreenRoot);
        View statusRow = paneView.findViewById(R.id.bleStatusRow);
        if (deviceRoot == null || statusRow == null) return;

        int scanIndex = deviceRoot.indexOfChild(statusRow) + 1;
        if (scanIndex < 0) scanIndex = 0;

        connectedCardView = LayoutInflater.from(deviceRoot.getContext())
                .inflate(R.layout.item_ble_device, deviceRoot, false);

        // Keep the pairing selector and status controls above the connected device.
        deviceRoot.addView(connectedCardView, scanIndex);

        bindConnectedCard(connectedCardView, d);
    }

    private void bindConnectedCard(View card, WearTakBleClient.DiscoveredDevice d) {
        if (card == null || d == null) return;

        TextView name = card.findViewById(R.id.deviceName);
        TextView bondedName = card.findViewById(R.id.deviceBondedName);
        TextView mac = card.findViewById(R.id.deviceMac);
        TextView rssi = card.findViewById(R.id.rssiBadge);
        TextView state = card.findViewById(R.id.deviceState);

        Button disconnect = card.findViewById(R.id.disconnectButton);
        Button action = card.findViewById(R.id.actionButton);
        View settings = card.findViewById(R.id.watchSettingsButton);

        // Defensive: if you haven't updated item_ble_device.xml yet,
        // disconnectButton may not exist. Fail softly.
        if (name != null && bondedName != null) BleDeviceAdapter.bindNames(name, bondedName,
                bleClient != null ? bleClient.withSessionIdentity(d) : d, panelStatusText());
        if (mac != null) mac.setVisibility(View.GONE);
        if (rssi != null) rssi.setVisibility(View.GONE);
        if (state != null) state.setVisibility(View.GONE);

        if (disconnect != null) {
            disconnect.setVisibility(View.GONE);
            disconnect.setEnabled(true);
            disconnect.setOnClickListener(v -> onDisconnectClicked());
        }

        if (action != null) {
            action.setVisibility(setupPurpose ? View.VISIBLE : View.GONE);
            action.setText(managementRelease.isPending() ? "Wait" : "Disconnect");
            action.setEnabled(!managementRelease.isPending());
            action.setOnClickListener(v -> onDisconnectClicked());
        }
        if (settings != null) {
            settings.setVisibility(setupPurpose ? View.VISIBLE : View.GONE);
            settings.setEnabled(bleClient != null && bleClient.isConnected());
            settings.setAlpha(settings.isEnabled() ? 1f : 0.4f);
            settings.setOnClickListener(v -> {
                if (bleClient == null || !bleClient.isConnected()) return;
                selectedDevice = d;
                showSettingsScreen();
                requestSettingsFromWatch("connectedCardSettings");
                if (lastSettingsPayload != null) {
                    applyServersToUi(currentServerList);
                    Integer reportInt = optInt(lastSettingsPayload, "reportIntSecs");
                    if (reportInt != null && reportIntervalET != null) {
                        reportIntervalET.setText(String.valueOf(reportInt));
                    }
                }
            });
        }
    }

    private void hideConnectedCard() {
        if (connectedCardView == null) return;
        ViewGroup parent = (ViewGroup) connectedCardView.getParent();
        if (parent != null) parent.removeView(connectedCardView);
        connectedCardView = null;
    }

    private void onDisconnectClicked() {
        requestBleManagementRelease();
    }

    private void releaseNotice(String message) {
        LogX.i(TAG, message);
        if (settingsStatusTV != null) settingsStatusTV.setText(message);
        Toast.makeText(MapView.getMapView().getContext(), message, Toast.LENGTH_LONG).show();
    }

    private void clearManagementRelease() {
        managementRelease.cancel();
        if (releaseTimeout != null) mainHandler.removeCallbacks(releaseTimeout);
        releaseTimeout = null;
        releasePeer = null;
        releaseCurrent = releaseClose = null;
        updateManagementReleaseUi();
    }

    private void updateManagementReleaseUi() {
        updateSettingsSendState();
        updateConnectionModeUi();
        updateSystemBondedControl();
        if (deviceAdapter != null) deviceAdapter.setDisconnectPending(managementRelease.isPending());
    }

    private void failManagementRelease(String reason) {
        if (!managementRelease.isPending()) return;
        clearManagementRelease();
        releaseNotice("Cannot confirm watch settings unlock. " + reason
                + " Watch may remain locked; reconnect or retry Disconnect.");
    }

    private String beginManagementRelease(Object peer, BooleanSupplier current,
            BooleanSupplier close) {
        if (managementRelease.isPending()) {
            releaseNotice("Waiting for watch unlock confirmation; the route has not been disconnected.");
            return null;
        }
        String id = UUID.randomUUID().toString();
        cancelPhoneSettingsHandshake();
        long attempt = managementRelease.begin(peer, id, SystemClock.elapsedRealtime());
        releasePeer = peer;
        releaseCurrent = current;
        releaseClose = close;
        releaseTimeout = () -> {
            if (managementRelease.token() != attempt || !managementRelease.isPending()) return;
            // Preserve the pending state until failManagementRelease reports the failure.
            failManagementRelease(current.getAsBoolean()
                    ? "No acknowledgement received (watch may need an update). Route left connected."
                    : "The original watch session is no longer ready.");
        };
        mainHandler.postDelayed(releaseTimeout, PhoneManagementRelease.TIMEOUT_MILLIS);
        updateManagementReleaseUi();
        releaseNotice("Requesting watch settings unlock before disconnecting…");
        return id;
    }

    private void requestBleManagementRelease() {
        WearTakBleClient owner = bleClient;
        long session = owner == null ? -1 : owner.getReadySession();
        if (session < 0 || gatewayPurpose) {
            releaseNotice("Cannot confirm watch settings unlock: no ready single-watch link. "
                    + "The watch may remain locked until reconnected or unpaired on the watch.");
            return;
        }
        Object peer = new Object();
        SystemBondedWatchConnection samsung = systemBondedConnection;
        boolean isSamsung = connectionModes.getMode() == ConnectionModeController.Mode.SAMSUNG;
        String id = beginManagementRelease(peer,
                () -> bleClient == owner && owner.getReadySession() == session,
                () -> {
                    if (!owner.disconnectReadySession(session)) return false;
                    if (isSamsung && samsung != null) {
                        systemBondedPreferences().edit().putBoolean(KEY_WEARABLE_PAUSED, true).apply();
                        samsung.pause();
                    }
                    clearSessionSettings();
                    ++scanSessionCount;
                    return true;
                });
        if (id == null) return;
        try {
            JSONObject payload = new JSONObject().put("requestId", id);
            String command = new JSONObject().put("msg_type", PhoneManagementRelease.COMMAND)
                    .put("payload", payload).toString();
            if (!owner.writeJsonLineToWatch(command, session)) {
                failManagementRelease("Could not send release; route left connected.");
            }
        } catch (JSONException e) {
            LogX.e(TAG, "Could not encode management release", e);
            failManagementRelease("Could not encode release; route left connected.");
        }
    }

    private void requestGarminManagementRelease() {
        refreshGarminSettingsLink();
        GarminConnectIqClient owner = garminClient;
        GarminEntityForwarder.Link link = garminSettingsLink;
        if (owner == null || link == null || !link.isCurrent() || gatewayPurpose) {
            releaseNotice("Cannot confirm watch settings unlock: exactly one ready Garmin watch "
                    + "is required. The watch may remain locked; route not paused.");
            return;
        }
        if (garminSettingsTransaction.isPending()) {
            releaseNotice("Watch settings exchange is still pending. Wait for it to finish, "
                    + "then retry Pause; watch unlock has not been requested.");
            return;
        }
        String id = beginManagementRelease(link,
                () -> garminClient == owner && currentGarminBloodhoundLink(owner) == link,
                () -> {
                    if (!owner.stopOnlyReadyLink(link)) return false;
                    systemBondedPreferences().edit().putBoolean(KEY_GARMIN_PAUSED, true).apply();
                    connectionModes.stop();
                    renderGarminConnection(false);
                    return true;
                });
        if (id == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", id);
        if (!owner.sendOnlyReadyLink(link, PhoneManagementRelease.COMMAND, payload)) {
            failManagementRelease("Could not send release; Garmin route left connected.");
        }
    }

    private void handleManagementReleaseAck(JSONObject payload, Object peer) {
        if (!managementRelease.isPending() || peer != releasePeer) return;
        if (releaseCurrent == null || !releaseCurrent.getAsBoolean()) {
            failManagementRelease("The original watch session changed.");
            return;
        }
        if (payload == null) return;
        PhoneManagementRelease.Result result = managementRelease.acknowledge(peer,
                payload.opt("requestId"), managementRelease.token(),
                SystemClock.elapsedRealtime(), payload.opt("ok"));
        if (result == PhoneManagementRelease.Result.IGNORED) return;
        BooleanSupplier close = releaseClose;
        clearManagementRelease();
        if (result == PhoneManagementRelease.Result.SUCCEEDED) {
            if (close != null && close.getAsBoolean()) {
                releaseNotice("Watch settings unlocked; ATAK route disconnected. Settings and bond preserved.");
            } else {
                releaseNotice("Watch acknowledged unlock, but the original link changed; "
                        + "no replacement link was disconnected. Verify watch settings.");
            }
        } else {
            releaseNotice("Cannot confirm watch settings unlock. "
                    + (result == PhoneManagementRelease.Result.TIMED_OUT ? "Acknowledgement arrived too late."
                    : "Watch refused release: " + payload.optString("error", "no reason supplied"))
                    + " Route left connected; retry Disconnect.");
        }
    }

    private void connectToDevice(WearTakBleClient.DiscoveredDevice device) {
        if (device == null || bleClient == null
                || connectionModes.getMode() != ConnectionModeController.Mode.TRADITIONAL) return;

        disableSystemBondedForManualUse();
        clearSessionSettings();
        ++scanSessionCount;
        selectedDevice = device;
        bleClient.resetSession("UI connect " + device.address);
        currentConnectedAddress = device.address;

        if (deviceAdapter != null) deviceAdapter.setConnecting(device.address);
        setConnectionStatus("Connecting WearTAK watch...");
        bleClient.connectToSelectedDevice(device);
    }

    // ------------------ Plugin lifecycle ------------------
    public WearTakPlugin(IServiceController serviceController) {
        this.serviceController = serviceController;

        final PluginContextProvider ctxProvider = serviceController.getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);
        MapView mapView = MapView.getMapView();
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onStart() {
        if (Looper.myLooper() != mainHandler.getLooper()) {
            mainHandler.post(this::onStart);
            return;
        }
        this.serviceController.registerComponent(IToolbarItem.class, this);

        if (pluginContext == null && serviceController != null) {
            PluginContextProvider ctxProvider = serviceController.getService(PluginContextProvider.class);
            if (ctxProvider != null) {
                pluginContext = ctxProvider.getPluginContext();
                if (pluginContext != null) pluginContext.setTheme(R.style.ATAKPluginTheme);
            }
        }

        assert serviceController != null;
        uiService = serviceController.getService(IHostUIService.class); // safe refresh
        if (pluginContext == null) {
            LogX.w(TAG, "onStart: pluginContext is null; skipping BLE init");
            return;
        }

        initializeDiagnosticLogger();
        identityObserverActive = true;
        MapView identityMap = MapView.getMapView();
        receivedCotTiming.start(identityMap);
        phoneIdentityObserver.start(AtakPreferences.getInstance(identityMap.getContext()).getSharedPrefs(),
                identityMap.getSelfMarker());
        if (bleCotBridge == null) bleCotBridge = new BleCotBridge();
        bleCotBridge.setReceivedCotTiming(receivedCotTiming);
        SharedPreferences prefs = systemBondedPreferences();
        setupPurpose = prefs.getBoolean("setup_purpose", false);
        gatewayPurpose = "gateway".equals(prefs.getString("purpose", ""));
        if (gatewayPurpose) setupPurpose = false;
        String saved = prefs.getString("connection_mode", null);
        if (saved == null) {
            saved = pluginContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("connection_mode", null);
        }
        ConnectionModeController.Mode mode = ConnectionModeController.restore(saved,
                prefs.getBoolean(KEY_SYSTEM_BONDED_ENABLED, false));
        if (gatewayPurpose) mode = ConnectionModeController.Mode.TRADITIONAL;
        persistConnectionMode(mode);
        configureWatchChatEchoSuppression(!gatewayPurpose);
        connectionModes.start(mode, gatewayPurpose);
        simulatorDiagnostics.start(MapView.getMapView(), bleCotBridge, chatEchoSuppressor);
    }

    private void configureWatchChatEchoSuppression(boolean enabled) {
        if (bleCotBridge == null) return;
        if (!enabled) {
            try {
                if (chatEchoSuppressor != null) chatEchoSuppressor.stop();
            } catch (RuntimeException e) {
                LogX.e(TAG, "Could not restore ATAK chat listener cleanly", e);
            } finally {
                bleCotBridge.setWatchChatSend(Runnable::run);
            }
            return;
        }
        try {
            if (chatEchoSuppressor == null) chatEchoSuppressor = new WatchChatEchoSuppressor();
            chatEchoSuppressor.start();
            bleCotBridge.setWatchChatSend(chatEchoSuppressor::send);
        } catch (RuntimeException e) {
            bleCotBridge.setWatchChatSend(Runnable::run);
            LogX.e(TAG, "Could not install watch chat echo suppression; watch echoes may notify ATAK", e);
        }
    }

    private void initializeBleTransport(final long token) {
        if (gatewayPurpose) {
            gateway = new MultiwatchGateway(pluginContext, bleCotBridge);
            gateway.start();
            if (paneView != null) gateway.bind(paneView.findViewById(R.id.gatewayPanel));
            startPhoneAlertForwarding(token);
            return;
        }
        systemBondedEnabled = connectionModes.getMode() == ConnectionModeController.Mode.SAMSUNG;
        if (bleClient == null) {
            bleClient = new WearTakBleClient(pluginContext, null);
        }
        bleClient.setStatusListener((WearTakBleClient.RefreshAwareStatusListener) (connected, refreshing) -> {
                runOnMain(() -> {
                    if (!connectionModes.isCurrent(token)) return;
                    if (!connected) failManagementRelease("Watch disconnected before acknowledgement.");
                    if (!connected) stopLocationDiagnostics("watch disconnected");
                    if (!connected && bleCotBridge != null) bleCotBridge.discardPendingCot();
                    if (!connected && phoneUserRelay != null) phoneUserRelay.disconnected();
                    if (systemBondedConnection != null)
                        systemBondedConnection.onConnectionChanged(connected, refreshing);
                    BloodhoundBridge bridge = bloodhoundBridge;
                    if (!connected && bridge != null) bridge.disconnected();
                    if (connectionStatusTV != null) {
                        setConnectionStatus(connected ? "CONNECTED"
                                : systemBondedConnection != null && systemBondedConnection.isActive()
                                ? systemBondedConnection.getStatus() : "NOT CONNECTED");
                    }

                    if (connected) {
                        // Persist preferred device for future sessions
                        String addr = currentConnectedAddress;
                        String nm = (selectedDevice != null) ? selectedDevice.name : null;
                        if (addr != null && !addr.trim().isEmpty()) {
                            savePreferredDevice(addr, nm);
                        }
                        // Show the card if we have a selected device (common path).
                        if (selectedDevice != null) {
                            currentConnectedAddress = selectedDevice.address;
                            showConnectedCard(selectedDevice);
                        }
                    } else {
                        clearSessionSettings();
                        hideConnectedCard();
                        showDeviceScreen();
                        currentConnectedAddress = null;
                        phoneSettingsPushedThisConnection = false;
                    }

                    if (deviceAdapter != null) {
                        if (connected) {
                            if (currentConnectedAddress != null) {
                                deviceAdapter.setConnected(currentConnectedAddress);
                            } else {
                                deviceAdapter.notifyDataSetChanged();
                            }
                        } else {
                            deviceAdapter.clearConnectionMarkers();
                        }
                    }

                    refreshDeviceListUi();
                    updateConnectionModeUi();
                    updateSystemBondedControl();
                });
            });

        if (bleCotBridge == null) {
            bleCotBridge = new BleCotBridge();
            LogX.i(TAG, "BleCotBridge initialized in plugin");
        }

        if (bloodhoundBridge == null) {
            bloodhoundBridge = new BloodhoundBridge(MapView.getMapView(), bleClient);
        }
        registerMapItemListener();
        bleClient.setAutomaticReconnectAllowed(
                connectionModes.getMode() == ConnectionModeController.Mode.TRADITIONAL);
        bleClient.start();

        bleClient.setJsonListener(new WearTakBleClient.JsonListener() {
            @Override
            public void onBluetoothStateChanged(boolean available) {
                runOnMain(() -> {
                    if (!connectionModes.isCurrent(token)) return;
                    if (systemBondedConnection != null) {
                        systemBondedConnection.onBluetoothStateChanged(available);
                    }
                    updateSystemBondedControl();
                });
            }

            @Override
            public void onReady() {
                LogX.i(TAG, "BLE READY");
                runOnMain(() -> {
                    if (!connectionModes.isCurrent(token)) return;
                    if (!bleClient.isConnected()) return;
                    phoneSettingsPushedThisConnection = false;
                    settingsModeLoadedThisConnection = false;
                    if (!setupPurpose && phoneManagedSettingsRequested) {
                        startPhoneSettingsHandshake();
                    } else {
                        setConnectionStatus("CONNECTED");
                        requestSettingsFromWatch("onReady");
                    }
                });
            }

            @Override
            public void onJson(String jsonLine) {
                final long settingsToken = settingsSession.token();
                final WearTakBleClient incomingOwner = bleClient;
                final long incomingSession = incomingOwner == null ? -1 : incomingOwner.getReadySession();
                runOnMain(() -> {
                if (!connectionModes.isCurrent(token) || !settingsSession.isCurrent(settingsToken)) return;
                if (incomingOwner == null || incomingOwner != bleClient || incomingSession < 0
                        || incomingOwner.getReadySession() != incomingSession) return;
                LogX.d(TAG, "RX JSON bytes=" + jsonLine.getBytes(StandardCharsets.UTF_8).length);
                try {
                    JSONObject pointEnvelope = new JSONObject(jsonLine);
                    String diagnosticType = pointEnvelope.optString("msgType",
                            pointEnvelope.optString("msg_type", ""));
                    if ("watch_info".equals(diagnosticType)
                            || "settings_request".equals(diagnosticType)
                            || "settings_changed".equals(diagnosticType)) {
                        refreshIdentityStatus();
                        refreshDeviceListUi();
                    }
                    if (LocationDiagnosticsProtocol.HELLO.equals(diagnosticType) ||
                            LocationDiagnosticsProtocol.STOP.equals(diagnosticType) ||
                            LocationDiagnosticsProtocol.SNAPSHOT.equals(diagnosticType)) {
                        handleLocationDiagnosticsEnvelope(pointEnvelope, incomingOwner, incomingSession);
                        return;
                    }
                    if (PhoneManagementRelease.ACK.equals(pointEnvelope.optString("msgType",
                            pointEnvelope.optString("msg_type", "")))) {
                        if (incomingOwner != null && incomingOwner == bleClient && incomingSession >= 0
                                && incomingOwner.getReadySession() == incomingSession) {
                            handleManagementReleaseAck(pointEnvelope.optJSONObject("payload"), releasePeer);
                        }
                        return;
                    }
                    if ("marker_delete".equals(pointEnvelope.optString("msg_type"))) {
                        if (phoneMarkerRelay != null) phoneMarkerRelay.receiveDeletion(jsonLine);
                        else LogX.w(TAG, "Point relay unavailable for deletion");
                        return;
                    }
                    if ("marker_delete_ack".equals(pointEnvelope.optString("msg_type"))) {
                        if (phoneMarkerRelay != null) phoneMarkerRelay.receiveDeletionAck(jsonLine);
                        return;
                    }
                } catch (org.json.JSONException e) {
                    LogX.w(TAG, "Invalid point envelope", e);
                    return;
                }
                BloodhoundBridge bridge = bloodhoundBridge;
                if (bridge != null && bridge.handleJson(jsonLine)) return;
                if (phoneRouteNavigation != null && phoneRouteNavigation.handleJson(jsonLine)) return;

                // CoT forwarder path (your design)
                if (bleCotBridge != null) {
                    try {
                        bleCotBridge.handleJsonFromWearTak(jsonLine);
                    } catch (Throwable t) {
                        LogX.w(TAG, "BleCotBridge threw while handling RX JSON", t);
                    }
                }

                // Non-CoT messages handled locally (settings updates)
                try {
                    JSONObject env = new JSONObject(jsonLine);
                    String msgType = env.optString("msgType", "");

                    if ("settings_request".equals(msgType)) {
                        JSONObject payload = env.optJSONObject("payload");
                        if (payload != null) {
                            // treat it as “requested/current settings snapshot”
                            applySettingsPayloadDiscretely(payload, "settings_request", settingsToken);

                            mainHandler.post(() -> {
                                if (!connectionModes.isCurrent(token) || !settingsSession.isCurrent(settingsToken)) return;
                                if (settingsStatusTV != null && lastSettingsApplyResult == null) {
                                    settingsStatusTV.setText("Settings loaded from watch.");
                                }
                                updatePhoneIdentityViews();
                                if (phoneSettingsHandshake.isPending()) {
                                    handlePhoneSettingsAction(phoneSettingsHandshake.snapshot(
                                            phoneSettingsHandshake.token(),
                                            payload.optJSONArray("takServerList") != null
                                                    && optInt(payload, "reportIntSecs") != null,
                                            payload.optBoolean("phoneManagedSettings", false),
                                            SystemClock.elapsedRealtime()));
                                }
                            });
                        }
                    }

                    if ("settings_set_ack".equals(msgType)) {
                        JSONObject p = env.optJSONObject("payload");
                        boolean ok = (p != null) && p.optBoolean("ok", false);
                        String err = (p != null) ? p.optString("error", "") : "";
                        lastSettingsApplyResult = ok;
                        mainHandler.post(() -> {
                            if (!connectionModes.isCurrent(token) || !settingsSession.isCurrent(settingsToken)) return;
                            if (!ok) {
                                phoneSettingsPushedThisConnection = false;
                            }
                            if (settingsStatusTV != null) {
                                settingsStatusTV.setText(ok
                                        ? "Settings validated and saved on watch; server connection not yet verified."
                                        : ("Watch could not apply settings: " + err));
                            }
                            handlePhoneSettingsAction(phoneSettingsHandshake.acknowledge(
                                    phoneSettingsHandshake.token(), ok, SystemClock.elapsedRealtime()));
                        });
                    }

                    if ("settings_changed".equals(msgType)) {
                        JSONObject payload = env.optJSONObject("payload");
                        if (payload != null) {
                            applySettingsPayloadDiscretely(payload, "settings_changed", settingsToken);

                            mainHandler.post(() -> {
                                if (!connectionModes.isCurrent(token) || !settingsSession.isCurrent(settingsToken)) return;
                                if (settingsStatusTV != null) settingsStatusTV.setText("Settings were last changed from the watch.");
                            });
                        }
                    }

                } catch (Throwable t) {
                    LogX.w(TAG, "onJson parse failed", t);
                }
                });
            }

            @Override public void onPaired(String deviceId, String callsign) {
                final long readySession = bleClient.getReadySession();
                runOnMain(() -> {
                    if (connectionModes.isCurrent(token) && phoneUserRelay != null)
                        phoneUserRelay.ready(deviceId, readySession);
                });
            }

            @Override
            public void onTrustLost(String deviceAddress, String reason) {
                runOnMain(() -> {
                    if (!connectionModes.isCurrent(token)) return;
                    stopLocationDiagnostics("watch bond lost");
                    clearPreferredDevice(deviceAddress);
                    clearSessionSettings();
                    updateSystemBondedControl();
                    BloodhoundBridge bridge = bloodhoundBridge;
                    if (bridge != null) bridge.disconnected();
                    currentConnectedAddress = null;
                    hideConnectedCard();
                    setConnectionStatus("NOT CONNECTED");
                    if (deviceAdapter != null) {
                        deviceAdapter.clearConnectionMarkers();
                        refreshDeviceListUi();
                    }
                    if (settingsStatusTV != null && settingsScreenRoot != null
                            && settingsScreenRoot.getVisibility() == View.VISIBLE) {
                        settingsStatusTV.setText("BLE trust lost. Re-pair required.");
                    }
                });
            }

            @Override
            public void onConnectionProgress(String message) {
                runOnMain(() -> {
                    if (!connectionModes.isCurrent(token)) return;
                    if (connectionStatusTV != null && !bleClient.isConnected()) {
                        setConnectionStatus("BLE: " + message);
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnMain(() -> {
                    if (!connectionModes.isCurrent(token)) return;
                    if (systemBondedConnection != null) systemBondedConnection.onError(msg);
                    updateSystemBondedControl();
                    if (!bleClient.isConnected() && deviceAdapter != null) {
                        deviceAdapter.clearConnectionMarkers();
                        refreshDeviceListUi();
                    }
                    if (connectionStatusTV != null && !bleClient.isConnected()) {
                        setConnectionStatus(
                                systemBondedConnection != null && systemBondedConnection.isActive()
                                        ? systemBondedConnection.getStatus() : "BLE: " + msg);
                    }
                    if (settingsStatusTV != null && settingsScreenRoot != null
                            && settingsScreenRoot.getVisibility() == View.VISIBLE) {
                        settingsStatusTV.setText("BLE error: " + msg);
                    }
                });
            }
        });
        startPhoneAlertForwarding(token);
        bleCotBridge.startChatForwarding(bleClient);
        if (systemBondedConnection == null) {
            systemBondedConnection = new SystemBondedWatchConnection(
                    new SystemBondedWatchConnection.Scheduler() {
                        @Override public void execute(Runnable action) { runOnMain(action); }
                        @Override public void later(Runnable action, long delayMs) {
                            mainHandler.postDelayed(action, delayMs);
                        }
                        @Override public void cancel(Runnable action) { mainHandler.removeCallbacks(action); }
                    },
                    new SystemBondedWatchConnection.Transport() {
                        @Override public WearTakBleClient.DiscoveredDevice knownBondedCandidate() {
                            return bleClient.getKnownBondedCompanion();
                        }
                        @Override public void find(WearTakBleClient.BondedCompanionListener listener) {
                            bleClient.findBondedCompanion(listener);
                        }
                        @Override public void connect(WearTakBleClient.DiscoveredDevice device) {
                            bleClient.connectToSelectedDevice(device, false);
                        }
                        @Override public void reset() { bleClient.resetSession("system bonded activation"); }
                    },
                    new SystemBondedWatchConnection.Listener() {
                        @Override public void onStatus(String message) {
                            if (!connectionModes.isCurrent(token)) return;
                            LogX.i(TAG, message);
                            updateSystemBondedControl();
                        }
                        @Override public void onConnecting(WearTakBleClient.DiscoveredDevice device) {
                            if (!connectionModes.isCurrent(token)) return;
                            selectedDevice = device;
                            currentConnectedAddress = device.address;
                            if (deviceAdapter != null) deviceAdapter.setConnecting(device.address);
                        }
                        @Override public void onCandidate(WearTakBleClient.DiscoveredDevice device) {
                            if (!connectionModes.isCurrent(token)) return;
                            updateSystemBondedControl();
                        }
                    });
        }
        if (systemBondedEnabled) {
            if (systemBondedPreferences().getBoolean(KEY_WEARABLE_PAUSED, false)) {
                systemBondedConnection.pause();
            } else {
                systemBondedConnection.start();
            }
        }
        updateSystemBondedControl();
    }

    private SharedPreferences systemBondedPreferences() {
        return PluginPrefs.get(pluginContext, PREFS_NAME);
    }

    private void persistConnectionMode(ConnectionModeController.Mode mode) {
        systemBondedPreferences().edit().putString("connection_mode", mode.value)
                .putBoolean(KEY_SYSTEM_BONDED_ENABLED, mode == ConnectionModeController.Mode.SAMSUNG)
                .apply();
    }

    private void selectConnectionMode(ConnectionModeController.Mode mode) {
        if (gatewayPurpose && mode != ConnectionModeController.Mode.TRADITIONAL) {
            Toast.makeText(MapView.getMapView().getContext(),
                    "Multiwatch Gateway requires BLE. Choose Bond Single Watch or Configure Single Watch first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (setupPurpose && mode == ConnectionModeController.Mode.GARMIN) {
            Toast.makeText(MapView.getMapView().getContext(),
                    "Configure Single Watch requires BLE or Samsung Wearable.", Toast.LENGTH_LONG).show();
            return;
        }
        persistConnectionMode(mode);
        connectionModes.select(mode);
        updateConnectionModeUi();
    }

    private void updateConnectionModeUi() {
        ConnectionModeController.Mode mode = connectionModes.getMode();
        if (connectionModeButton == null) return;
        connectionModeButton.setText(mode == ConnectionModeController.Mode.TRADITIONAL
                ? "BLE" : mode == ConnectionModeController.Mode.GARMIN ? "Garmin Connect" : "Samsung Wearable");
        connectionModeButton.setEnabled(!connectionModes.isPairingLocked());
        connectionModeButton.setAlpha(connectionModes.isPairingLocked() ? 0.4f : 1f);
        paneView.findViewById(R.id.gatewayScroll).setVisibility(gatewayPurpose ? View.VISIBLE : View.GONE);
        updateLocationDiagnosticsUi();
        if (purposeButton != null) purposeButton.setText(
                gatewayPurpose ? "Multiwatch Gateway"
                        : setupPurpose ? "Configure Single Watch" : "Bond Single Watch");
        if (gatewayPurpose) {
            paneView.findViewById(R.id.bleStatusRow).setVisibility(View.GONE);
            paneView.findViewById(R.id.systemBondedPanel).setVisibility(View.GONE);
            paneView.findViewById(R.id.garminPanel).setVisibility(View.GONE);
            deviceList.setVisibility(View.GONE);
            emptyState.setVisibility(View.GONE);
            bleSettingsButton.setEnabled(false);
            if (gateway != null) gateway.bind(paneView.findViewById(R.id.gatewayPanel));
            return;
        }
        paneView.findViewById(R.id.bleStatusRow).setVisibility(
                mode == ConnectionModeController.Mode.TRADITIONAL ? View.VISIBLE : View.GONE);
        connectionStatusTV.setVisibility(
                mode == ConnectionModeController.Mode.SAMSUNG ? View.GONE : View.VISIBLE);
        scanButton.setVisibility(mode == ConnectionModeController.Mode.TRADITIONAL ? View.VISIBLE : View.GONE);
        deviceList.setVisibility(mode == ConnectionModeController.Mode.TRADITIONAL ? View.VISIBLE : View.GONE);
        if (mode != ConnectionModeController.Mode.TRADITIONAL) {
            emptyState.setVisibility(View.GONE);
        }
        paneView.findViewById(R.id.systemBondedPanel).setVisibility(
                mode == ConnectionModeController.Mode.SAMSUNG ? View.VISIBLE : View.GONE);
        paneView.findViewById(R.id.garminPanel).setVisibility(
                mode == ConnectionModeController.Mode.GARMIN ? View.VISIBLE : View.GONE);
        if (garminSettingsButton != null) {
            garminSettingsButton.setVisibility(setupPurpose
                    && mode == ConnectionModeController.Mode.GARMIN ? View.VISIBLE : View.GONE);
            garminSettingsButton.setEnabled(setupPurpose && mode == ConnectionModeController.Mode.GARMIN
                    && garminClient != null && currentGarminBloodhoundLink(garminClient) != null);
            garminSettingsButton.setAlpha(garminSettingsButton.isEnabled() ? 1f : 0.4f);
        }
        boolean bleReady = mode == ConnectionModeController.Mode.TRADITIONAL
                && bleClient != null && bleClient.isConnected();
        if (bleSettingsButton != null) {
            bleSettingsButton.setVisibility(View.GONE);
            bleSettingsButton.setEnabled(setupPurpose && bleReady);
            bleSettingsButton.setAlpha(setupPurpose && bleReady ? 1f : 0.4f);
        }
        if (systemBondedSettingsButton != null) {
            systemBondedSettingsButton.setVisibility(setupPurpose
                    && mode == ConnectionModeController.Mode.SAMSUNG ? View.VISIBLE : View.GONE);
        }
        if (deviceAdapter != null) deviceAdapter.setSettingsVisible(setupPurpose);
        if (connectedCardView != null && selectedDevice != null) {
            bindConnectedCard(connectedCardView, selectedDevice);
        }
        if (mode == ConnectionModeController.Mode.TRADITIONAL) {
            scanButton.setText(managementRelease.isPending() ? "Wait" : bleReady ? "Disconnect" : "Scan");
            scanButton.setEnabled(!managementRelease.isPending());
        }
        if (mode == ConnectionModeController.Mode.GARMIN && garminClient != null) {
            renderGarminConnection(garminClient.isConnected());
        }
        if (connectionStatusTV != null) {
            setConnectionStatus(mode == ConnectionModeController.Mode.GARMIN
                    ? garminClient != null ? garminClient.getStatus() : "Garmin Connect IQ is off."
                    : bleClient != null && bleClient.isConnected()
                    ? "CONNECTED" : systemBondedEnabled ? systemBondedConnection.getStatus() : "NOT CONNECTED");
        }
        updateSystemBondedControl();
    }

    private void showConnectionModeChooser() {
        if (connectionModes.isPairingLocked()) return;
        ConnectionModeController.Mode[] modes = {
                ConnectionModeController.Mode.TRADITIONAL,
                ConnectionModeController.Mode.GARMIN,
                ConnectionModeController.Mode.SAMSUNG
        };
        String[] labels = {"BLE", "Garmin Connect", "Samsung Wearable"};
        int selected = 0;
        for (int i = 0; i < modes.length; ++i) {
            if (modes[i] == connectionModes.getMode()) selected = i;
        }
        new AlertDialog.Builder(MapView.getMapView().getContext()).setTitle("Pairing Mode")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    selectConnectionMode(modes[which]);
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showPurposeChooser() {
        Context host = MapView.getMapView().getContext();
        String[] labels = {"Bond Single Watch", "Configure Single Watch", "Multiwatch Gateway"};
        ArrayAdapter<String> choices = new ArrayAdapter<String>(
                host, android.R.layout.simple_list_item_single_choice, labels) {
            @Override public boolean areAllItemsEnabled() { return true; }
            @Override public boolean isEnabled(int position) { return true; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                view.setEnabled(isEnabled(position));
                view.setAlpha(isEnabled(position) ? 1f : 0.4f);
                return view;
            }
        };
        new AlertDialog.Builder(host).setTitle("Purpose")
                .setSingleChoiceItems(choices, gatewayPurpose ? 2 : setupPurpose ? 1 : 0, (dialog, which) -> {
                    if (which == 1 && connectionModes.getMode() == ConnectionModeController.Mode.GARMIN) {
                        Toast.makeText(host, "Select BLE or Samsung Wearable before Configure Single Watch.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    setupPurpose = which == 1;
                    gatewayPurpose = which == 2;
                    stopLocationDiagnostics("companion purpose changed");
                    phoneManagedSettingsRequested =
                            CompanionSettingsPolicy.phoneManaged(setupPurpose, gatewayPurpose);
                    if (phoneManagedSettingsCB != null) {
                        phoneManagedSettingsCB.setChecked(phoneManagedSettingsRequested);
                    }
                    systemBondedPreferences().edit().putBoolean("setup_purpose", setupPurpose)
                            .putString("purpose", gatewayPurpose ? "gateway" : setupPurpose ? "setup" : "bond").apply();
                    if (gatewayPurpose) persistConnectionMode(ConnectionModeController.Mode.TRADITIONAL);
                    configureWatchChatEchoSuppression(!gatewayPurpose);
                    connectionModes.changePurpose(gatewayPurpose);
                    purposeButton.setText(gatewayPurpose ? "Multiwatch Gateway"
                            : setupPurpose ? "Configure Single Watch" : "Bond Single Watch");
                    showDeviceScreen();
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void disableSystemBondedForManualUse() {
        selectConnectionMode(ConnectionModeController.Mode.TRADITIONAL);
    }

    private void updateSystemBondedControl() {
        if (systemBondedWatchStatus == null || systemBondedConnection == null) return;
        SystemBondedWatchConnection.State state = systemBondedConnection.getState();
        boolean noWatch = state == SystemBondedWatchConnection.State.NOT_FOUND;
        boolean connected = state == SystemBondedWatchConnection.State.CONNECTED
                && bleClient != null && bleClient.isConnected();
        systemBondedWatchStatus.setText(connected ? singleWatchConnectionStatus()
                : state == SystemBondedWatchConnection.State.BLUETOOTH_OFF ? "BLUETOOTH OFF"
                : state == SystemBondedWatchConnection.State.CONNECTING ? "CONNECTING" : "NOT CONNECTED");
        styleConnectionStatus(systemBondedWatchStatus);
        systemBondedConnectButton.setText(noWatch ? "Open"
                : managementRelease.isPending() ? "Wait"
                : systemBondedConnection.isActive() ? "Disconnect" : "Connect");
        systemBondedConnectButton.setEnabled(!managementRelease.isPending());
        systemBondedSettingsButton.setEnabled(
                setupPurpose && systemBondedConnection.getState() == SystemBondedWatchConnection.State.CONNECTED
                        && bleClient != null && bleClient.isConnected());
        systemBondedSettingsButton.setAlpha(systemBondedSettingsButton.isEnabled() ? 1f : 0.4f);
        refreshIdentityStatus();
    }

    private void setConnectionStatus(String status) {
        if (connectionStatusTV == null) return;
        connectionStatusTV.setText("CONNECTED".equals(status) ? singleWatchConnectionStatus() : status);
        styleConnectionStatus(connectionStatusTV);
        refreshIdentityStatus();
    }

    private String panelStatusText() {
        TextView chip = connectionModes.getMode() == ConnectionModeController.Mode.SAMSUNG
                ? systemBondedWatchStatus : connectionStatusTV;
        return chip == null || chip.getText() == null ? null : chip.getText().toString();
    }

    private void refreshIdentityStatus() {
        if (deviceAdapter != null) deviceAdapter.setPanelStatus(panelStatusText());
        if (connectedCardView != null && selectedDevice != null)
            bindConnectedCard(connectedCardView, selectedDevice);
        if (systemBondedIdentity != null) {
            WearTakBleClient.DiscoveredDevice candidate = systemBondedConnection == null
                    ? null : systemBondedConnection.getCandidate();
            if (candidate != null && bleClient != null) candidate = bleClient.withSessionIdentity(candidate);
            systemBondedIdentity.setText(BleDeviceAdapter.identityLines(systemBondedIdentity.getResources(),
                    candidate, getPhoneCallsign(),
                    systemBondedWatchStatus == null ? null : systemBondedWatchStatus.getText().toString(),
                    gatewayPurpose));
        }
        renderGarminIdentities();
    }

    private void renderGarminIdentities() {
        if (garminIdentityRows == null) return;
        garminIdentityRows.removeAllViews();
        List<GarminConnectIqClient.WatchIdentity> identities = garminClient == null
                ? Collections.emptyList() : garminClient.watchIdentities();
        if (identities.isEmpty()) {
            addGarminIdentity(null);
        } else {
            for (GarminConnectIqClient.WatchIdentity identity : identities) addGarminIdentity(identity);
        }
    }

    private void addGarminIdentity(GarminConnectIqClient.WatchIdentity identity) {
        TextView row = new TextView(garminIdentityRows.getContext());
        row.setTextSize(14);
        row.setTypeface(null, android.graphics.Typeface.BOLD);
        int padding = Math.round(12 * row.getResources().getDisplayMetrics().density);
        row.setPadding(padding, padding, padding, padding);
        String status = garminConnectionBadgeTV == null ? null : garminConnectionBadgeTV.getText().toString();
        if (identity != null && !identity.connected && !"PAUSED".equals(status)
                && !"OFF".equals(status)) status = "NOT CONNECTED";
        row.setText(BleDeviceAdapter.identityLines(row.getResources(), getPhoneCallsign(), status,
                null, 0, identity == null ? null : identity.callsign,
                identity == null ? null : identity.product, gatewayPurpose));
        garminIdentityRows.addView(row);
    }

    private String singleWatchConnectionStatus() {
        if (phoneSettingsHandshake.isPending()) return "CONNECTED - SYNCING";
        if (phoneSettingsHandshake.isFailed()) return "CONNECTED - SYNC FAILED";
        return "CONNECTED";
    }

    private void cancelPhoneSettingsHandshake() {
        phoneSettingsHandshake.cancel();
        if (phoneSettingsTimeout != null) mainHandler.removeCallbacks(phoneSettingsTimeout);
        phoneSettingsTimeout = null;
        phoneSettingsRetryJson = null;
    }

    private void startPhoneSettingsHandshake() {
        cancelPhoneSettingsHandshake();
        handlePhoneSettingsAction(phoneSettingsHandshake.begin(SystemClock.elapsedRealtime()));
    }

    private void handlePhoneSettingsAction(PhoneSettingsHandshake.Action action) {
        if (action == PhoneSettingsHandshake.Action.NONE) return;
        if (phoneSettingsTimeout != null) mainHandler.removeCallbacks(phoneSettingsTimeout);
        phoneSettingsTimeout = null;
        if (action == PhoneSettingsHandshake.Action.REQUEST) {
            requestSettingsFromWatch("management handshake");
        } else if (action == PhoneSettingsHandshake.Action.APPLY) {
            if (phoneSettingsRetryJson == null) {
                phoneSettingsPushedThisConnection = sendSettingsToWatch();
            } else if (bleClient != null) {
                phoneSettingsPushedThisConnection = bleClient.writeJsonLineToWatch(phoneSettingsRetryJson);
            }
        } else if (action == PhoneSettingsHandshake.Action.FAILED) {
            String message = "Watch management sync failed. Refresh settings or reconnect to retry.";
            LogX.e(TAG, message);
            if (settingsStatusTV != null) settingsStatusTV.setText(message);
            Toast.makeText(MapView.getMapView().getContext(), message, Toast.LENGTH_LONG).show();
        } else if (action == PhoneSettingsHandshake.Action.CONFIRMED) {
            LogX.i(TAG, "Watch phone management confirmed by acknowledgement and settings snapshot");
            if (settingsStatusTV != null) settingsStatusTV.setText("Phone management confirmed on watch.");
            phoneSettingsRetryJson = null;
            mainHandler.post(liveIdentityUpdate);
        }
        setConnectionStatus("CONNECTED");
        updateSystemBondedControl();
        updateSettingsSendState();
        if (connectedCardView != null) {
            TextView state = connectedCardView.findViewById(R.id.deviceState);
            if (state != null) state.setText(singleWatchConnectionStatus());
        }
        if (!phoneSettingsHandshake.isPending()) return;
        final long attempt = phoneSettingsHandshake.token();
        final long settingsToken = settingsSession.token();
        final WearTakBleClient owner = bleClient;
        final long session = owner == null ? -1 : owner.getReadySession();
        phoneSettingsTimeout = () -> {
            if (!phoneSettingsHandshake.isCurrent(attempt)
                    || !settingsSession.isCurrent(settingsToken)
                    || managementRelease.isPending() || owner == null || owner != bleClient
                    || session < 0 || owner.getReadySession() != session) return;
            handlePhoneSettingsAction(phoneSettingsHandshake.timeout(attempt, SystemClock.elapsedRealtime()));
        };
        mainHandler.postDelayed(phoneSettingsTimeout, PhoneSettingsHandshake.TIMEOUT_MILLIS);
    }

    private void handleLocationDiagnosticsEnvelope(JSONObject envelope, WearTakBleClient owner,
                                                   long bleSession) {
        String type = envelope.optString("msgType", envelope.optString("msg_type", ""));
        String session = envelope.optString("session", null);
        if (!LocationDiagnosticsProtocol.isValidSession(session)) {
            LogX.w(TAG, "Ignoring diagnostics message with invalid session token");
            return;
        }
        if (LocationDiagnosticsProtocol.HELLO.equals(type)) {
            if (owner != bleClient || bleSession != owner.getReadySession() ||
                    !owner.isBondedConnected() ||
                    connectionModes.getMode() != ConnectionModeController.Mode.TRADITIONAL ||
                    setupPurpose || gatewayPurpose) return;
            JSONObject valid = LocationDiagnosticsProtocol.parse(
                    envelope, LocationDiagnosticsProtocol.HELLO, session);
            if (valid == null) {
                LogX.w(TAG, "Ignoring invalid bonded diagnostics capability request");
                return;
            }
            startPhoneLocationDiagnostics(owner, bleSession, session);
            return;
        }
        if ((!LocationDiagnosticsProtocol.SNAPSHOT.equals(type) &&
                !LocationDiagnosticsProtocol.STOP.equals(type)) ||
                owner != bleClient || bleSession != owner.getReadySession() ||
                !owner.isBondedConnected() || setupPurpose || gatewayPurpose ||
                connectionModes.getMode() != ConnectionModeController.Mode.TRADITIONAL) return;
        if (LocationDiagnosticsProtocol.STOP.equals(type)) {
            if (locationDiagnostics.stopForSession(envelope, bleSession)) {
                watchLocationDiagnostics = null;
                LogX.i(TAG, "Bonded location diagnostics stopped: watch disabled diagnostics");
                updateLocationDiagnosticsUi();
            } else {
                LogX.w(TAG, "Ignoring invalid or stale watch location diagnostics stop");
            }
            return;
        }
        String activeSession = locationDiagnostics.session();
        if (activeSession == null) return;
        JSONObject valid = LocationDiagnosticsProtocol.parse(
                envelope, type, activeSession);
        if (valid == null || (LocationDiagnosticsProtocol.SNAPSHOT.equals(type) &&
                valid.optJSONObject("payload") == null)) {
            LogX.w(TAG, "Ignoring invalid or stale watch location diagnostics snapshot");
            return;
        }
        watchLocationDiagnostics = valid.optJSONObject("payload");
        updateLocationDiagnosticsUi();
    }

    private void startPhoneLocationDiagnostics(WearTakBleClient owner, long bleSession,
                                               String diagnosticSession) {
        startPhoneLocationDiagnostics(owner, bleSession, diagnosticSession, 0);
    }

    private void startPhoneLocationDiagnostics(WearTakBleClient owner, long bleSession,
                                               String diagnosticSession, int retry) {
        if (diagnosticSession.equals(locationDiagnostics.session()) &&
                bleSession == locationDiagnostics.readySession()) return;
        final JSONObject capability;
        try {
            capability = LocationDiagnosticsProtocol.capability(diagnosticSession, true);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            LogX.e(TAG, "Unable to build diagnostics capability", error);
            return;
        }
        if (!owner.writeDiagnosticJsonLineToWatch(capability.toString(), bleSession)) {
            LogX.i(TAG, "Diagnostics capability deferred while operational BLE traffic is active");
            if (retry == 0) {
                mainHandler.postDelayed(() -> {
                    if (owner == bleClient && bleSession == owner.getReadySession() &&
                            owner.isBondedConnected() && !setupPurpose && !gatewayPurpose &&
                            connectionModes.getMode() == ConnectionModeController.Mode.TRADITIONAL) {
                        startPhoneLocationDiagnostics(owner, bleSession, diagnosticSession, 1);
                    }
                }, 1500L);
            } else {
                LogX.w(TAG, "Dropping diagnostics capability after bounded operational-traffic deferral");
            }
            return;
        }
        watchLocationDiagnostics = null;
        locationDiagnostics.start(MapView.getMapView().getContext(), diagnosticSession, bleSession, snapshot -> {
            if (owner != bleClient || owner.getReadySession() != bleSession ||
                    !owner.isBondedConnected() || setupPurpose || gatewayPurpose ||
                    !diagnosticSession.equals(locationDiagnostics.session())) return;
            if (!owner.writeDiagnosticJsonLineToWatch(snapshot.toString(), bleSession)) {
                LogX.i(TAG, "Dropping bounded diagnostics sample while operational BLE traffic is queued");
            }
            updateLocationDiagnosticsUi();
        });
        updateLocationDiagnosticsUi();
    }

    private void stopLocationDiagnostics(String reason) {
        if (locationDiagnostics.session() == null) return;
        locationDiagnostics.stop();
        watchLocationDiagnostics = null;
        LogX.i(TAG, "Bonded location diagnostics stopped: " + reason);
        updateLocationDiagnosticsUi();
    }

    private void updateLocationDiagnosticsUi() {
        if (locationDiagnosticsStatus == null) return;
        boolean eligible = !setupPurpose && !gatewayPurpose &&
                connectionModes.getMode() == ConnectionModeController.Mode.TRADITIONAL;
        if (!eligible || locationDiagnostics.session() == null) {
            locationDiagnosticsStatus.setVisibility(View.GONE);
            return;
        }
        JSONObject phone = locationDiagnostics.payloadSnapshot();
        String phoneLine = formatDiagnosticsSample("Phone", phone);
        String watchLine = watchLocationDiagnostics == null
                ? "Watch: waiting for observation" : formatDiagnosticsSample("Watch", watchLocationDiagnostics);
        locationDiagnosticsStatus.setText("Bonded Location Diagnostics\n" + phoneLine + "\n" + watchLine);
        locationDiagnosticsStatus.setVisibility(View.VISIBLE);
    }

    private String formatDiagnosticsSample(String label, JSONObject sample) {
        String quality = LocationDiagnosticsProtocol.quality(sample);
        String reason = sample.optString("error", sample.optString("reason", "GNSS observation unknown"));
        String age = sample.isNull("ageMs") ? "unknown" : sample.optLong("ageMs") / 1000L + "s";
        String accuracy = sample.isNull("accuracyM") ? "unknown"
                : String.format(java.util.Locale.ROOT, "%.1f m", sample.optDouble("accuracyM"));
        String sats = sample.optBoolean("satelliteStatusSupported", false)
                ? sample.optInt("used", 0) + "/" + sample.optInt("satellites", 0) + " used" : "unknown";
        return label + ": " + quality + " (" + reason + "); age " + age +
                "; accuracy " + accuracy + "; satellites " + sats;
    }

    static void styleConnectionStatus(TextView status) {
        String text = status.getText().toString();
        boolean notConnected = "NOT CONNECTED".equals(text) || "BLUETOOTH OFF".equals(text);
        status.setBackgroundResource(notConnected ? R.drawable.bg_chip_not_connected
                : "CONNECTED".equals(text) ? R.drawable.bg_chip_success
                : "NOT INSTALLED".equals(text) || "Garmin Connect Not Found".equals(text)
                ? R.drawable.bg_chip_error : R.drawable.bg_chip_neutral);
        status.setTextColor(notConnected ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
    }

    private void toggleWearableConnection() {
        if (connectionModes.getMode() != ConnectionModeController.Mode.SAMSUNG
                || systemBondedConnection == null) return;
        if (systemBondedConnection.getState() == SystemBondedWatchConnection.State.NOT_FOUND) {
            openCompanionApp(SAMSUNG_WEARABLE_PACKAGE, "Samsung Wearable");
            return;
        }
        boolean pause = systemBondedConnection.isActive();
        if (pause) {
            if (bleClient != null && bleClient.getReadySession() >= 0) {
                requestBleManagementRelease();
            } else {
                systemBondedPreferences().edit().putBoolean(KEY_WEARABLE_PAUSED, true).apply();
                systemBondedConnection.pause();
                clearSessionSettings();
                releaseNotice("Samsung route paused without unlock confirmation: no ready watch link. "
                        + "Watch settings may remain locked; reconnect or unpair on the watch.");
            }
        } else {
            systemBondedPreferences().edit().putBoolean(KEY_WEARABLE_PAUSED, false).apply();
            systemBondedConnection.start();
        }
        updateSystemBondedControl();
    }

    private void openCompanionApp(String packageName, String appName) {
        Context host = MapView.getMapView().getContext();
        Intent launch = host.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            if (SAMSUNG_WEARABLE_PACKAGE.equals(packageName) && isSamsungInstalledPreview()) {
                LogX.i(TAG, "Samsung Wearable installed-state preview; app is not installed");
                Toast.makeText(host, "Emulator preview only: Samsung Wearable is not installed.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (SAMSUNG_WEARABLE_PACKAGE.equals(packageName)) {
                // An explicit activity can launch even when ATAK cannot query the package.
                launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(packageName,
                                packageName + ".setupwizard.SetupWizardWelcomeActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } else {
                LogX.w(TAG, appName + " has no available launch activity");
                Toast.makeText(host, "Unable to open " + appName + ". Opening store listing.",
                        Toast.LENGTH_LONG).show();
                openCompanionAppListing(packageName);
                return;
            }
        }
        try {
            host.startActivity(launch);
        } catch (ActivityNotFoundException | SecurityException e) {
            LogX.e(TAG, "Unable to launch " + appName, e);
            Toast.makeText(host, "Unable to open " + appName + ". Opening store listing.",
                    Toast.LENGTH_LONG).show();
            openCompanionAppListing(packageName);
        }
    }

    private boolean isCompanionAppInstalled(String packageName) {
        if (SAMSUNG_WEARABLE_PACKAGE.equals(packageName) && isSamsungInstalledPreview()) {
            return true;
        }
        try {
            MapView.getMapView().getContext().getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isSamsungInstalledPreview() {
        return SamsungWearablePreview.isEnabled(BuildConfig.DEBUG, Build.HARDWARE,
                new File(MapView.getMapView().getContext().getFilesDir(),
                        "weartak-preview-samsung-installed").isFile());
    }

    private void openCompanionAppListing(String packageName) {
        Context host = MapView.getMapView().getContext();
        Intent store = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName));
        store.setPackage("com.android.vending");
        try {
            host.startActivity(store);
        } catch (ActivityNotFoundException e) {
            LogX.i(TAG, "Play Store unavailable; opening web listing for " + packageName);
            try {
                host.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
            } catch (ActivityNotFoundException noBrowser) {
                LogX.e(TAG, "No application can open the store listing for " + packageName, noBrowser);
                Toast.makeText(host, "No Play Store or browser available to open the app listing.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == mainHandler.getLooper()) action.run();
        else mainHandler.post(action);
    }

    @Override
    public void onStop() {
        runOnMain(() -> {
            identityObserverActive = false;
            receivedCotTiming.stop();
            phoneIdentityObserver.stop();
            mainHandler.removeCallbacks(liveIdentityUpdate);
            simulatorDiagnostics.stop();
            configureWatchChatEchoSuppression(false);
            connectionModes.stop();
            if (diagnosticLogger != null) {
                LogX.detach(diagnosticLogger);
                diagnosticLogger.shutdown();
                diagnosticLogger = null;
            }
        });
    }

    private void initializeDiagnosticLogger() {
        if (diagnosticLogger != null) return;
        diagnosticLogger = new CompanionDiagnosticLogger(() -> {
            File root = FileSystemUtils.getRoot();
            if (root == null) throw new IOException("ATAK storage is unavailable");
            return new File(root, "companion-logs");
        }, error -> {
            LogX.e(TAG, "Companion diagnostic capture stopped: " + error.getMessage(), error);
            mainHandler.post(() -> Toast.makeText(MapView.getMapView().getContext(),
                    pluginContext.getString(R.string.log_capture_problem, error.getMessage()),
                    Toast.LENGTH_LONG).show());
        });
        LogX.attach(diagnosticLogger);
        updateLoggingButton();
    }

    private String diagnosticMetadata() {
        Context host = MapView.getMapView().getContext();
        try {
            String atakVersion = host.getPackageManager()
                    .getPackageInfo(host.getPackageName(), 0).versionName;
            return "Plugin version: " + BuildConfig.VERSION_NAME
                    + "\nATAK version: " + atakVersion
                    + "\nDevice model: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "\nAndroid version: " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ")";
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Cannot read ATAK version", error);
        }
    }

    private void onLoggingClicked() {
        CompanionDiagnosticLogger logger = diagnosticLogger;
        if (logger == null || logger.isBusy()) return;
        loggingButton.setEnabled(false);
        if (logger.isLogging()) {
            loggingButton.setText(R.string.finalizing_log);
            loggingButton.setContentDescription(pluginContext.getString(R.string.finishing_log_description));
            logger.stop((file, error) -> {
                MissionPackageManifest manifest = null;
                IOException failure = error;
                if (failure == null) {
                    try {
                        if (file == null || !file.isFile())
                            throw new IOException("Companion log file is unavailable");
                        manifest = MissionPackageApi.CreateTempManifest("Companion Log " + file.getName());
                        if (manifest == null || !manifest.addFile(file, "text/plain", null))
                            throw new IOException("Cannot add Companion log to ATAK data package");
                    } catch (IOException | RuntimeException problem) {
                        failure = new IOException("Cannot prepare Companion data package", problem);
                    }
                }
                MissionPackageManifest prepared = manifest;
                IOException problem = failure;
                mainHandler.post(() -> {
                    if (diagnosticLogger != logger) return;
                    updateLoggingButton();
                    if (problem != null) {
                        showLogError(problem);
                        return;
                    }
                    try {
                        if (!MissionPackageApi.Send(MapView.getMapView().getContext(),
                                prepared, ToastSaveCallback.class, null))
                            showLogError(new IOException("Cannot start ATAK data package send"));
                    } catch (RuntimeException sendError) {
                        showLogError(sendError);
                    }
                });
            });
        } else {
            loggingButton.setText(R.string.starting_log);
            logger.start(this::diagnosticMetadata, (file, error) -> mainHandler.post(() -> {
                if (diagnosticLogger != logger) return;
                updateLoggingButton();
                if (error != null) showLogError(error);
                else {
                    LogX.i(TAG, "Companion diagnostic capture started");
                    Toast.makeText(MapView.getMapView().getContext(),
                            pluginContext.getString(R.string.log_started), Toast.LENGTH_LONG).show();
                }
            }));
        }
    }

    private void updateLoggingButton() {
        if (loggingButton == null) return;
        boolean logging = diagnosticLogger != null && diagnosticLogger.isLogging();
        loggingButton.setEnabled(diagnosticLogger != null && !diagnosticLogger.isBusy());
        loggingButton.setText(logging ? R.string.stop_and_send_log : R.string.start_logging);
        loggingButton.setContentDescription(pluginContext.getString(logging
                ? R.string.send_log_description : R.string.start_log_description));
    }

    private void showLogError(Exception error) {
        LogX.e(TAG, "Companion diagnostic log failed", error);
        Toast.makeText(MapView.getMapView().getContext(),
                pluginContext.getString(R.string.log_capture_problem, error.getMessage()),
                Toast.LENGTH_LONG).show();
    }

    private void startGarmin(final long token) {
            if (systemBondedPreferences().getBoolean(KEY_GARMIN_PAUSED, false)) {
                garminEnabled = false;
                renderGarminConnection(false);
                return;
            }
            garminEnabled = true;
                garminClient = new GarminConnectIqClient(pluginContext,
                        new GarminConnectIqClient.Listener() {
                            @Override
                            public void onStatusChanged(String status) {
                                mainHandler.post(() -> {
                                    if (!connectionModes.isCurrent(token)) return;
                                    if (garminConnectionStatusTV != null) {
                                        garminConnectionStatusTV.setText(status);
                                    }
                                    renderGarminConnection(garminClient.isConnected());
                                    if (connectionStatusTV != null) connectionStatusTV.setText(status);
                                });
                            }

                            @Override
                            public void onConnectionChanged(boolean connected) {
                                mainHandler.post(() -> {
                                    if (!connectionModes.isCurrent(token)) return;
                                    if (!connected && bloodhoundBridge != null) {
                                        bloodhoundBridge.disconnected();
                                    }
                                    renderGarminConnection(connected);
                                    if (garminEntities != null) garminEntities.refreshLinks();
                                    if (garminRoutes != null) garminRoutes.refreshLinks();
                                    refreshGarminSettingsLink();
                                    if (managementRelease.isPending() && releaseCurrent != null
                                            && !releaseCurrent.getAsBoolean()) {
                                        failManagementRelease("The original Garmin watch session changed.");
                                    }
                                    updateSettingsSendState();
                                    updateConnectionModeUi();
                                });
                            }

                            @Override
                            public void onMessageReceived(JSONObject envelope) {
                                // SDK ingress uses the link-scoped overload below.
                            }

                            @Override
                            public void onMessageReceived(JSONObject envelope, GarminEntityForwarder.Link link) {
                                mainHandler.post(() -> {
                                    if (connectionModes.isCurrent(token) && link != null && link.isCurrent()) {
                                        handleGarminMessage(envelope, link);
                                        renderGarminIdentities();
                                    }
                                });
                            }
                        });
            renderGarminConnecting();
            bleCotBridge.startChatForwarding(garminClient);
            GarminConnectIqClient owner = garminClient;
            bloodhoundBridge = new BloodhoundBridge(MapView.getMapView(),
                    new BloodhoundBridge.Transport() {
                        @Override public boolean connected() {
                            return currentGarminBloodhoundLink(token, owner) != null;
                        }

                        @Override public boolean send(JSONObject envelope) {
                            GarminEntityForwarder.Link link =
                                    currentGarminBloodhoundLink(token, owner);
                            if (link == null) return false;
                            try {
                                return link.send(GarminBloodhoundProtocol.outboundType(envelope),
                                        GarminBloodhoundProtocol.outboundPayload(envelope));
                            } catch (IllegalArgumentException e) {
                                LogX.w(TAG, "Garmin Bloodhound message rejected", e);
                                return false;
                            }
                        }
                    }, true);
            garminEntitySource = new GarminMapEntitySource(MapView.getMapView(), bleCotBridge, receivedCotTiming);
            garminEntities = new GarminEntityForwarder(garminEntitySource, owner::entityLinks,
                    runnable -> mainHandler.post(runnable),
                    () -> garminEnabled && connectionModes.isCurrent(token) && garminClient == owner);
            garminEntities.start();
            garminRouteSource = new GarminRouteMapSource(MapView.getMapView());
            garminRoutes = new GarminRouteForwarder(garminRouteSource, owner::entityLinks,
                    runnable -> mainHandler.post(runnable),
                    () -> garminEnabled && connectionModes.isCurrent(token) && garminClient == owner);
            garminRoutes.start();
            garminRouteNavigationTransport = new GarminRouteNavigationTransport(
                    () -> currentGarminBloodhoundLink(token, owner), garminRoutes);
            phoneRouteNavigation = new PhoneRouteNavigationBridge(garminRouteNavigationTransport);
            startPhoneAlertForwarding(token);
            garminClient.start();
    }

    private GarminEntityForwarder.Link currentGarminBloodhoundLink(
            long token, GarminConnectIqClient owner) {
        if (!connectionModes.isCurrent(token)
                || connectionModes.getMode() != ConnectionModeController.Mode.GARMIN
                || !garminEnabled || garminClient != owner) return null;
        List<GarminEntityForwarder.Link> links = owner.entityLinks();
        return links.size() == 1 && links.get(0).isCurrent() ? links.get(0) : null;
    }

    private boolean isCurrentGarminBloodhoundLink(
            long token, GarminConnectIqClient owner, GarminEntityForwarder.Link link) {
        return link != null && link.isCurrent()
                && currentGarminBloodhoundLink(token, owner) == link;
    }

    private boolean isCurrentGarminBloodhoundLink(GarminEntityForwarder.Link link) {
        GarminConnectIqClient owner = garminClient;
        return link != null && link.isCurrent()
                && connectionModes.getMode() == ConnectionModeController.Mode.GARMIN
                && garminEnabled && owner != null
                && currentGarminBloodhoundLink(owner) == link;
    }

    private GarminEntityForwarder.Link currentGarminBloodhoundLink(GarminConnectIqClient owner) {
        if (connectionModes.getMode() != ConnectionModeController.Mode.GARMIN
                || !garminEnabled || garminClient != owner) return null;
        List<GarminEntityForwarder.Link> links = owner.entityLinks();
        return links.size() == 1 && links.get(0).isCurrent() ? links.get(0) : null;
    }

    private boolean isGarminConnectMissing() {
        return !isCompanionAppInstalled(GARMIN_CONNECT_PACKAGE)
                || garminClient != null && garminClient.getStatus().contains("GCM_NOT_INSTALLED");
    }

    private void renderGarminConnection(boolean connected) {
        if (garminConnectionBadgeTV == null) return;
        boolean paused = systemBondedPreferences().getBoolean(KEY_GARMIN_PAUSED, false);
        boolean connectMobileMissing = isGarminConnectMissing();
        garminConnectionBadgeTV.setText(connectMobileMissing
                ? "Garmin Connect Not Found"
                : paused ? "PAUSED" : connected ? "CONNECTED" : "NOT CONNECTED");
        styleConnectionStatus(garminConnectionBadgeTV);
        if (garminToggleButton != null) {
            garminToggleButton.setText(connectMobileMissing ? "Install"
                    : managementRelease.isPending() ? "Wait"
                    : paused ? "Resume" : connected ? "Pause" : "Open");
            garminToggleButton.setEnabled(!managementRelease.isPending());
        }
        if (garminConnectionStatusTV != null) {
            garminConnectionStatusTV.setVisibility(View.GONE);
        }
        renderGarminIdentities();
    }

    private void renderGarminConnecting() {
        if (garminConnectionBadgeTV == null) return;
        garminConnectionBadgeTV.setText("CONNECTING");
        garminConnectionBadgeTV.setTextColor(android.graphics.Color.WHITE);
        garminConnectionBadgeTV.setBackgroundResource(R.drawable.bg_chip_warning);
        renderGarminIdentities();
    }

    private void renderGarminOff() {
        if (garminConnectionBadgeTV == null) return;
        garminConnectionBadgeTV.setText("OFF");
        garminConnectionBadgeTV.setTextColor(android.graphics.Color.WHITE);
        garminConnectionBadgeTV.setBackgroundResource(R.drawable.bg_chip_neutral);
        renderGarminIdentities();
    }

    private void refreshGarminSettingsLink() {
        List<GarminEntityForwarder.Link> links = garminClient == null
                ? Collections.emptyList() : garminClient.entityLinks();
        GarminEntityForwarder.Link next = links.size() == 1 && links.get(0).isCurrent()
                ? links.get(0) : null;
        if (garminSettingsLink != next) {
            if (bloodhoundBridge != null
                    && connectionModes.getMode() == ConnectionModeController.Mode.GARMIN) {
                bloodhoundBridge.disconnected();
            }
            garminSettingsTransaction.cancel();
            garminSettingsDraftAtSend = null;
            garminSettingsLink = next;
            garminSettingsSnapshotLoaded = false;
            lastSettingsApplyResult = null;
            phoneSettingsPushedThisConnection = false;
            if (settingsStatusTV != null && settingsScreenRoot != null
                    && settingsScreenRoot.getVisibility() == View.VISIBLE) {
                settingsStatusTV.setText(next == null
                        ? "Garmin settings need exactly one connected WearTAK watch."
                        : "Requesting settings from Garmin watch...");
            }
        }
        if (next == null && links.size() > 1 && garminConnectionStatusTV != null) {
            garminConnectionStatusTV.setText("Connect only one Garmin watch to manage its settings.");
        }
    }

    private void openGarminSettings() {
        refreshGarminSettingsLink();
        if (garminSettingsLink == null || !garminSettingsLink.isCurrent()) {
            if (garminConnectionStatusTV != null) {
                garminConnectionStatusTV.setText("Settings require exactly one connected Garmin watch with WearTAK.");
            }
            return;
        }
        showSettingsScreen();
        requestSettingsFromWatch("Garmin settings button");
    }

    private void handleGarminSettingsMessage(String msgType, JSONObject payload,
            GarminEntityForwarder.Link link) {
        if (payload == null || link == null || link != garminSettingsLink || !link.isCurrent()) return;
        if (garminSettingsTransaction.isPending() && !garminSettingsDraftIsCurrent()) {
            garminSettingsTransaction.cancel();
            garminSettingsDraftAtSend = null;
            phoneSettingsPushedThisConnection = false;
            if (settingsStatusTV != null) {
                settingsStatusTV.setText("Profile changed while waiting; refresh and send the current settings again.");
            }
            updateSettingsSendState();
            return;
        }
        Object value = payload.opt("transactionId");
        if (!GarminSettingsProtocol.isValidTransactionId(value)) {
            LogX.w(TAG, "Ignoring Garmin settings response with missing or invalid transaction ID.");
            return;
        }
        String transactionId = (String) value;
        long attempt = garminSettingsTransaction.generation();
        GarminSettingsTransaction.Result result;
        long now = SystemClock.elapsedRealtime();
        boolean applyWasPending = phoneSettingsPushedThisConnection;
        if ("settings_set_ack".equals(msgType)) {
            Object ok = payload.opt("ok");
            boolean acknowledged = Boolean.TRUE.equals(ok);
            String error = payload.optString("error", "Invalid settings acknowledgement");
            result = garminSettingsTransaction.acknowledge(transactionId, link, attempt,
                    now, acknowledged);
            if (result == GarminSettingsTransaction.Result.WAITING) {
                if (settingsStatusTV != null) {
                    settingsStatusTV.setText("Watch acknowledged settings; verifying the saved profile...");
                }
                return;
            }
            if (result == GarminSettingsTransaction.Result.FAILED) {
                garminSettingsDraftAtSend = null;
                lastSettingsApplyResult = false;
                phoneSettingsPushedThisConnection = false;
                if (settingsStatusTV != null) {
                    settingsStatusTV.setText(Boolean.FALSE.equals(ok)
                            ? "Watch could not save settings: " + error
                            : "Watch returned an invalid settings acknowledgement.");
                }
                updateSettingsSendState();
                return;
            }
        } else {
            result = garminSettingsTransaction.receiveSnapshot(transactionId, link,
                    attempt, now, payload);
            if (result == GarminSettingsTransaction.Result.WAITING) {
                if (settingsStatusTV != null) {
                    settingsStatusTV.setText("Watch profile received; waiting for save acknowledgement...");
                }
                return;
            }
            if (result == GarminSettingsTransaction.Result.FAILED) {
                garminSettingsDraftAtSend = null;
                lastSettingsApplyResult = false;
                phoneSettingsPushedThisConnection = false;
                if (settingsStatusTV != null) {
                    settingsStatusTV.setText("Watch profile did not match the settings sent.");
                }
                updateSettingsSendState();
                return;
            }
        }
        if (result == GarminSettingsTransaction.Result.SUCCEEDED) {
            boolean applyConfirmed = "settings_set_ack".equals(msgType) || applyWasPending;
            JSONObject confirmedSnapshot = garminSettingsTransaction.takeConfirmedSnapshot();
            garminSettingsDraftAtSend = null;
            if (confirmedSnapshot != null) applyGarminSettingsSnapshot(confirmedSnapshot);
            lastSettingsApplyResult = applyConfirmed ? true : null;
            if (settingsStatusTV != null && applyConfirmed) {
                settingsStatusTV.setText("Settings saved on Garmin watch; TAK server connection is not verified.");
            } else if (settingsStatusTV != null && !phoneSettingsPushedThisConnection) {
                settingsStatusTV.setText("Settings loaded from Garmin watch.");
            }
        } else if (result == GarminSettingsTransaction.Result.TIMED_OUT) {
            garminSettingsDraftAtSend = null;
            lastSettingsApplyResult = false;
            phoneSettingsPushedThisConnection = false;
            if (settingsStatusTV != null) {
                settingsStatusTV.setText("Garmin settings confirmation timed out; refresh and retry.");
            }
        }
        updateSettingsSendState();
    }

    private void applyGarminSettingsSnapshot(JSONObject payload) {
        garminSettingsSnapshotLoaded = true;
        lastSettingsPayload = payload;
        settingsModeLoadedThisConnection = true;
        phoneManagedSettingsRequested = CompanionSettingsPolicy.phoneManaged(setupPurpose, gatewayPurpose);
        if (phoneManagedSettingsCB != null) {
            phoneManagedSettingsCB.setChecked(phoneManagedSettingsRequested);
        }
        actualWatchCallsign = payload.optString("callsign", "");
        setText(watchCallsignET, actualWatchCallsign);
        setText(reportIntervalET, String.valueOf(payload.optInt("reportIntSecs")));
        updatePhoneIdentityViews();
        updateConnectedCardTitleFromCallsign(actualWatchCallsign);
        updateSettingsSendState();
        if (!setupPurpose && phoneManagedSettingsRequested && !phoneSettingsPushedThisConnection) {
            phoneSettingsPushedThisConnection = sendSettingsToWatch();
        }
    }

    private void scheduleGarminSettingsTimeout(String transactionId,
            GarminEntityForwarder.Link link, long attempt) {
        mainHandler.postDelayed(() -> {
            GarminSettingsTransaction.Result result = garminSettingsTransaction.timeout(
                    transactionId, link, attempt, SystemClock.elapsedRealtime());
            if (result == GarminSettingsTransaction.Result.TIMED_OUT) {
                garminSettingsDraftAtSend = null;
                lastSettingsApplyResult = false;
                phoneSettingsPushedThisConnection = false;
                if (settingsStatusTV != null) {
                    settingsStatusTV.setText("Garmin settings confirmation timed out; refresh and retry.");
                }
                updateSettingsSendState();
            }
        }, GarminSettingsTransaction.TIMEOUT_MILLIS);
    }

    private void handleGarminMessage(JSONObject envelope, GarminEntityForwarder.Link link) {
        if (!garminEnabled) return;
        String msgType = envelope.optString("msgType", "");
        if ("watch_route_navigation".equals(msgType)) {
            try {
                if (garminRouteNavigationTransport != null && phoneRouteNavigation != null) {
                    phoneRouteNavigation.handleJson(garminRouteNavigationTransport.inbound(envelope, link));
                } else {
                    LogX.w(TAG, "Garmin navigation bridge unavailable");
                }
            } catch (org.json.JSONException | IllegalArgumentException e) {
                LogX.w(TAG, "Invalid Garmin route navigation request", e);
            }
            return;
        }
        if (PhoneManagementRelease.ACK.equals(msgType)
                || PhoneManagementRelease.ACK.equals(envelope.optString("type", ""))) {
            handleManagementReleaseAck(envelope.optJSONObject("payload"), link);
            return;
        }
        LogX.i(TAG, "Garmin Connect IQ message: " + msgType);
        if ("bloodhound_control".equals(msgType)
                || "bloodhound_control_result".equals(msgType)) {
            if (!isCurrentGarminBloodhoundLink(link)) {
                LogX.w(TAG, "Ignoring Garmin Bloodhound message from a non-exclusive watch link");
                return;
            }
            BloodhoundBridge bridge = bloodhoundBridge;
            if (bridge != null) bridge.handleGarminMessage(envelope);
            return;
        }
        if ("settings_set_ack".equals(msgType) || "watch_settings".equals(msgType)) {
            handleGarminSettingsMessage(msgType, envelope.optJSONObject("payload"), link);
            return;
        }
        if (garminConnectionStatusTV != null) {
            garminConnectionStatusTV.setText("Garmin watch active (" + msgType + ").");
        }
        if ("entity_sync_request".equals(msgType)) {
            JSONObject requestPayload = envelope.optJSONObject("payload");
            if (garminClient != null && requestPayload != null) {
                garminClient.setRouteVersion(link, requestPayload.optInt("routeVersion", 0));
            }
            int requestedLimit = requestPayload == null ? 50 : requestPayload.optInt("limit", 50);
            if (garminEntities == null || !garminEntities.sendSnapshot(link, requestedLimit)) {
                LogX.w(TAG, "Unable to send Garmin entity snapshot");
                if (garminConnectionStatusTV != null) {
                    garminConnectionStatusTV.setText("Garmin connected, but entity sync could not be sent.");
                }
            } else if (garminConnectionStatusTV != null) {
                garminConnectionStatusTV.setText("Garmin watch active: sending "
                        + garminEntities.snapshotSize() + " nearest entities (limit 50)"
                        + (garminEntities.snapshotTruncated() ? "; additional entities omitted." : "."));
            }
            if (garminRoutes != null) garminRoutes.refreshLink(link);
            return;
        }
        if ("relay_hello".equals(msgType) && garminClient != null) {
            if (garminEntities != null) garminEntities.refreshLinks();
            JSONObject hello = envelope.optJSONObject("payload");
            if (hello != null) {
                garminClient.setRouteVersion(link, hello.optInt("routeVersion", 0));
                if (garminRoutes != null) garminRoutes.refreshLink(link);
                try {
                    Map<String, Object> status = GarminChatPolicy.relayStatus(
                            hello, isCurrentGarminBloodhoundLink(link));
                    boolean bloodhoundSupported =
                            Boolean.TRUE.equals(status.get("bloodhoundControl"));
                    if (bloodhoundBridge != null) {
                        if (bloodhoundSupported)
                            bloodhoundBridge.setRelaySession(hello.optString("relaySession"));
                        else
                            bloodhoundBridge.disconnected();
                    }
                    if (link != null && link.isCurrent()) link.send("relay_status", status);
                } catch (IllegalArgumentException e) {
                    LogX.w(TAG, "Garmin capabilities not negotiated: " + e.getMessage());
                }
            }
        }
        if (bleCotBridge != null) {
            String relaySession = link == null ? "" : link.relaySession();
            boolean accepted = bleCotBridge.handleGarminMessage(envelope, entity -> {
                if (link != null && link.isCurrent()
                        && !link.sendForSession("entity", entity, relaySession)) {
                    LogX.w(TAG, "Unable to return phone-resolved Garmin marker");
                }
            });
            JSONObject payload = envelope.optJSONObject("payload");
            String messageId = WatchMessageIds.id(envelope);
            if (payload != null && !messageId.isEmpty() && link != null && link.isCurrent()) {
                if (!link.send("relay_result", WatchMessageIds.result(envelope, accepted))) {
                    LogX.w(TAG, "Unable to acknowledge Garmin message; a retry will be deduplicated");
                }
            }
        }
    }

    private void registerMapItemListener() {
        unregisterMapItemListener();
        if (bleClient == null) return;
        bleCotBridge.setPhoneMarkerPolicy(phoneMarkerPolicy);
        phoneMarkerRelay = new PhoneMarkerMapRelay(phoneMarkerPolicy, bleClient, receivedCotTiming);
        phoneMarkerRelay.start(MapView.getMapView());
        phoneUserRelay = new PhoneUserMapRelay(bleClient);
        phoneUserRelay.start(MapView.getMapView());
        if (!setupPurpose && !gatewayPurpose) {
            phoneRouteNavigation = new PhoneRouteNavigationBridge(bleClient);
        }
    }

    private void unregisterMapItemListener() {
        if (phoneRouteNavigation != null) phoneRouteNavigation.close();
        phoneRouteNavigation = null;
        garminRouteNavigationTransport = null;
        if (phoneMarkerRelay != null) phoneMarkerRelay.stop();
        phoneMarkerRelay = null;
        if (phoneUserRelay != null) phoneUserRelay.stop();
        phoneUserRelay = null;
    }

    private void startPhoneAlertForwarding(long token) {
        if (cotServiceRemote != null) return;

        phoneEmergencyRelay.start();
        AtakBroadcast.DocumentedIntentFilter filter = new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction("com.atakmap.android.emergency.ALERT_EVENT");
        filter.addAction("com.atakmap.android.emergency.CANCEL_EVENT");
        filter.addAction("com.atakmap.android.emergency.tool.SEND_EMERGENCY");
        AtakBroadcast.getInstance().registerReceiver(phoneEmergencyReceiver, filter);
        CotServiceRemote remote = new CotServiceRemote();
        // Successfully imported events bypass CotServiceRemote listeners. The broadcasts
        // cover those and locally initiated alerts; this listener covers fallback events.
        remote.setCotEventListener((event, extras) -> runOnMain(() -> {
            if (connectionModes.isCurrent(token)) forwardPhoneEmergencyToWatch(event, extras);
        }));
        cotServiceRemote = remote;
    }

    private void stopPhoneAlertForwarding() {
        phoneEmergencyRelay.stop();
        if (cotServiceRemote == null) return;
        AtakBroadcast.getInstance().unregisterReceiver(phoneEmergencyReceiver);
        cotServiceRemote.setCotEventListener(null);
        cotServiceRemote = null;
    }

    private void forwardPhoneEmergencyToWatch(CotEvent event, android.os.Bundle extras) {
        if (event == null) return;
        if (gatewayPurpose && gateway != null) {
            StringBuilder cot = new StringBuilder();
            event.buildXml(cot);
            gateway.forwardPhoneEmergency(cot.toString());
            return;
        }
        if (garminEnabled && garminEntitySource != null) {
            garminEntitySource.cot(event);
            return;
        }
        WearTakBleClient client = bleClient;
        long session = client == null ? -1 : client.getReadySession();
        if (event == null || session < 0
                || !PhoneEmergencyRelay.isEmergencyType(event.getType())) return;
        String uid = event.getUID();
        if (bleCotBridge != null && bleCotBridge.isWatchOriginEmergency(uid)) return;
        StringBuilder xml = new StringBuilder();
        event.buildXml(xml);
        try {
            if (!phoneEmergencyRelay.forward(event.getType(), xml.toString(),
                    client.isBondedConnected(),
                    bleCotBridge != null && bleCotBridge.isWatchOriginEmergency(uid),
                    json -> client.writeJsonLineToWatch(json, session))) {
                LogX.w(TAG, "Phone emergency CoT could not be queued for BLE type="
                        + event.getType() + " uid=" + uid);
            }
        } catch (org.json.JSONException e) {
            LogX.e(TAG, "Failed to serialize phone emergency CoT for BLE", e);
        }
    }

    // ------------------ UI build / pane ------------------

    private View addServerButton;
    private Button deleteServerButton;

    private void showPane() {
        if (templatePane == null) {
            // IMPORTANT: store paneView as a FIELD (not a local)
            paneView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
            loggingButton = paneView.findViewById(R.id.loggingButton);
            loggingButton.setOnClickListener(v -> onLoggingClicked());
            updateLoggingButton();

            templatePane = new PaneBuilder(paneView)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();

            // roots
            deviceScreenRoot = paneView.findViewById(R.id.deviceScreenRoot);
            settingsScreenRoot = paneView.findViewById(R.id.settingsScreenRoot);

            // device screen
            connectionStatusTV = paneView.findViewById(R.id.connection_status);
            locationDiagnosticsStatus = paneView.findViewById(R.id.locationDiagnosticsStatus);
            garminConnectionStatusTV = paneView.findViewById(R.id.garminConnectionStatus);
            garminIdentityRows = paneView.findViewById(R.id.garminIdentityRows);
            garminConnectionBadgeTV = paneView.findViewById(R.id.garminConnectionBadge);
            garminSettingsButton = paneView.findViewById(R.id.garminSettingsButton);
            garminSettingsButton.setOnClickListener(v -> openGarminSettings());
            if (garminClient != null) {
                garminConnectionStatusTV.setText(garminClient.getStatus());
                if (garminEnabled) {
                    renderGarminConnection(garminClient.isConnected());
                } else {
                    renderGarminOff();
                }
            } else {
                garminConnectionStatusTV.setText("Garmin Connect IQ is off.");
                renderGarminOff();
            }
            garminToggleButton = paneView.findViewById(R.id.garminToggleButton);
            garminToggleButton.setText("Retry");
            garminToggleButton.setOnClickListener(v -> {
                if (isGarminConnectMissing()) {
                    openCompanionAppListing(GARMIN_CONNECT_PACKAGE);
                    return;
                }
                if (systemBondedPreferences().getBoolean(KEY_GARMIN_PAUSED, false)) {
                    systemBondedPreferences().edit().putBoolean(KEY_GARMIN_PAUSED, false).apply();
                    connectionModes.restart();
                    return;
                }
                if (garminClient == null || !garminClient.isConnected()) {
                    openCompanionApp(GARMIN_CONNECT_PACKAGE, "Garmin Connect");
                    return;
                }
                requestGarminManagementRelease();
            });
            renderGarminConnection(garminEnabled && garminClient != null && garminClient.isConnected());
            scanButton = paneView.findViewById(R.id.scanButton);
            bleSettingsButton = paneView.findViewById(R.id.bleSettingsButton);
            bleSettingsButton.setOnClickListener(v -> {
                if (connectionModes.getMode() != ConnectionModeController.Mode.TRADITIONAL
                        || bleClient == null || !bleClient.isConnected()) return;
                showSettingsScreen();
                requestSettingsFromWatch("bleSettingsGear");
            });
            deviceList = paneView.findViewById(R.id.deviceList);
            emptyState = paneView.findViewById(R.id.emptyState);
            systemBondedWatchStatus = paneView.findViewById(R.id.systemBondedWatchStatus);
            systemBondedIdentity = paneView.findViewById(R.id.systemBondedIdentity);
            systemBondedConnectButton = paneView.findViewById(R.id.systemBondedConnectButton);
            systemBondedConnectButton.setOnClickListener(v -> toggleWearableConnection());
            systemBondedSettingsButton = paneView.findViewById(R.id.systemBondedSettingsButton);
            systemBondedSettingsButton.setOnClickListener(v -> {
                if (bleClient == null || !bleClient.isConnected()) return;
                showSettingsScreen();
                requestSettingsFromWatch("wearableSettings");
            });
            connectionModeButton = paneView.findViewById(R.id.connectionModeButton);
            connectionModeButton.setOnClickListener(v -> showConnectionModeChooser());
            setupPurpose = !gatewayPurpose && systemBondedPreferences().getBoolean("setup_purpose", false);
            if (connectionModes.getMode() == ConnectionModeController.Mode.GARMIN) setupPurpose = false;
            purposeButton = paneView.findViewById(R.id.purposeButton);
            purposeButton.setText(gatewayPurpose ? "Multiwatch Gateway"
                    : setupPurpose ? "Configure Single Watch" : "Bond Single Watch");
            purposeButton.setOnClickListener(v -> showPurposeChooser());

            deviceList.setLayoutManager(new LinearLayoutManager(pluginContext));
            deviceAdapter = new BleDeviceAdapter(new BleDeviceAdapter.Listener() {
                @Override
                public void onConnectClicked(WearTakBleClient.DiscoveredDevice device) {
                    connectToDevice(device);
                }

                @Override
                public void onDisconnectClicked() {
                    WearTakPlugin.this.onDisconnectClicked();
                }

                @Override
                public void onSettingsClicked(WearTakBleClient.DiscoveredDevice device) {
                    if (bleClient == null || !bleClient.isConnected()) return;
                    selectedDevice = device;
                    showSettingsScreen();
                    requestSettingsFromWatch("settingsClicked");
                    if (lastSettingsPayload != null) {
                        applyServersToUi(currentServerList);
                        Integer reportInt = optInt(lastSettingsPayload, "reportIntSecs");
                        reportIntervalET = paneView.findViewById(R.id.reportInterval);

                        if (reportInt != null && reportIntervalET != null) reportIntervalET.setText(String.valueOf(reportInt));
                    }
                }
            });
            deviceAdapter.setSettingsVisible(setupPurpose);
            deviceList.setAdapter(deviceAdapter);
            updateConnectionModeUi();

            scanButton.setOnClickListener(v -> {
                if (connectionModes.getMode() != ConnectionModeController.Mode.TRADITIONAL) return;
                if (bleClient != null && bleClient.isConnected()) {
                    onDisconnectClicked();
                    return;
                }
                disableSystemBondedForManualUse();
                clearSessionSettings();
                int sessionId = ++scanSessionCount;
                if (bleClient != null) {
                    bleClient.pauseAutoReconnect();
                    bleClient.resetSession("UI scan " + sessionId);
                }

                scannedDevices.clear();
                if (deviceAdapter != null) {
                    deviceAdapter.setDevices(scannedDevices);
                    deviceAdapter.clearConnectionMarkers();
                }

                if (emptyState != null) emptyState.setVisibility(View.GONE);

                // scan text will update when scan finishes
                scanForDevices(sessionId);
            });

            // settings screen
            settingsBackButton = paneView.findViewById(R.id.settingsBackButton);
            serverSettingsGroup = paneView.findViewById(R.id.serverSettingsGroup);
            standaloneSetupButton = paneView.findViewById(R.id.standaloneSetupButton);
            standaloneSetupButton.setOnClickListener(v -> {
                boolean expanded = serverSettingsGroup.getVisibility() != View.VISIBLE;
                serverSettingsGroup.setVisibility(expanded ? View.VISIBLE : View.GONE);
                standaloneSetupButton.setText(expanded ? "Hide Standalone Setup" : "Standalone Setup");
            });
//            settingsDeviceSubtitle = paneView.findViewById(R.id.settingsDeviceSubtitle);
            watchCallsignET = paneView.findViewById(R.id.watchCallsign);
            phoneTeamTV = paneView.findViewById(R.id.phoneTeam);
            phoneRoleTV = paneView.findViewById(R.id.phoneRole);
            phoneManagedSettingsDescription = paneView.findViewById(R.id.phoneManagedSettingsDescription);
            phoneManagedSettingsCB = paneView.findViewById(R.id.phoneManagedSettings);
            phoneManagedSettingsCB.setChecked(phoneManagedSettingsRequested);
            phoneManagedSettingsCB.setOnClickListener(v -> {
                if (connectionModes.getMode() == ConnectionModeController.Mode.GARMIN
                        && garminSettingsTransaction.isPending()) {
                    garminSettingsTransaction.cancel();
                    phoneSettingsPushedThisConnection = false;
                    if (settingsStatusTV != null) {
                        settingsStatusTV.setText("Settings mode changed; send again to confirm the new profile.");
                    }
                }
                phoneManagedSettingsRequested = phoneManagedSettingsCB.isChecked();
                cancelPhoneSettingsHandshake();
                setConnectionStatus("CONNECTED");
                updateSystemBondedControl();
                updateSettingsSendState();
                if (!phoneManagedSettingsRequested && watchCallsignET != null) {
                    setText(watchCallsignET, actualWatchCallsign);
                }
                updatePhoneIdentityViews();
                if (settingsStatusTV != null) {
                    settingsStatusTV.setText("Mode changed (not sent yet). Tap Send to Watch to apply.");
                }
            });

            takServerListRV = paneView.findViewById(R.id.takServerList);
            takServerListRV.setLayoutManager(new LinearLayoutManager(pluginContext));

            addServerButton = paneView.findViewById(R.id.addServerButton);

            addServerButton.setOnClickListener(v -> {
                addNewServer();
                applyServersToUi(currentServerList);
                currentSelectedServerIndex = currentServerList.size() - 1;
                if (takServerAdapter != null) takServerAdapter.setSelectedIndex(currentSelectedServerIndex);
                TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
                if (item != null) populateFieldsFromItem(item);
            });

            takServerAdapter = new TakServerListAdapter(new TakServerListAdapter.Listener() {
                @Override
                public void onSelected(int index) {
                    currentSelectedServerIndex = index;
                    TakServerItem item = safeGet(currentServerList, index);
                    if (item != null) populateFieldsFromItem(item);
                }

                @Override
                public void onEnabledToggled(int index, boolean enabled) {
                    TakServerItem item = safeGet(currentServerList, index);
                    if (item != null) item.isEnabled = enabled;
                }

                @Override
                public void onDeleteClicked(int index) {
                    // Make sure edits in the text fields are not lost
                    commitFieldsToSelectedItem();

                    if (index < 0 || index >= currentServerList.size()) return;

                    currentServerList.remove(index);

                    // Select a reasonable next item
                    if (currentServerList.isEmpty()) {
                        currentSelectedServerIndex = -1;
                        applyServersToUi(currentServerList);
                        return;
                    }

                    int newSel = index;
                    if (newSel >= currentServerList.size()) newSel = currentServerList.size() - 1;

                    currentSelectedServerIndex = newSel;
                    applyServersToUi(currentServerList);
                    if (takServerAdapter != null) takServerAdapter.setSelectedIndex(newSel);

                    TakServerItem item = safeGet(currentServerList, newSel);
                    if (item != null) populateFieldsFromItem(item);

                    if (settingsStatusTV != null) settingsStatusTV.setText("Deleted server (not sent yet).");
                }

            });
            takServerListRV.setAdapter(takServerAdapter);

            takNameET = paneView.findViewById(R.id.takName);
            takAddressET = paneView.findViewById(R.id.takAddress);
            takPortET = paneView.findViewById(R.id.takPort);
            takUseP12CB = paneView.findViewById(R.id.takUseP12);
            takUsernameET = paneView.findViewById(R.id.takUsername);
            takPasswordET = paneView.findViewById(R.id.takPassword);
            takP12PasswordET = paneView.findViewById(R.id.takP12Password);
            takP12StatusTV = paneView.findViewById(R.id.takP12Status);
            chooseP12Button = paneView.findViewById(R.id.chooseP12Button);
            clearP12Button = paneView.findViewById(R.id.clearP12Button);
            reportIntervalET = paneView.findViewById(R.id.reportInterval);

            if (takUseP12CB != null) {
                takUseP12CB.setOnClickListener(v -> onAuthModeCheckboxClicked());
            }
            if (chooseP12Button != null) {
                chooseP12Button.setOnClickListener(v -> openP12Browser());
            }
            if (clearP12Button != null) {
                clearP12Button.setOnClickListener(v -> clearSelectedServerP12());
            }

            refreshSettingsButton = paneView.findViewById(R.id.refreshSettingsButton);
            refreshSettingsButton.setOnClickListener(v -> requestSettingsFromWatch("refreshButton"));

            sendSettingsButton = paneView.findViewById(R.id.sendSettingsButton);
            settingsStatusTV = paneView.findViewById(R.id.settingsStatus);

            sendSettingsButton.setOnClickListener(v -> {
                commitFieldsToSelectedItem();
                phoneSettingsPushedThisConnection = sendSettingsToWatch();
            });
            if (lastSettingsPayload != null) {
                applyServersToUi(currentServerList);
                setText(watchCallsignET, actualWatchCallsign);
                Integer report = optInt(lastSettingsPayload, "reportIntSecs");
                setText(reportIntervalET, report == null ? "" : String.valueOf(report));
            }
            updateSettingsSendState();

            settingsBackButton.setOnClickListener(v -> showDeviceScreen());

            showDeviceScreen();
        }

        if (uiService == null) return;

        if (!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);

            // only reset when opening
            resetDeviceScreenOnOpen();
            requestSettingsFromWatch("showPane");
        } else {
            uiService.closePane(templatePane);
        }
    }

    private void refreshDeviceListUi() {
        if (deviceAdapter == null) return;
        deviceAdapter.setPanelStatus(panelStatusText());
        List<WearTakBleClient.DiscoveredDevice> identities = new ArrayList<>();
        for (WearTakBleClient.DiscoveredDevice device : scannedDevices)
            identities.add(bleClient == null ? device : bleClient.withSessionIdentity(device));
        deviceAdapter.setDevices(ScanDeviceList.visibleRows(identities, currentConnectedAddress,
                bleClient != null && bleClient.isConnected(), this::loadPreferredAddress));
    }

    private void resetDeviceScreenOnOpen() {
        // Clear scan list every time dropdown is opened
        scannedDevices.clear();
        if (deviceAdapter != null) deviceAdapter.setDevices(scannedDevices);

        if (emptyState != null) {
            emptyState.setText("");
            emptyState.setVisibility(View.GONE);
        }

        if (scanButton != null) scanButton.setText(SCAN_TEXT_DEFAULT);
        if (connectionStatusTV != null) {
            setConnectionStatus(bleClient != null && bleClient.isConnected()
                    ? "CONNECTED"
                    : systemBondedConnection != null && systemBondedEnabled
                    ? systemBondedConnection.getStatus() : "NOT CONNECTED");
        }
        updateSystemBondedControl();

        // Connected card: show only if we have a selectedDevice from this session
        if (bleClient != null && bleClient.isConnected()
                && selectedDevice != null && currentConnectedAddress != null
                && currentConnectedAddress.equals(selectedDevice.address)) {
            showConnectedCard(selectedDevice);
        } else {
            hideConnectedCard();
        }
        updateConnectionModeUi();
    }

    private void scanForDevices(final int sessionId) {
        if (bleClient == null) return;

        bleClient.scanForDevices(new WearTakBleClient.ScanListener() {
            @Override
            public void onDeviceFound(WearTakBleClient.DiscoveredDevice device) {
                mainHandler.post(() -> {
                    if (sessionId != scanSessionCount) return;
                    upsertDevice(device);
                    refreshDeviceListUi();
                });
            }

            @Override
            public void onDevicesExpired(List<String> addresses) {
                mainHandler.post(() -> {
                    if (sessionId != scanSessionCount) return;
                    scannedDevices.removeIf(device -> addresses.contains(device.address));
                    refreshDeviceListUi();
                });
            }

            @Override
            public void onScanFinished(List<WearTakBleClient.DiscoveredDevice> devices) {
                mainHandler.post(() -> {
                    if (sessionId != scanSessionCount) return;
                    scannedDevices.clear();
                    scannedDevices.addAll(devices);
                    refreshDeviceListUi();
                    if (emptyState != null) {
                        emptyState.setVisibility(View.GONE);
                    }
                    if (scanButton != null) {
                        updateConnectionModeUi();
                    }
                    if (bleClient != null) bleClient.resumeAutoReconnect();
                });
            }

            @Override
            public void onScanError(String msg) {
                mainHandler.post(() -> {
                    if (sessionId != scanSessionCount) return;
                    if (emptyState != null) {
                        emptyState.setText("Scan error: " + msg);
                        emptyState.setVisibility(View.VISIBLE);
                    }
                    if (scanButton != null) {
                        updateConnectionModeUi();
                    }
                    if (bleClient != null) bleClient.resumeAutoReconnect();
                });
            }
        });
    }

    private void upsertDevice(WearTakBleClient.DiscoveredDevice device) {
        int idx = -1;
        for (int i = 0; i < scannedDevices.size(); i++) {
            if (scannedDevices.get(i).address.equals(device.address)) { idx = i; break; }
        }
        if (idx >= 0) scannedDevices.set(idx, device);
        else scannedDevices.add(device);
    }

    // ---------- SETTINGS: request / parse / apply ----------
    private void requestSettingsFromWatch(String reason) {
        if (managementRelease.isPending()) return;
        if (connectionModes.getMode() == ConnectionModeController.Mode.GARMIN) {
            refreshGarminSettingsLink();
            GarminEntityForwarder.Link link = garminSettingsLink;
            if (link == null || !link.isCurrent()) {
                if (settingsStatusTV != null) {
                    settingsStatusTV.setText("Garmin settings require one connected WearTAK watch.");
                }
                return;
            }
            String transactionId = GarminSettingsProtocol.newTransactionId();
            garminSettingsDraftAtSend = null;
            long attempt = garminSettingsTransaction.beginRequest(transactionId, link,
                    SystemClock.elapsedRealtime());
            boolean sent = link.send("request_settings", GarminSettingsProtocol.request(transactionId));
            if (!sent) {
                garminSettingsTransaction.cancel();
                if (settingsStatusTV != null) settingsStatusTV.setText("Could not request Garmin settings.");
                return;
            }
            phoneSettingsPushedThisConnection = false;
            lastSettingsApplyResult = null;
            if (settingsStatusTV != null) settingsStatusTV.setText("Requesting settings from Garmin watch...");
            updateSettingsSendState();
            scheduleGarminSettingsTimeout(transactionId, link, attempt);
            LogX.i(TAG, "Garmin request_settings (" + reason + ") sent with transaction " + transactionId);
            return;
        }
        if (bleClient == null || !bleClient.isConnected()) return;
        if (phoneSettingsHandshake.isFailed() && !setupPurpose && phoneManagedSettingsRequested) {
            startPhoneSettingsHandshake();
            return;
        }
        phoneSettingsPushedThisConnection = false;
        lastSettingsApplyResult = null;
        String req = "{\"msg_type\":\"request_settings\"}";
        boolean ok = bleClient.writeJsonLineToWatch(req);

        LogX.i(TAG, "request_settings (" + reason + ") ok=" + ok);

        if (settingsStatusTV != null && settingsScreenRoot != null
                && settingsScreenRoot.getVisibility() == View.VISIBLE) {
            settingsStatusTV.setText(ok ? "Requesting settings..." : "Cannot request settings (not connected).");
        }
    }

    private List<TakServerItem> parseTakServerList(JSONObject payload) {
        ArrayList<TakServerItem> out = new ArrayList<>();
        if (payload == null) return out;
        JSONArray arr = payload.optJSONArray("takServerList");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            out.add(TakServerItem.fromJson(o));
        }
        return out;
    }

    private void applyServersToUi(List<TakServerItem> servers) {
        if (takServerAdapter == null) return;

        takServerAdapter.setItems(servers);

        int sel = -1;
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).isEnabled) { sel = i; break; }
        }
        if (sel == -1 && !servers.isEmpty()) sel = 0;

        currentSelectedServerIndex = sel;
        takServerAdapter.setSelectedIndex(sel);

        TakServerItem chosen = safeGet(servers, sel);
        if (chosen != null) populateFieldsFromItem(chosen);
        else clearServerEditor();
    }

    private void populateFieldsFromItem(TakServerItem item) {
        if (item == null) return;

        setText(takNameET, item.name);
        setText(takAddressET, item.address);
        setText(takPortET, String.valueOf(item.port));
        if (takUseP12CB != null) takUseP12CB.setChecked(item.isP12Cert);

        if (!item.isP12Cert) {
            setText(takUsernameET, item.username);
            setText(takPasswordET, item.password);
            setText(takP12PasswordET, "");
        } else {
            item.p12CertPassword = TakServerItem.normalizeP12Password(item.p12CertPassword);
            setText(takUsernameET, "");
            setText(takPasswordET, "");
            setText(takP12PasswordET, item.p12CertPassword);
        }

        refreshSelectedServerAuthUi(item);
    }

    private void commitFieldsToSelectedItem() {
        TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
        if (item == null) return;

        item.name = getText(takNameET);
        item.address = getText(takAddressET);

        Integer port = tryParseInt(getText(takPortET));
        if (port != null) item.port = port;

        if (!item.isP12Cert) {
            item.username = getText(takUsernameET);
            item.password = getText(takPasswordET);
        } else {
            item.username = "";
            item.password = "";
            item.p12CertPassword = TakServerItem.normalizeP12Password(getText(takP12PasswordET));
        }
    }

    public String desiredWatchCallsign = null;
    public String actualWatchCallsign = null;

    // ---------- SETTINGS: send back to watch ----------
    private boolean sendSettingsToWatch() {
        if (managementRelease.isPending()) return false;
        if (connectionModes.getMode() == ConnectionModeController.Mode.GARMIN) {
            return sendSettingsToGarminWatch();
        }
        if (bleClient == null || !bleClient.isConnected()) return false;
        if (!settingsSession.canSend()) {
            if (settingsStatusTV != null) settingsStatusTV.setText("Wait for this watch's settings snapshot before sending.");
            return false;
        }
        commitFieldsToSelectedItem();

        JSONObject root;
        try {
            Integer reportInt = tryParseInt(getText(reportIntervalET));
            if (!getText(reportIntervalET).isEmpty() && reportInt == null) {
                throw new IllegalArgumentException("Reporting interval must be a positive number.");
            }
            if (reportInt == null && lastSettingsPayload != null) {
                reportInt = optInt(lastSettingsPayload, "reportIntSecs");
            }
            String cs = phoneManagedSettingsRequested ? getPhoneCallsign() : getText(watchCallsignET);
            root = SettingsPayloadBuilder.build(currentServerList, reportInt, cs,
                    getPhoneTeam(), getPhoneRole(), phoneManagedSettingsRequested);
            desiredWatchCallsign = cs;
        } catch (IllegalArgumentException error) {
            if (settingsStatusTV != null) settingsStatusTV.setText(error.getMessage());
            return false;
        } catch (JSONException error) {
            LogX.e(TAG, "Failed to build settings JSON", error);
            if (settingsStatusTV != null) settingsStatusTV.setText("Failed to build settings JSON.");
            return false;
        }

        String jsonLine = root.toString().replace("\\/", "/");
        if (phoneSettingsHandshake.isPending()) phoneSettingsRetryJson = jsonLine;
        lastSettingsApplyResult = null;
        boolean ok = bleClient.writeJsonLineToWatch(jsonLine);
        if (ok && phoneManagedSettingsRequested) {
            JSONObject sent = root.optJSONObject("payload");
            lastSentPhoneIdentity = java.util.Arrays.asList(sent.optString("callsign"),
                    sent.optString("team"), sent.optString("role"));
        }
        if (settingsStatusTV != null) {
            settingsStatusTV.setText(ok ? "Sending settings to watch..." : "Failed to send (not connected).");
        }
        return ok;
    }

    private void syncLivePhoneIdentity() {
        if (!identityObserverActive) return;
        updatePhoneIdentityViews();
        refreshIdentityStatus();
        WearTakBleClient owner = bleClient;
        long readySession = owner == null ? -1 : owner.getReadySession();
        if (setupPurpose || gatewayPurpose || !phoneManagedSettingsRequested
                || connectionModes.getMode() == ConnectionModeController.Mode.GARMIN
                || managementRelease.isPending() || phoneSettingsHandshake.isPending()
                || phoneSettingsHandshake.isFailed() || !settingsSession.canSend()
                || readySession < 0) return;
        List<String> identity = java.util.Arrays.asList(getPhoneCallsign(), getPhoneTeam(), getPhoneRole());
        if (identity.equals(lastSentPhoneIdentity)) return;
        try {
            List<JSONObject> commands = SettingsPayloadBuilder.identityChanges(lastSentPhoneIdentity,
                    identity.get(0), identity.get(1), identity.get(2));
            lastSentPhoneIdentity = lastSentPhoneIdentity == null
                    ? new ArrayList<>(java.util.Collections.nCopies(3, null))
                    : new ArrayList<>(lastSentPhoneIdentity);
            String[] keys = {"callsign", "team", "role"};
            for (JSONObject envelope : commands) {
                if (!owner.writeJsonLineToWatch(envelope.toString(), readySession)) {
                    LogX.e(TAG, "Live phone identity sync could not queue " + envelope.optString("msg_type"));
                    if (settingsStatusTV != null) settingsStatusTV.setText("Failed to send updated phone identity.");
                    return;
                }
                JSONObject payload = envelope.getJSONObject("payload");
                for (int i = 0; i < keys.length; i++) {
                    if (payload.has(keys[i])) lastSentPhoneIdentity.set(i, identity.get(i));
                }
            }
            if (!commands.isEmpty() && bleCotBridge != null) {
                bleCotBridge.sendStandardPli(new JSONObject().put("payload", new JSONObject()).toString());
            }
            desiredWatchCallsign = identity.get(0);
            lastSettingsApplyResult = null;
            if (settingsStatusTV != null) settingsStatusTV.setText("Sending updated phone identity to watch...");
        } catch (JSONException | IllegalArgumentException error) {
            LogX.e(TAG, "Cannot build live phone identity settings", error);
            if (settingsStatusTV != null) settingsStatusTV.setText("Failed to build updated phone identity settings.");
        }
    }

    private boolean sendSettingsToGarminWatch() {
        GarminEntityForwarder.Link link = garminSettingsLink;
        if (link == null || !link.isCurrent()) {
            if (settingsStatusTV != null) settingsStatusTV.setText("Garmin watch is not connected.");
            return false;
        }
        if (!garminSettingsSnapshotLoaded) {
            if (settingsStatusTV != null) {
                settingsStatusTV.setText("Refresh this Garmin watch's settings before sending.");
            }
            return false;
        }
        if (garminSettingsTransaction.isPending()) {
            if (settingsStatusTV != null) {
                settingsStatusTV.setText("Wait for the current Garmin settings transaction to finish.");
            }
            return false;
        }

        Integer reportInterval = tryParseInt(getText(reportIntervalET));
        if (!getText(reportIntervalET).isEmpty() && reportInterval == null) {
            if (settingsStatusTV != null) {
                settingsStatusTV.setText("Reporting interval must be a positive number.");
            }
            return false;
        }
        if (reportInterval == null && lastSettingsPayload != null) {
            reportInterval = optInt(lastSettingsPayload, "reportIntSecs");
        }
        String callsign = phoneManagedSettingsRequested
                ? getPhoneCallsign() : getText(watchCallsignET);
        String transactionId = GarminSettingsProtocol.newTransactionId();
        Map<String, Object> payload;
        try {
            payload = GarminSettingsProtocol.phoneSettings(transactionId, callsign,
                    reportInterval, getPhoneTeam(), getPhoneRole(), phoneManagedSettingsRequested);
        } catch (IllegalArgumentException error) {
            if (settingsStatusTV != null) settingsStatusTV.setText(error.getMessage());
            return false;
        }

        long attempt = garminSettingsTransaction.beginApply(transactionId, link,
                SystemClock.elapsedRealtime(), payload);
        garminSettingsDraftAtSend = captureGarminSettingsDraft();
        lastSettingsApplyResult = null;
        boolean sent = link.send("set_settings", payload);
        if (!sent) {
            garminSettingsTransaction.cancel();
            garminSettingsDraftAtSend = null;
            if (settingsStatusTV != null) settingsStatusTV.setText("Could not send settings to Garmin watch.");
            return false;
        }
        desiredWatchCallsign = callsign;
        phoneSettingsPushedThisConnection = true;
        if (settingsStatusTV != null) {
            settingsStatusTV.setText("Sending profile settings to Garmin watch...");
        }
        scheduleGarminSettingsTimeout(transactionId, link, attempt);
        return true;
    }

    private Map<String, Object> captureGarminSettingsDraft() {
        Map<String, Object> draft = new HashMap<>();
        draft.put("phoneManaged", phoneManagedSettingsRequested);
        draft.put("callsign", phoneManagedSettingsRequested
                ? getPhoneCallsign() : getText(watchCallsignET));
        draft.put("reportInterval", getText(reportIntervalET));
        draft.put("team", getPhoneTeam());
        draft.put("role", getPhoneRole());
        return draft;
    }

    private boolean garminSettingsDraftIsCurrent() {
        if (garminSettingsDraftAtSend == null) return true;
        Map<String, Object> current = captureGarminSettingsDraft();
        return current.equals(garminSettingsDraftAtSend);
    }

    private void addNewServer() {
        commitFieldsToSelectedItem();

        TakServerItem item = new TakServerItem();
        item.isEnabled = false;
        item.name = "New Server";
        item.address = "";
        item.port = 8089;

        item.isP12Cert = false;
        item.username = "";
        item.password = "";
        item.p12Cert = "";
        item.p12CertPassword = "";
        item.p12DisplayName = null;

        currentServerList.add(item);

        int newIndex = currentServerList.size() - 1;

        if (takServerAdapter != null) {
            takServerAdapter.setItems(currentServerList);
            takServerAdapter.setSelectedIndex(newIndex);
        }
        currentSelectedServerIndex = newIndex;

        populateFieldsFromItem(item);

        if (settingsStatusTV != null) settingsStatusTV.setText("Added server (not sent yet).");
    }

    private void deleteSelectedServer() {
        int idx = currentSelectedServerIndex;

        if (idx < 0 || idx >= currentServerList.size()) {
            if (settingsStatusTV != null) settingsStatusTV.setText("No server selected to delete.");
            return;
        }

        currentServerList.remove(idx);

        if (currentServerList.isEmpty()) {
            currentSelectedServerIndex = -1;

            if (takServerAdapter != null) {
                takServerAdapter.setItems(currentServerList);
                takServerAdapter.setSelectedIndex(-1);
            }

            setText(takNameET, "");
            setText(takAddressET, "");
            setText(takPortET, "");
            if (takUseP12CB != null) takUseP12CB.setChecked(false);
            setText(takUsernameET, "");
            setText(takPasswordET, "");
            setText(takP12PasswordET, "");
            updateP12Status(null);
            refreshSelectedServerAuthUi(null);

            if (settingsStatusTV != null) settingsStatusTV.setText("Deleted server (not sent yet).");
            return;
        }

        if (idx >= currentServerList.size()) idx = currentServerList.size() - 1;

        currentSelectedServerIndex = idx;
        TakServerItem item = currentServerList.get(idx);

        if (takServerAdapter != null) {
            takServerAdapter.setItems(currentServerList);
            takServerAdapter.setSelectedIndex(idx);
        }

        populateFieldsFromItem(item);

        if (settingsStatusTV != null) settingsStatusTV.setText("Deleted server (not sent yet).");
    }

    // ---------- screen nav ----------

    private void showSettingsScreen() {
        boolean garminMode = connectionModes.getMode() == ConnectionModeController.Mode.GARMIN;
        if (garminMode) {
            if (garminSettingsLink == null || !garminSettingsLink.isCurrent()) return;
        } else if (bleClient == null || !bleClient.isConnected()) {
            return;
        }
        if (serverSettingsGroup != null) {
            serverSettingsGroup.setVisibility(setupPurpose && !garminMode ? View.VISIBLE : View.GONE);
        }
        if (standaloneSetupButton != null) {
            standaloneSetupButton.setVisibility(View.GONE);
            standaloneSetupButton.setText("Standalone Setup");
        }
        if (phoneManagedSettingsDescription != null) {
            updateCompanionSettingsDescription();
        }
        if (phoneManagedSettingsCB != null) {
            phoneManagedSettingsCB.setVisibility(setupPurpose ? View.GONE : View.VISIBLE);
        }
        if (setupPurpose) {
            phoneManagedSettingsRequested = false;
            if (phoneManagedSettingsCB != null) phoneManagedSettingsCB.setChecked(false);
            setText(watchCallsignET, actualWatchCallsign);
        }
        updateSettingsSendState();
//        if (settingsDeviceSubtitle != null) {
//            String label = (selectedDevice != null)
//                    ? ((selectedDevice.name == null ? "WearTAK" : selectedDevice.name) + " • " + selectedDevice.address)
//                    : "—";
//            settingsDeviceSubtitle.setText(label);
//        }
        if (deviceScreenRoot != null) deviceScreenRoot.setVisibility(View.GONE);
        if (settingsScreenRoot != null) settingsScreenRoot.setVisibility(View.VISIBLE);
        updatePhoneIdentityViews();
    }

    private void showDeviceScreen() {
        if (settingsScreenRoot != null) settingsScreenRoot.setVisibility(View.GONE);
        if (deviceScreenRoot != null) deviceScreenRoot.setVisibility(View.VISIBLE);
    }

    // ---------- utils ----------

    private static void setText(EditText e, String v) {
        if (e == null) return;
        e.setText(v == null ? "" : v);
    }

    private static String getText(EditText e) {
        if (e == null || e.getText() == null) return "";
        return e.getText().toString().trim();
    }

    private String getPhoneCallsign() {
        MapView mapView = MapView.getMapView();
        String callsign = mapView == null ? "" : mapView.getDeviceCallsign();
        if (callsign == null || callsign.trim().isEmpty()) {
            Marker self = mapView == null ? null : mapView.getSelfMarker();
            callsign = self == null ? "" : self.getMetaString("callsign", "");
        }
        return callsign == null ? "" : callsign.trim();
    }

    private String getPhoneTeam() {
        return PhoneIdentity.team(MapView.getMapView());
    }

    private String getPhoneRole() {
        return PhoneIdentity.role(MapView.getMapView());
    }

    private void updatePhoneIdentityViews() {
        updateCompanionSettingsDescription();
        String callsign = getPhoneCallsign();
        if (watchCallsignET != null) {
            watchCallsignET.setEnabled(!phoneManagedSettingsRequested);
            watchCallsignET.setFocusableInTouchMode(!phoneManagedSettingsRequested);
            if (phoneManagedSettingsRequested && !callsign.isEmpty()) watchCallsignET.setText(callsign);
            if (!phoneManagedSettingsRequested && getText(watchCallsignET).isEmpty()) {
                setText(watchCallsignET, actualWatchCallsign);
            }
        }
        if (phoneTeamTV != null) phoneTeamTV.setText("Team (phone)\n" + getPhoneTeam());
        if (phoneRoleTV != null) phoneRoleTV.setText("Role (phone)\n" + getPhoneRole());
    }

    private void updateCompanionSettingsDescription() {
        if (phoneManagedSettingsDescription == null) return;
        boolean garminMode = connectionModes.getMode() == ConnectionModeController.Mode.GARMIN;
        phoneManagedSettingsDescription.setText(phoneManagedSettingsRequested
                ? "Use this phone's ATAK callsign, team, and role. Identity "
                        + (garminMode ? "syncs on reconnect" : "changes sync while connected")
                        + "; watch identity controls stay locked until disabled."
                : garminMode
                ? "Use an independent watch callsign and reporting profile. TAK servers and certificates stay on the phone."
                : "Use an independent watch callsign. Standalone Setup uploads TAK server settings and a P12; the watch disconnects after settings are applied.");
    }

    private static Integer tryParseInt(String s) {
        try {
            if (s == null || s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer optInt(JSONObject o, String key) {
        if (o == null) return null;
        if (!o.has(key)) return null;
        try { return o.getInt(key); } catch (Throwable t) { return null; }
    }

    private static String optStringOrNull(JSONObject o, String key) {
        if (o == null) return null;
        if (!o.has(key)) return null;
        String v = o.optString(key, null);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean hasArray(JSONObject o, String key) {
        return o != null && o.optJSONArray(key) != null;
    }

    private static TakServerItem safeGet(List<TakServerItem> list, int idx) {
        if (list == null) return null;
        if (idx < 0 || idx >= list.size()) return null;
        return list.get(idx);
    }

    private void clearServerEditor() {
        setText(takNameET, "");
        setText(takAddressET, "");
        setText(takPortET, "");
        if (takUseP12CB != null) takUseP12CB.setChecked(false);
        setText(takUsernameET, "");
        setText(takPasswordET, "");
        setText(takP12PasswordET, "");
        updateP12Status(null);
        refreshSelectedServerAuthUi(null);
    }

    private void onAuthModeCheckboxClicked() {
        TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
        if (item == null || takUseP12CB == null) {
            if (takUseP12CB != null) takUseP12CB.setChecked(false);
            return;
        }

        boolean useP12 = takUseP12CB.isChecked();
        item.isP12Cert = useP12;
        if (useP12) {
            item.username = "";
            item.password = "";
            item.p12CertPassword = TakServerItem.normalizeP12Password(item.p12CertPassword);
            setText(takP12PasswordET, item.p12CertPassword);
        } else {
            item.p12Cert = "";
            item.p12CertPassword = "";
            item.p12DisplayName = null;
            setText(takP12PasswordET, "");
        }

        refreshSelectedServerAuthUi(item);
        if (takServerAdapter != null) takServerAdapter.notifyDataSetChanged();
    }

    private void refreshSelectedServerAuthUi(TakServerItem item) {
        boolean useP12 = item != null && item.isP12Cert;

        setVisible(takUsernameET, !useP12);
        setVisible(takPasswordET, !useP12);
        setVisible(takP12StatusTV, useP12);
        setVisible(takP12PasswordET, useP12);
        View p12Buttons = paneView == null ? null : paneView.findViewById(R.id.takP12Buttons);
        setVisible(p12Buttons, useP12);

        if (chooseP12Button != null) {
            boolean hasP12 = item != null && item.p12Cert != null && !item.p12Cert.isEmpty();
            chooseP12Button.setText(hasP12 ? "Replace P12 File" : "Choose P12 File");
        }
        updateP12Status(item);
    }

    private void updateP12Status(TakServerItem item) {
        if (takP12StatusTV == null) return;
        if (item == null || !item.isP12Cert) {
            takP12StatusTV.setText(NO_P12_SELECTED);
            return;
        }

        if (item.p12DisplayName != null && !item.p12DisplayName.trim().isEmpty()) {
            takP12StatusTV.setText("Selected: " + item.p12DisplayName);
            return;
        }

        if (item.p12Cert != null && !item.p12Cert.isEmpty()) {
            takP12StatusTV.setText(WATCH_P12_SELECTED);
            return;
        }

        takP12StatusTV.setText(NO_P12_SELECTED);
    }

    private void openP12Browser() {
        TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
        if (item == null) {
            if (settingsStatusTV != null) settingsStatusTV.setText("Select a server before choosing a P12 file.");
            return;
        }

        final long settingsToken = settingsSession.token();
        ImportFileBrowserDialog browser = new ImportFileBrowserDialog(MapView.getMapView().getContext());
        browser.setExtensionTypes("p12");
        browser.setTitle("Select P12 Certificate");
        browser.setOnDismissListener(new ImportFileBrowserDialog.DialogDismissed() {
            @Override
            public void onFileSelected(File file) {
                runOnMain(() -> {
                    if (!settingsSession.isCurrent(settingsToken)
                            || safeGet(currentServerList, currentSelectedServerIndex) != item) return;
                    importP12File(file);
                });
            }

            @Override
            public void onDialogClosed() {
            }
        });
        browser.show();
    }

    private void importP12File(File file) {
        TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
        if (item == null || file == null) return;

        try {
            byte[] bytes = readFileBytes(file);
            if (bytes.length == 0) {
                if (settingsStatusTV != null) settingsStatusTV.setText("Selected P12 file was empty.");
                return;
            }

            item.isP12Cert = true;
            item.username = "";
            item.password = "";
            item.p12Cert = TakServerItem.normalizeP12Cert(Base64.encodeToString(bytes, Base64.NO_WRAP));
            item.p12CertPassword = TakServerItem.normalizeP12Password(getText(takP12PasswordET));
            item.p12DisplayName = file.getName();

            if (takUseP12CB != null) takUseP12CB.setChecked(true);
            setText(takP12PasswordET, item.p12CertPassword);
            refreshSelectedServerAuthUi(item);
            if (takServerAdapter != null) takServerAdapter.notifyDataSetChanged();
            if (settingsStatusTV != null) settingsStatusTV.setText("Loaded " + file.getName() + " for the selected server.");
        } catch (Throwable t) {
            LogX.w(TAG, "Failed to load p12 file", t);
            if (settingsStatusTV != null) settingsStatusTV.setText("Failed to read the selected P12 file.");
        }
    }

    private void clearSelectedServerP12() {
        TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
        if (item == null) return;

        item.p12Cert = "";
        item.p12CertPassword = "";
        item.p12DisplayName = null;
        setText(takP12PasswordET, "");
        refreshSelectedServerAuthUi(item);
        if (takServerAdapter != null) takServerAdapter.notifyDataSetChanged();
        if (settingsStatusTV != null) settingsStatusTV.setText("Cleared the selected server certificate.");
    }

    private static byte[] readFileBytes(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static void setVisible(View view, boolean visible) {
        if (view == null) return;
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateConnectedCardTitleFromCallsign(String callsign) {
        if (connectedCardView == null) return;
        if (callsign == null || callsign.trim().isEmpty()) return;
        if (bleClient != null && bleClient.isConnected() && selectedDevice != null) {
            bindConnectedCard(connectedCardView, selectedDevice);
            return;
        }

        TextView name = connectedCardView.findViewById(R.id.deviceName);
        if (name != null) name.setText(callsign);
    }

    private void clearSessionSettings() {
        lastSentPhoneIdentity = null;
        cancelPhoneSettingsHandshake();
        settingsSession.clear();
        garminSettingsTransaction.cancel();
        garminSettingsDraftAtSend = null;
        garminSettingsSnapshotLoaded = false;
        phoneSettingsPushedThisConnection = false;
        phoneManagedSettingsRequested = CompanionSettingsPolicy.phoneManaged(setupPurpose, gatewayPurpose);
        settingsModeLoadedThisConnection = false;
        if (phoneManagedSettingsCB != null) phoneManagedSettingsCB.setChecked(phoneManagedSettingsRequested);
        lastSettingsPayload = null;
        lastSettingsApplyResult = null;
        currentServerList = new ArrayList<>();
        currentSelectedServerIndex = -1;
        actualWatchCallsign = desiredWatchCallsign = null;
        if (takServerAdapter != null) {
            takServerAdapter.setItems(currentServerList);
            takServerAdapter.setSelectedIndex(-1);
        }
        clearServerEditor();
        setText(watchCallsignET, "");
        setText(reportIntervalET, "");
        if (settingsStatusTV != null) settingsStatusTV.setText("Waiting for this watch's settings snapshot.");
        updateSettingsSendState();
    }

    private void updateSettingsSendState() {
        if (sendSettingsButton != null) {
            boolean garminMode = connectionModes.getMode() == ConnectionModeController.Mode.GARMIN;
            sendSettingsButton.setEnabled(!managementRelease.isPending() && (garminMode
                    ? garminSettingsSnapshotLoaded && garminSettingsLink != null
                            && garminSettingsLink.isCurrent()
                            && !garminSettingsTransaction.isPending()
                    : !phoneSettingsHandshake.isPending() && settingsSession.canSend()
                            && bleClient != null && bleClient.isConnected()));
        }
    }

    private void applySettingsPayloadDiscretely(JSONObject payload, String reason, long settingsToken)
            throws org.json.JSONException {
        if (payload == null) return;
        boolean complete = payload.optJSONArray("takServerList") != null
                && optInt(payload, "reportIntSecs") != null;
        if (!settingsSession.acceptSnapshot(settingsToken, complete)) return;
        lastSettingsPayload = WatchSettingsSnapshot.observe(lastSettingsPayload, payload, complete);
        JSONArray servers = payload.optJSONArray("takServerList");
        LogX.i(TAG, "applySettingsPayloadDiscretely reason=" + reason
                + " serverCount=" + (servers == null ? 0 : servers.length()));
        mainHandler.post(() -> {
            if (!settingsSession.isCurrent(settingsToken)) return;
            if (!settingsModeLoadedThisConnection) {
                // The selected purpose is desired state; a watch snapshot is only observed state.
                phoneManagedSettingsRequested = CompanionSettingsPolicy.phoneManaged(setupPurpose, gatewayPurpose);
                settingsModeLoadedThisConnection = true;
                if (phoneManagedSettingsCB != null) {
                    phoneManagedSettingsCB.setChecked(phoneManagedSettingsRequested);
                }
            }
            updatePhoneIdentityViews();
        });

        // callsign (device-wide)
        if (payload.has("callsign")) {
            String cs = payload.optString("callsign", "").trim();
            if (!cs.isEmpty()) {
                actualWatchCallsign = cs;
                runOnMain(() -> {
                    if (!settingsSession.isCurrent(settingsToken)) return;
                    if (!phoneManagedSettingsRequested) setText(watchCallsignET, cs);
                    updateConnectedCardTitleFromCallsign(cs); // optional if you already have this
                });
            }
        }

        // reporting interval (device-wide)
        if (payload.has("reportIntSecs")) {
            Integer reportInt = optInt(payload, "reportIntSecs");
            if (reportInt != null) {
                runOnMain(() -> {
                    if (!settingsSession.isCurrent(settingsToken)) return;
                    if (reportIntervalET != null) reportIntervalET.setText(String.valueOf(reportInt));
                });
            }
        }

        // tak servers (list)
        if (payload.has("takServerList")) {
            currentServerList = parseTakServerList(payload);
            runOnMain(() -> {
                if (settingsSession.isCurrent(settingsToken)) applyServersToUi(currentServerList);
            });
        }
        updateSettingsSendState();
        mainHandler.post(liveIdentityUpdate);
    }

    private void savePreferredDevice(String address, String nameOrNull) {
        if (pluginContext == null) return;
        if (address == null || address.trim().isEmpty()) return;

        systemBondedPreferences()
                .edit()
                .putString(KEY_PREF_ADDR, address.trim())
                .putString(KEY_PREF_NAME, nameOrNull == null ? "" : nameOrNull)
                .putLong(KEY_PREF_SET_AT, System.currentTimeMillis())
                .apply();
    }

    private void clearPreferredDevice(String address) {
        if (pluginContext == null) return;

        SharedPreferences prefs = systemBondedPreferences();
        String current = prefs.getString(KEY_PREF_ADDR, null);
        if (address != null) {
            if (current == null) return;
            current = current.trim();
            if (!address.equals(current)) return;
        }

        prefs.edit()
                .remove(KEY_PREF_ADDR)
                .remove(KEY_PREF_NAME)
                .remove(KEY_PREF_SET_AT)
                .apply();
    }

    private String loadPreferredAddress() {
        if (pluginContext == null) return null;
        String v = systemBondedPreferences()
                .getString(KEY_PREF_ADDR, null);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private String loadPreferredName() {
        if (pluginContext == null) return null;
        String v = systemBondedPreferences()
                .getString(KEY_PREF_NAME, null);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    @Override
    public String getShortDescription() {
        return pluginContext.getString(R.string.app_name);
    }

    @Override
    public Drawable getIcon() {
        return pluginContext.getResources().getDrawable(R.drawable.weartak_companion_plugin);
    }

    @Override
    public String getDescription() {
        return pluginContext.getString(R.string.app_desc);
    }

    @Override
    public void onItemEvent(MotionEvent motionEvent) {
        showPane();
    }

}
