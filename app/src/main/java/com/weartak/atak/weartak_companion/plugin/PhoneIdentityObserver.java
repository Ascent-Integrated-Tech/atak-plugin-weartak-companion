package com.weartak.atak.weartak_companion.plugin;

import android.content.SharedPreferences;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Marker;

/** Observe both preference writes and the self-marker updates that follow them. */
final class PhoneIdentityObserver {
    private static final String[] KEYS = {"callsign", "team", "atakRoleType", "role"};
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged;
    private final MapItem.OnMetadataChangedListener metadataChanged;
    private SharedPreferences preferences;
    private Marker self;

    PhoneIdentityObserver(Runnable changed) {
        preferencesChanged = (prefs, key) -> changed.run();
        metadataChanged = (item, key) -> changed.run();
    }

    void start(SharedPreferences preferences, Marker self) {
        stop();
        this.preferences = preferences;
        this.self = self;
        preferences.registerOnSharedPreferenceChangeListener(preferencesChanged);
        if (self != null) {
            for (String key : KEYS) self.addOnMetadataChangedListener(key, metadataChanged);
        }
    }

    void stop() {
        if (preferences != null)
            preferences.unregisterOnSharedPreferenceChangeListener(preferencesChanged);
        if (self != null) {
            for (String key : KEYS) self.removeOnMetadataChangedListener(key, metadataChanged);
        }
        preferences = null;
        self = null;
    }
}
