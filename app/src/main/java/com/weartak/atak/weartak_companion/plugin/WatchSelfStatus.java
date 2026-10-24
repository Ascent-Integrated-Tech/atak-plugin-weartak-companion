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
    static final double SKIN_TEMP_MIN_F = 59.0;
    static final double SKIN_TEMP_MAX_F = 113.0;
    static final List<String> PPE_LEVELS = Arrays.asList(
            "None", "MOPP 0", "MOPP 1", "MOPP 2", "MOPP 3", "MOPP 4");
    static final List<String> MODELS = Arrays.asList("WEAROS", "GARMIN", "WATCHOS");
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC);

    /** One physio metric with its actual measurement time. */
    static final class Sample {
        final double value;
        final long measuredAt;

        Sample(double value, long measuredAt) {
            this.value = value;
            this.measuredAt = measuredAt;
        }

        String time() { return Instant.ofEpochMilli(measuredAt).toString(); }

        String clock() { return CLOCK.format(Instant.ofEpochMilli(measuredAt)) + "Z"; }
    }

    static final class Contribution {
        static final Contribution NONE =
                new Contribution(null, null, null, null, null, null, Long.MAX_VALUE);

        final String ppeLevel;
        final String deviceUid;
        final String model;
        /** Fresh samples only; null when absent, stale or not yet plausible. */
        final Sample hr;
        final Sample skinTempF;
        final Sample exertion;
        /** Wall-clock time when this contribution changes without a new message. */
        final long nextChangeAt;

        Contribution(String ppeLevel, String deviceUid, String model, Sample hr, Sample skinTempF,
                     Sample exertion, long nextChangeAt) {
            this.ppeLevel = ppeLevel;
            this.deviceUid = deviceUid;
            this.model = model;
            this.hr = hr;
            this.skinTempF = skinTempF;
            this.exertion = exertion;
            this.nextChangeAt = nextChangeAt;
        }

        boolean isEmpty() { return ppeLevel == null && !hasPhysio(); }

        boolean hasPhysio() { return hr != null || skinTempF != null || exertion != null; }

        String hrText() { return Long.toString(Math.round(hr.value)); }

        String skinTempText() {
            // Established physiology-dashboard <skt> unit: Fahrenheit for WEAROS, Celsius otherwise.
            double value = "WEAROS".equals(model) ? skinTempF.value : (skinTempF.value - 32.0) * 5.0 / 9.0;
            return String.format(Locale.US, "%.1f", value);
        }

        String exertionText() { return String.format(Locale.US, "%.2f", exertion.value); }

        /** Newest fresh physio sample time, or null without physio. */
        String sampleTime() {
            Sample newest = null;
            for (Sample sample : new Sample[] {hr, skinTempF, exertion}) {
                if (sample != null && (newest == null || sample.measuredAt > newest.measuredAt))
                    newest = sample;
            }
            return newest == null ? null : newest.time();
        }

        /** Exact shared remarks line, or empty when the watch contributes nothing visible. */
        String remarksLine() {
            StringBuilder line = new StringBuilder();
            // Remarks token is the user's compact form (MOPP0..MOPP4); the wire level keeps its space.
            if (ppeLevel != null && !"None".equals(ppeLevel)) line.append(ppeLevel.replace(" ", ""));
            if (hr != null) segment(line, "HR: " + hrText() + " bpm @ " + hr.clock());
            if (skinTempF != null) segment(line, "Skin: "
                    + String.format(Locale.US, "%.1f", skinTempF.value) + "F @ " + skinTempF.clock());
            if (exertion != null) segment(line, "Exert: " + Math.round(exertion.value * 100)
                    + "% @ " + exertion.clock());
            return line.toString();
        }

        private static void segment(StringBuilder line, String text) {
            if (line.length() > 0) line.append("; ");
            line.append(text);
        }

        /** One device entry for the shared biometrics element; each metric carries its own time. */
        String biometricsXml() {
            if (!hasPhysio()) return "";
            StringBuilder xml = new StringBuilder("<biometrics version='").append(VERSION)
                    .append("'><device sampleTime='").append(LegacyPliCot.xml(sampleTime()))
                    .append("'><model>").append(LegacyPliCot.xml(model)).append("</model><uid>")
                    .append(LegacyPliCot.xml(deviceUid)).append("</uid>");
            if (hr != null) metric(xml, "hr", hr, hrText());
            if (skinTempF != null) metric(xml, "skt", skinTempF, skinTempText());
            if (exertion != null) metric(xml, "exert", exertion, exertionText());
            return xml.append("</device></biometrics>").toString();
        }

        private static void metric(StringBuilder xml, String name, Sample sample, String value) {
            xml.append('<').append(name).append(" sampleTime='").append(sample.time()).append("'>")
                    .append(value).append("</").append(name).append('>');
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
    private Sample hr;
    private Sample skinTempF;
    private Sample exertion;
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
            JSONObject saved = (JSONObject) ppe;
            level = saved.optString("level", "");
            if (!PPE_LEVELS.contains(level)) throw new IllegalArgumentException("Invalid PPE level");
            // savedAt is null for legacy saves whose time is unknown; it is never fabricated.
            if (!saved.has("savedAt")) throw new IllegalArgumentException("Missing savedAt");
            Object savedAt = saved.opt("savedAt");
            if (savedAt != null && savedAt != JSONObject.NULL) {
                if (!(savedAt instanceof String)) throw new IllegalArgumentException("Invalid savedAt");
                instant((String) savedAt);
            }
        } else if (ppe != null && ppe != JSONObject.NULL) {
            throw new IllegalArgumentException("Invalid PPE");
        }

        Sample nextHr = null, nextSkin = null, nextExertion = null;
        Object physio = payload.opt("physio");
        if (physio instanceof JSONObject) {
            JSONObject p = (JSONObject) physio;
            if (p.has("hr")) {
                long bpm = wholeNumber(p.opt("hr"));
                if (bpm < 20 || bpm > 250) throw new IllegalArgumentException("Invalid heart rate");
                nextHr = new Sample(bpm, instant(p.optString("measuredAt", "")));
            }
            if (p.has("sktF")) {
                double f = finite(p.opt("sktF"));
                if (f < SKIN_TEMP_MIN_F || f > SKIN_TEMP_MAX_F)
                    throw new IllegalArgumentException("Invalid skin temperature");
                nextSkin = new Sample(f, instant(p.optString("sktFMeasuredAt", "")));
            }
            if (p.has("exert")) {
                double fraction = finite(p.opt("exert"));
                if (fraction < 0 || fraction > 1) throw new IllegalArgumentException("Invalid exertion");
                nextExertion = new Sample(fraction, instant(p.optString("exertMeasuredAt", "")));
            }
            if (nextHr == null && nextSkin == null && nextExertion == null)
                throw new IllegalArgumentException("Empty physio");
        } else if (physio != null && physio != JSONObject.NULL) {
            throw new IllegalArgumentException("Invalid physio");
        }

        if (linkId.equals(link) && !nextSession.equals(session))
            throw new IllegalArgumentException("Session changed on the same link");
        if (!linkId.equals(link)) {
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
        skinTempF = nextSkin;
        exertion = nextExertion;
        receivedAt = now;
        return true;
    }

    synchronized void clear() {
        link = session = deviceUid = model = ppeLevel = null;
        seq = -1;
        hr = skinTempF = exertion = null;
        receivedAt = 0L;
    }

    synchronized Contribution current(long now) {
        if (link == null) return Contribution.NONE;
        long expiresAt = receivedAt + SNAPSHOT_TTL_MS;
        if (now >= expiresAt) {
            clear();
            return Contribution.NONE;
        }
        long[] next = {expiresAt};
        Sample freshHr = fresh(hr, now, next);
        Sample freshSkin = fresh(skinTempF, now, next);
        Sample freshExertion = fresh(exertion, now, next);
        return new Contribution(ppeLevel, deviceUid, model, freshHr, freshSkin, freshExertion, next[0]);
    }

    /** Returns the sample while its actual measurement time is fresh, tracking its next change. */
    private static Sample fresh(Sample sample, long now, long[] next) {
        if (sample == null) return null;
        if (sample.measuredAt > now + PHYSIO_FUTURE_SKEW_MS) {
            next[0] = Math.min(next[0], sample.measuredAt - PHYSIO_FUTURE_SKEW_MS);
            return null;
        }
        long expires = sample.measuredAt + PHYSIO_MAX_AGE_MS;
        if (now >= expires) return null;
        next[0] = Math.min(next[0], expires);
        return sample;
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

    private static double finite(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid number");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number))
            throw new IllegalArgumentException("Invalid number");
        return number;
    }

    private static long wholeNumber(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid number");
        double number = ((Number) value).doubleValue();
        if (number < 0 || number != Math.rint(number) || number > Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid number");
        return (long) number;
    }
}