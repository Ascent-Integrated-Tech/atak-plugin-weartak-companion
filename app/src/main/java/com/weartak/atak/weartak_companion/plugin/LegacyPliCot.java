package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

import java.time.Instant;
import java.util.Locale;

/** Legacy watch telemetry is emitted as PHONE/EUD PLI, not as a watch entity. */
final class LegacyPliCot {
    private LegacyPliCot() {}

    static final class Metadata {
        final String team, role, platform, version, device, os;

        Metadata(String team, String role, String platform, String version, String device, String os) {
            this.team = team;
            this.role = role;
            this.platform = platform;
            this.version = version;
            this.device = device;
            this.os = os;
        }
    }

    static String build(JSONObject payload, String phoneUid, String phoneCallsign,
                        double lat, double lon, double hae, double ce, double le,
                        Metadata phone, long now) {
        return build(payload, phoneUid, phoneCallsign, lat, lon, hae, ce, le, phone, now,
                null, WatchSelfStatus.Contribution.NONE);
    }

    /**
     * Remarks are the phone user's ATAK remarks (including any Companion-managed watch line);
     * watch physio is published only from a fresh SELF_STATUS contribution.
     */
    static String build(JSONObject payload, String phoneUid, String phoneCallsign,
                        double lat, double lon, double hae, double ce, double le,
                        Metadata phone, long now, String phoneRemarks,
                        WatchSelfStatus.Contribution watch) {
        if (!PhoneFixValidator.isUsable(lat, lon)) {
            throw new IllegalArgumentException("PLI requires a real phone position");
        }
        String time = string(payload, "tStart", Instant.ofEpochMilli(now).toString());
        String stale = string(payload, "tStale", Instant.ofEpochMilli(now + 120000).toString());
        StringBuilder detail = new StringBuilder();
        String remarks = phoneRemarks == null ? "" : phoneRemarks.trim();
        if (!remarks.isEmpty()) detail.append("<remarks>").append(xml(remarks)).append("</remarks>");
        detail.append("<contact endpoint='*:-1:stcp' callsign='").append(xml(phoneCallsign))
                .append("'/><__group role='").append(xml(phone.role)).append("' name='")
                .append(xml(phone.team)).append("'/>");
        if (present(phone.platform) || present(phone.version) || present(phone.device) || present(phone.os)) {
            detail.append("<takv");
            attribute(detail, "platform", phone.platform);
            attribute(detail, "version", phone.version);
            attribute(detail, "device", phone.device);
            attribute(detail, "os", phone.os);
            detail.append("/>");
        }
        detail.append("<track course='").append(number(payload.optDouble("course", 0.0)))
                .append("' speed='").append(number(payload.optDouble("speed", 0.0))).append("'/>");
        Integer battery = integer(payload, "battery_percent");
        if (battery == null) battery = integer(payload, "bat");
        if (battery != null) detail.append("<status battery='")
                .append(Math.max(0, Math.min(100, battery))).append("'/>");
        if (watch != null && watch.model != null) {
            detail.append(watch.biometricsXml());
        } else {
            // Watches without an active SELF_STATUS snapshot keep their existing legacy heart rate.
            Integer hr = integer(payload, "hr");
            if (hr == null) hr = integer(payload, "heart_rate");
            if (hr == null) hr = integer(payload, "heartRateBpm");
            if (hr != null && hr > 0) detail.append("<physio hr='").append(hr).append("'/>");
        }

        return "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                + "<event version='2.0' uid='" + xml(phoneUid) + "' type='a-f-G-U-C' "
                + "time='" + xml(time) + "' start='" + xml(time) + "' stale='" + xml(stale)
                + "' how='h-g-i-g-o'><point lat='" + Double.toString(lat)
                + "' lon='" + Double.toString(lon)
                + "' hae='" + String.format(Locale.US, "%.1f", hae)
                + "' ce='" + number(ce) + "' le='" + number(le) + "'/><detail>"
                + detail + "</detail></event>";
    }

    private static void attribute(StringBuilder xml, String key, String value) {
        if (present(value)) xml.append(" ").append(key).append("='").append(xml(value)).append("'");
    }

    private static boolean present(String value) { return value != null && !value.isEmpty(); }

    private static String string(JSONObject payload, String key, String fallback) {
        String value = payload.optString(key, null);
        return present(value) ? value : fallback;
    }

    private static Integer integer(JSONObject payload, String key) {
        Object value = payload.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                String text = ((String) value).trim();
                if (text.isEmpty()) return null;
                return (int) Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                // Legacy telemetry may omit or send a non-numeric sensor reading.
            }
        }
        return null;
    }

    private static String number(double value) {
        return ((int) value) == value ? Integer.toString((int) value) : Double.toString(value);
    }

    static String xml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
