package com.weartak.atak.weartak_companion.plugin;

import android.content.SharedPreferences;

final class AtakUnitPreferencesObserver {
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private SharedPreferences preferences;

    AtakUnitPreferencesObserver(Runnable changed) {
        listener = (prefs, key) -> {
            if (AtakUnitPreferences.isUnitKey(key)) changed.run();
        };
    }

    void start(SharedPreferences preferences) {
        stop();
        this.preferences = preferences;
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    void stop() {
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(listener);
        preferences = null;
    }
}
