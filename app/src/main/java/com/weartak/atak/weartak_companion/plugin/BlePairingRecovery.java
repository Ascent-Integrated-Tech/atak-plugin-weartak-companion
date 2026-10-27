package com.weartak.atak.weartak_companion.plugin;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;

/** Shared, non-destructive recovery controls for single-watch and gateway pairing. */
final class BlePairingRecovery {
    final WearTakBleClient.DiscoveredDevice device;
    final BondFailureTracker.Failure failure;
    private final String localName;

    BlePairingRecovery(WearTakBleClient.DiscoveredDevice device,
                      BondFailureTracker.Failure failure, String localName) {
        this.device = device;
        this.failure = failure;
        this.localName = localName;
    }

    void show(LinearLayout panel, Runnable retry) {
        Context context = panel.getContext();
        panel.removeAllViews();
        panel.setVisibility(View.VISIBLE);
        TextView guidance = new TextView(context);
        guidance.setTextSize(13);
        guidance.setText(context.getString(failure.likelyStale
                        ? R.string.pairing_stale_bond_guidance : R.string.pairing_rejected_guidance,
                localName, device.primaryName(), device.address));
        panel.addView(guidance);
        addButton(panel, R.string.pairing_retry, retry);
        addButton(panel, R.string.pairing_bluetooth_settings, () -> openBluetoothSettings(context));
    }

    private static void addButton(LinearLayout panel, int label, Runnable action) {
        Button button = new Button(panel.getContext());
        button.setText(label);
        button.setBackgroundResource(R.drawable.btn_gray);
        button.setTextColor(android.graphics.Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinHeight(Math.round(48 * panel.getResources().getDisplayMetrics().density));
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        button.setOnClickListener(v -> action.run());
        panel.addView(button);
    }

    private static void openBluetoothSettings(Context context) {
        MapView map = MapView.getMapView();
        Context host = map == null ? context : map.getContext();
        try {
            host.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            LogX.e("WTK/Pairing", "Unable to open Android Bluetooth settings", e);
            Toast.makeText(host, context.getString(R.string.pairing_settings_unavailable),
                    Toast.LENGTH_LONG).show();
        }
    }
}
