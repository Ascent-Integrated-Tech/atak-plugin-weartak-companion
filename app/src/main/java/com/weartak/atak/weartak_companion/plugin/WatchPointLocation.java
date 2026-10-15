package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

/** A shared phone identity must not replace the location of a watch-selected point. */
final class WatchPointLocation {
    final double lat, lon, hae, ce, le;

    WatchPointLocation(JSONObject payload, double fallbackLat, double fallbackLon,
                       double fallbackHae, double fallbackCe, double fallbackLe) throws JSONException {
        boolean hasLocation = payload.has("lat") || payload.has("lon");
        lat = hasLocation ? payload.getDouble("lat") : fallbackLat;
        lon = hasLocation ? payload.getDouble("lon") : fallbackLon;
        hae = payload.has("hae") ? payload.getDouble("hae") : fallbackHae;
        ce = payload.has("ce") ? payload.getDouble("ce") : fallbackCe;
        le = payload.has("le") ? payload.getDouble("le") : fallbackLe;
        if (!Double.isFinite(lat) || Math.abs(lat) > 90
                || !Double.isFinite(lon) || Math.abs(lon) > 180
                || !Double.isFinite(hae) || !Double.isFinite(ce) || !Double.isFinite(le)) {
            throw new IllegalArgumentException("Invalid watch point coordinates");
        }
    }
}
