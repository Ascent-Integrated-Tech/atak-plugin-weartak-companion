package com.weartak.atak.weartak_companion.plugin;

import com.atakmap.android.cot.detail.TakVersionDetailHandler;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.util.ATAKConstants;

/** Local ATAK identity only; watch observations must never supply phone metadata. */
final class PhoneIdentity {
    private PhoneIdentity() {}

    static String callsign(MapView mapView) {
        return mapView == null ? "" : value(mapView.getDeviceCallsign(), "");
    }

    static String team(MapView mapView) {
        return value(mapView == null ? null
                : mapView.getMapData().getMetaString("deviceTeam", null), "Blue");
    }

    static String role(MapView mapView) {
        return value(mapView == null ? null : AtakPreferences.getInstance(mapView.getContext())
                .get("atakRoleType", "Team Member"), "Team Member");
    }

    static LegacyPliCot.Metadata metadata(MapView mapView) {
        AtakPreferences preferences = AtakPreferences.getInstance(mapView.getContext());
        if (!preferences.get("dispatchTAKVersionNumber", true)) {
            return new LegacyPliCot.Metadata(team(mapView), role(mapView), null, null, null, null);
        }
        String version = TakVersionDetailHandler.getVersion();
        String brand = ATAKConstants.getVersionBrand();
        if (version != null && !version.isEmpty() && brand != null && !brand.isEmpty()) {
            version += "-" + brand;
        }
        return new LegacyPliCot.Metadata(team(mapView), role(mapView),
                ATAKConstants.getBrand(), version,
                TakVersionDetailHandler.getDeviceDescription(), TakVersionDetailHandler.getDeviceOS());
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
