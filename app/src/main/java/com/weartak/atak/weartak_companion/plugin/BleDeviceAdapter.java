package com.weartak.atak.weartak_companion.plugin;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.content.res.Resources;
import com.atakmap.android.maps.MapView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class BleDeviceAdapter extends RecyclerView.Adapter<BleDeviceAdapter.VH> {

    public interface Listener {
        void onConnectClicked(WearTakBleClient.DiscoveredDevice device);
        void onDisconnectClicked();
        void onSettingsClicked(WearTakBleClient.DiscoveredDevice device);
    }

    private final Listener listener;
    private final List<WearTakBleClient.DiscoveredDevice> items = new ArrayList<>();

    private String connectingAddress = null;
    private String connectedAddress = null;
    private boolean settingsVisible;
    private boolean disconnectPending;
    private String panelStatus;

    void setPanelStatus(String status) {
        if (java.util.Objects.equals(panelStatus, status)) return;
        panelStatus = status;
        notifyDataSetChanged();
    }

    public BleDeviceAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setDevices(List<WearTakBleClient.DiscoveredDevice> devices) {
        items.clear();
        if (devices != null) items.addAll(devices);
        notifyDataSetChanged();
    }

    public void setConnecting(String address) {
        connectingAddress = address;
        notifyDataSetChanged();
    }

    public void setConnected(String address) {
        connectedAddress = address;
        connectingAddress = null;
        notifyDataSetChanged();
    }

    public void clearConnectionMarkers() {
        connectingAddress = null;
        connectedAddress = null;
        notifyDataSetChanged();
    }

    public void setSettingsVisible(boolean visible) {
        settingsVisible = visible;
        notifyDataSetChanged();
    }

    public void setDisconnectPending(boolean pending) {
        disconnectPending = pending;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ble_device, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        WearTakBleClient.DiscoveredDevice d = items.get(position);

        bindNames(h.deviceName, h.bondedName, d, panelStatus);
        h.deviceMac.setVisibility(View.GONE);
        h.rssiBadge.setVisibility(View.GONE);
        h.deviceState.setVisibility(View.GONE);

        boolean isConnected = d.address != null && d.address.equals(connectedAddress);
        boolean isConnecting = d.address != null && d.address.equals(connectingAddress);

        h.actionButton.setOnClickListener(null);
        h.settingsButton.setVisibility(settingsVisible ? View.VISIBLE : View.GONE);
        h.settingsButton.setEnabled(isConnected);
        h.settingsButton.setAlpha(isConnected ? 1f : 0.4f);
        h.settingsButton.setOnClickListener(v -> {
            if (listener != null && isConnected) listener.onSettingsClicked(d);
        });

        if (isConnected) {
            h.deviceState.setText(disconnectPending ? "Confirming unlock…" : "Connected");
            h.actionButton.setText(disconnectPending ? "Wait" : "Disconnect");
            h.actionButton.setEnabled(!disconnectPending);
            h.actionButton.setOnClickListener(v -> {
                if (listener != null) listener.onDisconnectClicked();
            });
        } else if (isConnecting) {
            h.deviceState.setText("Connecting…");
            h.actionButton.setText("Connecting");
            h.actionButton.setEnabled(false);
        } else {
            h.deviceState.setText("");
            h.actionButton.setText("Connect");
            h.actionButton.setEnabled(true);
            h.actionButton.setOnClickListener(v -> {
                if (listener != null) listener.onConnectClicked(d);
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static void bindNames(TextView name, TextView bondedName,
                          WearTakBleClient.DiscoveredDevice device) {
        bindNames(name, bondedName, device, null);
    }

    static void bindNames(TextView name, TextView bondedName,
                          WearTakBleClient.DiscoveredDevice device, String status) {
        name.setText(identityLines(name.getResources(), device, localCallsign(), status));
        bondedName.setText("");
        bondedName.setVisibility(View.GONE);
    }

    static String identityLines(Resources resources, WearTakBleClient.DiscoveredDevice device,
                                String localCallsign, String status) {
        return identityLines(resources, device, localCallsign, status, false);
    }

    static String identityLines(Resources resources, WearTakBleClient.DiscoveredDevice device,
                                String localCallsign, String status, boolean gateway) {
        if (device == null) return identityLines(resources, localCallsign, status,
                null, 0, null, null, gateway);
        String watch = nonBlank(device.standaloneCallsign);
        if (watch == null) watch = nonBlank(device.advertisedCallsign);
        return identityLines(resources, localCallsign, status, device.address,
                device.rssi, watch, device.productName, gateway);
    }

    static String identityLines(Resources resources, String localCallsign, String status,
                                String address, int rssi, String watch, String product, boolean gateway) {
        String unknown = resources.getString(R.string.watch_identity_unavailable);
        String strength = rssi == 0 ? unknown : resources.getString(R.string.watch_identity_dbm, rssi);
        if (gateway) return resources.getString(R.string.watch_gateway_identity_lines,
                available(address, unknown), strength, available(watch, unknown), available(product, unknown));
        return resources.getString(R.string.watch_identity_lines,
                available(localCallsign, unknown), available(status, unknown),
                available(address, unknown), strength, available(watch, unknown), available(product, unknown));
    }

    private static String available(String value, String unknown) {
        String text = nonBlank(value);
        return text == null ? unknown : text;
    }

    static String localCallsign() {
        MapView map = MapView.getMapView();
        return map == null ? null : map.getDeviceCallsign();
    }

    static String watchLabel(Resources resources, WearTakBleClient.DiscoveredDevice device,
                             String localCallsign) {
        return watchLabel(resources, device, localCallsign, device.callsign);
    }

    static String watchLabel(Resources resources, WearTakBleClient.DiscoveredDevice device,
                             String localCallsign, String pairedCallsign) {
        String watch = nonBlank(pairedCallsign);
        if (watch == null) watch = nonBlank(device.advertisedCallsign);
        String product = nonBlank(device.productName);
        String label = watch == null ? product : product == null || watch.equalsIgnoreCase(product)
                ? watch : watch + ", " + product;
        if (label == null) label = nonBlank(device.primaryName());
        if (label == null) label = resources.getString(R.string.watch_name_unavailable);
        String local = nonBlank(localCallsign);
        return device.bonded && local != null
                ? resources.getString(R.string.watch_identity_bonded, label, local) : label;
    }

    private static String nonBlank(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView deviceName, bondedName, deviceMac, rssiBadge, deviceState;
        Button actionButton;
        View settingsButton;

        VH(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.deviceName);
            bondedName = itemView.findViewById(R.id.deviceBondedName);
            deviceMac = itemView.findViewById(R.id.deviceMac);
            rssiBadge = itemView.findViewById(R.id.rssiBadge);
            deviceState = itemView.findViewById(R.id.deviceState);
            actionButton = itemView.findViewById(R.id.actionButton);
            settingsButton = itemView.findViewById(R.id.watchSettingsButton);
        }
    }
}