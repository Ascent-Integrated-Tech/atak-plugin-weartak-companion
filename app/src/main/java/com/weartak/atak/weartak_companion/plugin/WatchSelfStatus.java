package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * SELF_STATUS v1: watch-owned PPE and physio contributions to the phone's own PLI.
 * Each message is a full snapshot bound to the current authorized link and watch session.
 */
final class WatchSelfStatus {
    static final String MESSAGE = "self_status";
    static final String CAPABILITY = "self_status_capability";
    static final int VERSION = 1;
    static final long PHYSIO_MAX_AGE_MS = 120_000L;
    static final long PHYSIO_FUTURE_SKEW_MS = 30_000L;
    static final long SNAPSHOT_TTL_MS = 300_000L;
    static final List<String> PPE_LEVELS = Arrays.asList(
            "None", "MOPP 0", "MOPP 1", "MOPP 2", "MOPP 3", "MOPP 4");
    static final List<String> MODELS = Arrays.asList("WEAROS", "GARMIN", "WATCHOS");
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC);

    static final class Contribution {
        static final Contribution NONE = new Contribution(null, null, null, null, 0L, Long.MAX_VALUE);

        final String ppeLevel;
        final String deviceUid;
        final String model;
        final Integer hr;
        final long measuredAt;
        /** Wall-clock time when this contribution changes without a new message. */
        final long nextChangeAt;

        Contribution(String ppeLevel, String deviceUid, String model, Integer hr,
                     long measuredAt, long nextChangeAt) {
            this.ppeLevel = ppeLevel;
            this.deviceUid = deviceUid;
            this.model = model;
            this.hr = hr;
            this.measuredAt = measuredAt;
            this.nextChangeAt = nextChangeAt;
        }

        boolean isEmpty() { return ppeLevel == null && hr == null; }

        /** Exact shared remarks line, or empty when the watch contributes nothing. */
        String remarksLine() {
            StringBuilder line = new StringBuilder();
            if (ppeLevel != null) line.append("PPE: ").append(ppeLevel);
            if (hr != null) {
                if (line.length() > 0) line.append("; ");
                line.append("HR: ").append(hr).append(" bpm @ ")
                        .append(CLOCK.format(Instant.ofEpochMilli(measuredAt))).append('Z');
            }
            return line.toString();
        }

        String sampleTime() { return hr == null ? null : Instant.ofEpochMilli(measuredAt).toString(); }

        /** Existing physiology-dashboard schema with version and sample-time attributes. */
        String biometricsXml() {
            if (hr == null) return "";
            return "<biometrics version='" + VERSION + "'><device sampleTime='"
                    + LegacyPliCot.xml(sampleTime()) + "'><model>" + LegacyPliCot.xml(model)
                    + "</model><uid>" + LegacyPliCot.xml(deviceUid) + "</uid><hr>" + hr
                    + "</hr></device></biometrics>";
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Contribution)) return false;
            Contribution c = (Contribution) other;
            return remarksLine().equals(c.remarksLine()) && biometricsXml().equals(c.biometricsXml());
        }

        @Override public int hashCode() { return remarksLine().hashCode() * 31 + biometricsXml().hashCode(); }
    }

    private String link;
    private String session;
    private long seq = -1;
    private String deviceUid;
    private String model;
    private String ppeLevel;
    private Integer hr;
    private long measuredAt;
    private long receivedAt;

    /**
     * Applies a snapshot from the current authorized link. Returns false for an out-of-order or
     * duplicate sequence; throws IllegalArgumentException for an invalid snapshot.
     */
    synchronized boolean accept(String linkId, JSONObject payload, long now) {
        if (!text(linkId, 256)) throw new IllegalArgumentException("Missing link");
        if (payload == null) throw new IllegalArgumentException("Missing payload");
        Object version = payload.opt("version");
        if (!(version instanceof Number) || ((Number) version).doubleValue() != VERSION)
            throw new IllegalArgumentException("Unsupported self_status version");
        String nextSession = string(payload, "session");
        long nextSeq = wholeNumber(payload.opt("seq"));
        String uid = string(payload, "deviceUid");
        String nextModel = payload.optString("model", "");
        if (!MODELS.contains(nextModel)) throw new IllegalArgumentException("Invalid model");

        String level = null;
        Object ppe = payload.opt("ppe");
        if (ppe instanceof JSONObject) {
            level = ((JSONObject) ppe).optString("level", "");
            if (!PPE_LEVELS.contains(level)) throw new IllegalArgumentException("Invalid PPE level");
            instant(((JSONObject) ppe).optString("savedAt", ""));
        } else if (ppe != null && ppe != JSONObject.NULL) {
            throw new IllegalArgumentException("Invalid PPE");
        }

        Integer nextHr = null;
        long nextMeasuredAt = 0L;
        Object physio = payload.opt("physio");
        if (physio instanceof JSONObject) {
            Object rate = ((JSONObject) physio).opt("hr");
            long bpm = wholeNumber(rate);
            if (bpm < 20 || bpm > 250) throw new IllegalArgumentException("Invalid heart rate");
            nextHr = (int) bpm;
            nextMeasuredAt = instant(((JSONObject) physio).optString("measuredAt", ""));
        } else if (physio != null && physio != JSONObject.NULL) {
            throw new IllegalArgumentException("Invalid physio");
        }

        if (!linkId.equals(link) || !nextSession.equals(session)) {
            link = linkId;
            session = nextSession;
            seq = -1;
        } else if (nextSeq <= seq) {
            return false;
        }
        seq = nextSeq;
        deviceUid = uid;
        model = nextModel;
        ppeLevel = level;
        hr = nextHr;
        measuredAt = nextMeasuredAt;
        receivedAt = now;
        return true;
    }

    synchronized void clear() {
        link = session = deviceUid = model = ppeLevel = null;
        seq = -1;
        hr = null;
        measuredAt = receivedAt = 0L;
    }

    synchronized Contribution current(long now) {
        if (link == null) return Contribution.NONE;
        long expiresAt = receivedAt + SNAPSHOT_TTL_MS;
        if (now >= expiresAt) {
            clear();
            return Contribution.NONE;
        }
        long next = expiresAt;
        Integer freshHr = null;
        if (hr != null) {
            long physioExpires = measuredAt + PHYSIO_MAX_AGE_MS;
            if (measuredAt > now + PHYSIO_FUTURE_SKEW_MS) {
                next = Math.min(next, measuredAt - PHYSIO_FUTURE_SKEW_MS);
            } else if (now < physioExpires) {
                freshHr = hr;
                next = Math.min(next, physioExpires);
            }
        }
        return new Contribution(ppeLevel, deviceUid, model, freshHr, measuredAt, next);
    }

    private static boolean text(String value, int max) {
        if (value == null || value.trim().isEmpty() || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }

    private static String string(JSONObject payload, String key) {
        Object value = payload.opt(key);
        if (!(value instanceof String) || !text((String) value, 128))
            throw new IllegalArgumentException("Invalid " + key);
        return (String) value;
    }

    private static long instant(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (java.time.DateTimeException error) {
            throw new IllegalArgumentException("Invalid timestamp", error);
        }
    }

    private static long wholeNumber(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid number");
        double number = ((Number) value).doubleValue();
        if (number < 0 || number != Math.rint(number) || number > Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid number");
        return (long) number;
    }
}
