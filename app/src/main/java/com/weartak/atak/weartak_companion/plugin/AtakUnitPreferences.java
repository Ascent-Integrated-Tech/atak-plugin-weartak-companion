package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

final class AtakUnitPreferences {
    private static final String[] KEYS = {
            "rab_rng_units_pref", "rng_feet_display_pref", "rng_meters_display_pref",
            "speed_unit_pref", "alt_unit_pref", "rab_brg_units_pref", "rab_north_ref_pref"
    };
    // ATAK 5.6 res/xml/unit_display_preferences.xml.
    private static final String[] DEFAULTS = {"1", "5280", "2000", "0", "0", "0", "1"};
    private static final int[] MAX_VALUES = {2, 1000000, 1000000, 3, 1, 1, 2};

    private AtakUnitPreferences() {}

    static boolean isUnitKey(String key) {
        if (key == null) return true; // SharedPreferences.clear() on newer Android versions.
        for (String candidate : KEYS) if (candidate.equals(key)) return true;
        return false;
    }

    static Map<String, String> read(Map<String, ?> preferences) {
        Map<String, String> units = new LinkedHashMap<>();
        for (int i = 0; i < KEYS.length; i++) {
            Object stored = preferences.get(KEYS[i]);
            String value = stored == null ? DEFAULTS[i] : stored.toString();
            int minimum = i == 1 || i == 2 ? 1 : 0;
            try {
                int number = Integer.parseInt(value);
                if (number < minimum || number > MAX_VALUES[i]) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid ATAK unit preference: " + KEYS[i], error);
            }
            units.put(KEYS[i], value);
        }
        return units;
    }

    static JSONObject command(Map<String, String> units) throws JSONException {
        return new JSONObject().put("msg_type", "unit_prefs")
                .put("payload", new JSONObject(units));
    }

    static boolean garminSupported(JSONObject hello) {
        Object version = hello == null ? null : hello.opt("unitPrefsVersion");
        return version instanceof Number && ((Number) version).doubleValue() == 1.0d;
    }

    static Map<String, Object> garminStatus(Map<String, Object> status, Map<String, String> units) {
        Map<String, Object> result = new LinkedHashMap<>(status);
        result.put("unitPrefsVersion", 1);
        result.put("unitPrefs", new LinkedHashMap<>(units));
        return result;
    }
}
