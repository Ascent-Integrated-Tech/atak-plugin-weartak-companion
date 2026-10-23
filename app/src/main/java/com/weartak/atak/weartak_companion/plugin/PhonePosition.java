package com.weartak.atak.weartak_companion.plugin;

final class PhonePosition {
    static final double UNKNOWN = 9999999.0;
    final double lat, lon, hae, ce, le;
    final long time;

    PhonePosition(double lat, double lon, double hae, double ce, double le, long time) {
        if (!PhoneFixValidator.isUsable(lat, lon)) {
            throw new IllegalArgumentException("Invalid phone position");
        }
        this.lat = lat;
        this.lon = lon;
        this.hae = Double.isFinite(hae) ? hae : UNKNOWN;
        this.ce = accuracy(ce);
        this.le = accuracy(le);
        this.time = Math.max(0, time);
    }

    private static double accuracy(double value) {
        return Double.isFinite(value) && value >= 0 ? value : UNKNOWN;
    }

    String pointXml() {
        return "<point lat='" + lat + "' lon='" + lon + "' hae='" + hae
                + "' ce='" + ce + "' le='" + le + "'/>";
    }

    String detailXml(long now) {
        return "<phone_location time='" + (time > 0 ? java.time.Instant.ofEpochMilli(time) : "")
                + "' stale='" + (time == 0 || now - time > 3000)
                + "' ageMillis='" + (time > 0 ? Math.max(0, now - time) : -1) + "'/>";
    }
}
