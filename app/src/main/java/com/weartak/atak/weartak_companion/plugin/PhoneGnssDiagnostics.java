package com.weartak.atak.weartak_companion.plugin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

final class PhoneGnssDiagnostics {
    interface Listener {
        void onSnapshot(JSONObject envelope);
    }

    private static final String TAG = "WTK/LocationDiagnostics";
    private static final long REPORT_INTERVAL_MS = 30_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context context;
    private LocationManager locationManager;
    private LocationListener passiveFixListener;
    private GnssStatus.Callback statusCallback;
    private GnssMeasurementsEvent.Callback measurementsCallback;
    private Listener listener;
    private String session;
    private long readySession = -1L;
    private long lastReportMs;
    private Integer satelliteCount;
    private Integer usedSatelliteCount;
    private Float meanCn0DbHz;
    private Double maxAgcDb;
    private JSONObject payload = new JSONObject();
    private boolean running;
    private String assessedQuality = "UNKNOWN";
    private String recoveryCandidate;
    private int recoveryCount;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!running) return;
            publish(true);
            handler.postDelayed(this, REPORT_INTERVAL_MS);
        }
    };

    @SuppressLint("MissingPermission")
    synchronized void start(Context pluginContext, String diagnosticSession, long bleSession,
                            Listener snapshotListener) {
        if (!LocationDiagnosticsProtocol.isValidSession(diagnosticSession) || bleSession < 0) {
            throw new IllegalArgumentException("Invalid bonded diagnostics session");
        }
        stop();
        context = pluginContext.getApplicationContext();
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        session = diagnosticSession;
        readySession = bleSession;
        listener = snapshotListener;
        running = true;
        payload = basePayload();

        if (locationManager == null) {
            setUnavailable("Location service unavailable");
            publish(true);
            return;
        }
        boolean hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) {
            setUnavailable("Location permission not granted");
            publish(true);
            handler.postDelayed(heartbeat, REPORT_INTERVAL_MS);
            return;
        }

        try {
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            payload.put("providerAvailable", gpsEnabled);
            if (!gpsEnabled) payload.put("error", "GNSS provider disabled");
        } catch (JSONException error) {
            setUnavailable("Unable to record GNSS provider status");
            Log.e(TAG, "Unable to record location provider status", error);
        } catch (SecurityException error) {
            setUnavailable("Location permission denied while checking provider");
            Log.w(TAG, "Unable to check location provider", error);
        } catch (RuntimeException error) {
            setUnavailable("Location provider status unavailable");
            Log.w(TAG, "Unable to check location provider", error);
        }

        if (hasFine) registerGnssCallbacks();
        else {
            try {
                payload.put("satelliteStatusSupported", false)
                        .put("measurementsSupported", false)
                        .put("error", "Fine location permission required for GNSS diagnostics");
            } catch (JSONException error) {
                Log.e(TAG, "Unable to record permission status", error);
            }
        }
        registerPassiveFixListener();
        publish(true);
        handler.postDelayed(heartbeat, REPORT_INTERVAL_MS);
    }

    synchronized boolean stopForSession(JSONObject envelope, long bleSession) {
        if (!running || bleSession != readySession ||
                LocationDiagnosticsProtocol.parse(envelope, LocationDiagnosticsProtocol.STOP, session) == null) {
            return false;
        }
        stop();
        return true;
    }

    synchronized void stop() {
        running = false;
        handler.removeCallbacks(heartbeat);
        if (locationManager != null) {
            if (passiveFixListener != null) {
                try {
                    locationManager.removeUpdates(passiveFixListener);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to remove passive location observer", error);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && statusCallback != null) {
                try {
                    locationManager.unregisterGnssStatusCallback(statusCallback);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to remove GNSS status observer", error);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && measurementsCallback != null) {
                try {
                    locationManager.unregisterGnssMeasurementsCallback(measurementsCallback);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to remove GNSS measurement observer", error);
                }
            }
        }
        locationManager = null;
        passiveFixListener = null;
        statusCallback = null;
        measurementsCallback = null;
        listener = null;
        session = null;
        readySession = -1L;
        satelliteCount = null;
        usedSatelliteCount = null;
        meanCn0DbHz = null;
        maxAgcDb = null;
        assessedQuality = "UNKNOWN";
        recoveryCandidate = null;
        recoveryCount = 0;
    }

    synchronized String session() { return session; }
    synchronized long readySession() { return readySession; }
    synchronized JSONObject payloadSnapshot() {
        try {
            return new JSONObject(payload.toString());
        } catch (JSONException error) {
            Log.e(TAG, "Unable to copy GNSS diagnostic state", error);
            return new JSONObject();
        }
    }

    private void registerPassiveFixListener() {
        passiveFixListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!running || location == null) return;
                long ageMs = Math.max(0L,
                        (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1_000_000L);
                try {
                    String provider = location.getProvider() == null ? "unknown" : location.getProvider();
                    String safeProvider = provider.replaceAll("[^A-Za-z0-9 _-]", "");
                    provider = safeProvider.substring(0, Math.min(32, safeProvider.length()));
                    payload.put("source", provider)
                            .put("ageMs", ageMs)
                            .put("accuracyM", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL)
                            .put("speedMps", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL)
                            .put("speedAccuracyMps", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                    location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : JSONObject.NULL)
                            .put("bearingAccuracyDeg", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                    location.hasBearingAccuracy() ? location.getBearingAccuracyDegrees() : JSONObject.NULL);
                    updateSatellitePayload();
                    publish(false);
                } catch (JSONException error) {
                    Log.e(TAG, "Unable to serialize observed location quality", error);
                }
            }
            @Override public void onProviderEnabled(String provider) {
                if (LocationManager.GPS_PROVIDER.equals(provider)) updateProvider(true, null);
            }
            @Override public void onProviderDisabled(String provider) {
                if (LocationManager.GPS_PROVIDER.equals(provider)) updateProvider(false, "GNSS provider disabled");
            }
            @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) { }
        };
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER, REPORT_INTERVAL_MS, 0f,
                    passiveFixListener, Looper.getMainLooper());
        } catch (SecurityException error) {
            setUnavailable("Location permission denied for passive observation");
            Log.w(TAG, "Passive location observation denied", error);
        } catch (RuntimeException error) {
            setUnavailable("Passive location provider unavailable");
            Log.w(TAG, "Passive location observer unavailable", error);
        }
    }

    private void registerGnssCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            try {
                payload.put("satelliteStatusSupported", false)
                        .put("measurementsSupported", false);
            } catch (JSONException error) {
                Log.e(TAG, "Unable to record GNSS API support", error);
            }
            return;
        }
        try {
            statusCallback = new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus status) {
                    if (!running) return;
                    int used = 0;
                    float cn0 = 0f;
                    int cn0Count = 0;
                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        if (status.usedInFix(i)) used++;
                        float value = status.getCn0DbHz(i);
                        if (!Float.isNaN(value) && !Float.isInfinite(value)) {
                            cn0 += value;
                            cn0Count++;
                        }
                    }
                    satelliteCount = status.getSatelliteCount();
                    usedSatelliteCount = used;
                    meanCn0DbHz = cn0Count == 0 ? null : cn0 / cn0Count;
                    updateSatellitePayload();
                    publish(false);
                }
                @Override public void onStarted() { updateProvider(true, null); }
                @Override public void onStopped() { markReceiverInactive(); }
            };
            locationManager.registerGnssStatusCallback(statusCallback, handler);
            payload.put("satelliteStatusSupported", true);
        } catch (SecurityException error) {
            setUnavailable("GNSS status permission denied");
            Log.w(TAG, "GNSS status observation denied", error);
        } catch (JSONException error) {
            Log.e(TAG, "Unable to record GNSS status support", error);
        } catch (RuntimeException error) {
            try { payload.put("satelliteStatusSupported", false); }
            catch (JSONException ignored) { }
            setUnavailable("GNSS status callback unavailable");
            Log.w(TAG, "GNSS status callback unavailable", error);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                measurementsCallback = new GnssMeasurementsEvent.Callback() {
                    @Override public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                        if (!running) return;
                        Double maximum = null;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            for (android.location.GnssMeasurement measurement : event.getMeasurements()) {
                                if (measurement.hasAutomaticGainControlLevelDb()) {
                                    double value = measurement.getAutomaticGainControlLevelDb();
                                    if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                                        maximum = maximum == null ? value : Math.max(maximum, value);
                                    }
                                }
                            }
                        }
                        maxAgcDb = maximum;
                        try {
                            payload.put("measurementsSupported", true);
                            updateSatellitePayload();
                            publish(false);
                        } catch (JSONException error) {
                            Log.e(TAG, "Unable to record measurement status", error);
                        }
                    }
                    @Override public void onStatusChanged(int status) {
                        if (status == GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED) {
                            try { payload.put("measurementsSupported", false); }
                            catch (JSONException error) { Log.e(TAG, "Unable to record GNSS support status", error); }
                            publish(false);
                        }
                    }
                };
                locationManager.registerGnssMeasurementsCallback(measurementsCallback, handler);
                payload.put("measurementsSupported", true);
            } catch (JSONException error) {
                Log.e(TAG, "Unable to record raw GNSS support", error);
            } catch (SecurityException error) {
                try { payload.put("measurementsSupported", false); }
                catch (JSONException jsonError) { Log.e(TAG, "Unable to record GNSS permission status", jsonError); }
                Log.w(TAG, "Raw GNSS measurement permission denied", error);
            } catch (RuntimeException error) {
                try { payload.put("measurementsSupported", false); }
                catch (JSONException jsonError) { Log.e(TAG, "Unable to record GNSS callback status", jsonError); }
                Log.w(TAG, "Raw GNSS measurement callback unavailable", error);
            }
        } else {
            try { payload.put("measurementsSupported", false); }
            catch (JSONException error) { Log.e(TAG, "Unable to record API support status", error); }
        }
    }

    private JSONObject basePayload() {
        JSONObject initial = new JSONObject();
        try {
            initial.put("source", JSONObject.NULL)
                    .put("ageMs", JSONObject.NULL)
                    .put("accuracyM", JSONObject.NULL)
                    .put("speedMps", JSONObject.NULL)
                    .put("speedAccuracyMps", JSONObject.NULL)
                    .put("bearingAccuracyDeg", JSONObject.NULL)
                    .put("satellites", JSONObject.NULL)
                    .put("used", JSONObject.NULL)
                    .put("meanCn0DbHz", JSONObject.NULL)
                    .put("maxAgcDb", JSONObject.NULL)
                    .put("satelliteStatusSupported", JSONObject.NULL)
                    .put("measurementsSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    .put("providerAvailable", false)
                    .put("reason", "Waiting for an active GNSS fix");
        } catch (JSONException error) {
            Log.e(TAG, "Unable to initialize GNSS diagnostics", error);
        }
        return initial;
    }

    private void updateSatellitePayload() {
        try {
            payload.put("satelliteStatusSupported", satelliteCount != null);
            payload.put("satellites", satelliteCount == null ? JSONObject.NULL : satelliteCount)
                    .put("used", usedSatelliteCount == null ? JSONObject.NULL : usedSatelliteCount)
                    .put("meanCn0DbHz", meanCn0DbHz == null ? JSONObject.NULL : meanCn0DbHz)
                    .put("maxAgcDb", maxAgcDb == null ? JSONObject.NULL : maxAgcDb);
        } catch (JSONException error) {
            Log.e(TAG, "Unable to update satellite diagnostics", error);
        }
    }

    private synchronized void updateProvider(boolean enabled, String error) {
        if (!running) return;
        try {
            payload.put("providerAvailable", enabled);
            if (error == null) {
                payload.remove("error");
                payload.remove("reason");
            } else payload.put("error", error);
            publish(false);
        } catch (JSONException jsonError) {
            Log.e(TAG, "Unable to record provider status", jsonError);
        }
    }

    private synchronized void markReceiverInactive() {
        if (!running) return;
        try {
            payload.put("providerAvailable", true)
                    .put("ageMs", JSONObject.NULL)
                    .put("accuracyM", JSONObject.NULL)
                    .put("speedMps", JSONObject.NULL)
                    .put("speedAccuracyMps", JSONObject.NULL)
                    .put("bearingAccuracyDeg", JSONObject.NULL)
                    .put("reason", "GNSS receiver inactive; waiting for location activity");
            payload.remove("error");
            publish(false);
        } catch (JSONException error) {
            Log.e(TAG, "Unable to record inactive GNSS state", error);
        }
    }

    private void setUnavailable(String reason) {
        try {
            payload.put("providerAvailable", false).put("error", reason);
        } catch (JSONException error) {
            Log.e(TAG, "Unable to record diagnostics error state", error);
        }
    }

    private synchronized void publish(boolean force) {
        if (!running || listener == null) return;
        long now = SystemClock.elapsedRealtime();
        String quality = LocationDiagnosticsProtocol.quality(payload);
        if ("UNAVAILABLE".equals(quality) || "UNKNOWN".equals(quality) || "DEGRADED".equals(quality)) {
            assessedQuality = quality;
            recoveryCandidate = null;
            recoveryCount = 0;
        } else if (!quality.equals(assessedQuality)) {
            if (quality.equals(recoveryCandidate)) recoveryCount++;
            else {
                recoveryCandidate = quality;
                recoveryCount = 1;
            }
            if (recoveryCount >= 3) {
                assessedQuality = quality;
                recoveryCandidate = null;
                recoveryCount = 0;
            }
        } else {
            recoveryCandidate = null;
            recoveryCount = 0;
        }
        try {
            payload.put("quality", assessedQuality);
            if (assessedQuality.equals(quality)) {
                if (payload.optString("error").isEmpty()) {
                    payload.put("reason", reasonFor(quality));
                } else {
                    payload.put("reason", payload.optString("error"));
                }
            }
            else payload.put("reason", "Recovery pending; quality remains " + assessedQuality);
        } catch (JSONException error) {
            Log.e(TAG, "Unable to record conservative quality state", error);
        }
        if (lastReportMs != 0L && now - lastReportMs < REPORT_INTERVAL_MS) return;
        lastReportMs = now;
        try {
            JSONObject envelope = LocationDiagnosticsProtocol.snapshot(session, payload);
            listener.onSnapshot(envelope);
        } catch (JSONException | IllegalArgumentException error) {
            Log.w(TAG, "Unable to send location diagnostics snapshot", error);
        }
    }

    private String reasonFor(String quality) {
        String error = payload.optString("error", "");
        if (!error.isEmpty()) return error;
        if ("UNKNOWN".equals(quality)) {
            if (!payload.optBoolean("satelliteStatusSupported", false)) return "Satellite status unsupported";
            if (!payload.optBoolean("measurementsSupported", false)) return "Raw GNSS measurements unsupported";
            if (payload.isNull("ageMs")) return "No active GNSS fix observed";
            if (payload.isNull("accuracyM")) return "Horizontal accuracy unavailable";
            if (payload.isNull("meanCn0DbHz")) return "Carrier-to-noise unavailable";
            return "Raw GNSS AGC unavailable";
        }
        if ("DEGRADED".equals(quality)) return "Fix quality is below conservative thresholds";
        if ("GOOD".equals(quality)) return "Conservative fix and satellite thresholds met";
        return "GNSS observation unavailable";
    }
}
