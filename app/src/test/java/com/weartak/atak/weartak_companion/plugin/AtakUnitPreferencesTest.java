package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class AtakUnitPreferencesTest {
    @Test public void absentPreferencesUseAllSevenAtakDefaultsNotWatchDefaults() {
        Map<String, String> units = AtakUnitPreferences.read(Collections.emptyMap());
        assertEquals(7, units.size());
        assertEquals("1", units.get("rab_rng_units_pref"));
        assertEquals("5280", units.get("rng_feet_display_pref"));
        assertEquals("2000", units.get("rng_meters_display_pref"));
        assertEquals("0", units.get("speed_unit_pref"));
        assertEquals("0", units.get("alt_unit_pref"));
        assertEquals("0", units.get("rab_brg_units_pref"));
        assertEquals("1", units.get("rab_north_ref_pref"));
    }

    @Test public void preservesIndependentUnitsAndCustomThresholdsAsStrings() {
        Map<String, Object> source = new HashMap<>();
        source.put("rab_rng_units_pref", "0");
        source.put("rng_feet_display_pref", "1000");
        source.put("rng_meters_display_pref", 1500);
        source.put("speed_unit_pref", "2");
        source.put("alt_unit_pref", "1");
        source.put("rab_brg_units_pref", "1");
        source.put("rab_north_ref_pref", "2");
        source.put("locationCallsign", "NOT A UNIT");
        Map<String, String> units = AtakUnitPreferences.read(source);
        assertEquals(7, units.size());
        for (String key : units.keySet()) assertEquals(source.get(key).toString(), units.get(key));
        assertFalse(units.containsKey("locationCallsign"));
        assertEquals(8, source.size());
    }

    @Test public void supportsEveryEnumeratedAtakUnit() {
        String[] keys = {"rab_rng_units_pref", "speed_unit_pref", "alt_unit_pref",
                "rab_brg_units_pref", "rab_north_ref_pref"};
        int[] maxima = {2, 3, 1, 1, 2};
        for (int i = 0; i < keys.length; i++) {
            for (int value = 0; value <= maxima[i]; value++) {
                assertEquals(Integer.toString(value), AtakUnitPreferences.read(
                        Collections.singletonMap(keys[i], Integer.toString(value))).get(keys[i]));
            }
        }
    }

    @Test public void invalidKnownValuesFailExplicitlyRatherThanReplacingUserChoice() {
        String[] keys = {"rab_rng_units_pref", "speed_unit_pref", "alt_unit_pref",
                "rab_brg_units_pref", "rab_north_ref_pref", "rng_feet_display_pref",
                "rng_meters_display_pref"};
        for (String key : keys) {
            for (Object value : new Object[]{"", "-1", "1000001", "garbage", true, 1.5}) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> AtakUnitPreferences.read(Collections.singletonMap(key, value)));
                assertTrue(error.getMessage().contains(key));
            }
        }
        for (String key : new String[]{"rng_feet_display_pref", "rng_meters_display_pref"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AtakUnitPreferences.read(Collections.singletonMap(key, "0")));
            for (String value : new String[]{"1", "1000000"}) {
                assertEquals(value, AtakUnitPreferences.read(
                        Collections.singletonMap(key, value)).get(key));
            }
        }
    }

    @Test public void liveCommandContainsOnlyUnitsAndNoManagementOrServerMutation() throws Exception {
        Map<String, String> units = AtakUnitPreferences.read(Collections.emptyMap());
        JSONObject command = AtakUnitPreferences.command(units);
        assertEquals(2, command.length());
        assertEquals("unit_prefs", command.getString("msg_type"));
        for (String key : units.keySet()) {
            assertEquals(units.get(key), command.getJSONObject("payload").getString(key));
        }
        assertEquals(7, command.getJSONObject("payload").length());
    }

    @Test public void bulkSettingsIncludeUnitsInBothSetupAndManagedModes() throws Exception {
        Map<String, String> units = AtakUnitPreferences.read(
                Collections.singletonMap("rab_rng_units_pref", "0"));
        for (boolean managed : new boolean[]{false, true}) {
            JSONObject payload = SettingsPayloadBuilder.build(Collections.emptyList(), 5, "PHONE",
                    "Blue", "Medic", managed, units).getJSONObject("payload");
            JSONObject sent = payload.getJSONObject("unitPrefs");
            assertEquals(7, sent.length());
            for (String key : units.keySet()) assertEquals(units.get(key), sent.getString(key));
            assertEquals(managed, payload.getBoolean("phoneManagedSettings"));
            assertEquals(5, payload.getInt("reportIntSecs"));
            assertEquals(0, payload.getJSONArray("takServerList").length());
        }
    }

    @Test public void onlyExplicitSupportedGarminVersionEnablesUnits() throws Exception {
        assertFalse(AtakUnitPreferences.garminSupported(null));
        assertFalse(AtakUnitPreferences.garminSupported(new JSONObject()));
        for (Object version : new Object[]{0, 2, 1.5, "1", true, JSONObject.NULL}) {
            assertFalse(AtakUnitPreferences.garminSupported(
                    new JSONObject().put("unitPrefsVersion", version)));
        }
        assertTrue(AtakUnitPreferences.garminSupported(new JSONObject().put("unitPrefsVersion", 1)));
    }

    @Test public void garminUnitsPreserveRelayCapabilitiesAndSessionWithoutMutatingPriorStatus() {
        Map<String, Object> previous = new HashMap<>();
        previous.put("relaySession", "session");
        previous.put("reliableDelivery", true);
        previous.put("phoneLocation", true);
        previous.put("chatGroups", false);
        previous.put("bloodhoundVersion", 1);
        Map<String, String> units = AtakUnitPreferences.read(Collections.emptyMap());
        Map<String, Object> status = AtakUnitPreferences.garminStatus(previous, units);
        for (String key : previous.keySet()) assertEquals(previous.get(key), status.get(key));
        assertEquals(1, status.get("unitPrefsVersion"));
        assertEquals(units, status.get("unitPrefs"));
        assertFalse(previous.containsKey("unitPrefs"));
        units.put("rab_rng_units_pref", "0");
        assertNotEquals(units, status.get("unitPrefs"));
        Map<String, Object> updated = AtakUnitPreferences.garminStatus(status, units);
        assertEquals(units, updated.get("unitPrefs"));
        assertEquals("session", updated.get("relaySession"));
    }
}
