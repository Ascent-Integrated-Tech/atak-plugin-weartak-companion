package com.weartak.atak.weartak_companion.plugin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.maps.coords.GeoPoint;

/** Active GPS requests are independent of ATAK's selected location authority. */
final class PhonePositionSource {
    private final MapView map;
    private final PhonePositionActions actions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LocationManager manager;
    private boolean started;
    private boolean requesting;
    private final PointMapItem.OnPointChangedListener selfChanged = item -> observeSelf(true);
    private final LocationListener locations = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            observe(location);
            if (!actions.hasActions()) stopRequests();
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {
            LogX.w("WTK/PhonePosition", "Location provider disabled: " + provider);
        }
    };
    private final Runnable expire = new Runnable() {
        @Override public void run() {
            if (!started) return;
            actions.expireOneShots(System.currentTimeMillis());
            if (!actions.hasActions()) stopRequests();
            else handler.postDelayed(this, 30000);
        }
    };

    PhonePositionSource(MapView map, PhonePositionActions actions) {
        this.map = map;
        this.actions = actions;
        manager = (LocationManager) map.getContext().getSystemService(Context.LOCATION_SERVICE);
    }

    void start() {
        if (started) return;
        started = true;
        if (map.getSelfMarker() != null) map.getSelfMarker().addOnPointChangedListener(selfChanged);
        refreshKnown();
    }

    void stop() {
        started = false;
        if (map.getSelfMarker() != null) map.getSelfMarker().removeOnPointChangedListener(selfChanged);
        stopRequests();
        actions.stop();
    }

    void observeSelf(boolean changed) {
        GeoPoint point = map.getSelfMarker() == null ? null : map.getSelfMarker().getPoint();
        if (point == null || !PhoneFixValidator.isUsable(point.getLatitude(), point.getLongitude())) return;
        long time = map.getMapData().getMetaLong("lastGpsUpdateTick", 0);
        // ATAK's GPS tick is monotonic, unlike CoT/Android Location timestamps.
        if (time > 0) time = System.currentTimeMillis() - Math.max(0, android.os.SystemClock.elapsedRealtime() - time);
        else time = map.getSelfMarker().getMetaLong("lastUpdateTime", 0);
        if (time <= 0 && changed) time = System.currentTimeMillis();
        actions.observe(new PhonePosition(point.getLatitude(), point.getLongitude(),
                point.getAltitude(), point.getCE(), point.getLE(), time));
    }

    @SuppressLint("MissingPermission")
    void refreshKnown() {
        observeSelf(false);
        if (manager == null || !permitted()) return;
        try {
            for (String provider : manager.getProviders(true)) observe(manager.getLastKnownLocation(provider));
        } catch (SecurityException | IllegalArgumentException error) {
            LogX.w("WTK/PhonePosition", "Unable to read last known phone location", error);
        }
    }

    @SuppressLint("MissingPermission")
    void request() {
        if (!started || requesting) return;
        if (manager == null || !permitted()) {
            LogX.w("WTK/PhonePosition", "Cannot acquire fresh phone fix: location service/permission unavailable");
            handler.removeCallbacks(expire);
            handler.postDelayed(expire, 30000);
            return;
        }
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 1000L, 0f, locations, Looper.getMainLooper());
                    requesting = true;
                }
            } catch (SecurityException | IllegalArgumentException error) {
                LogX.w("WTK/PhonePosition", "Unable to request active phone location from " + provider, error);
            }
        }
        if (!requesting) LogX.w("WTK/PhonePosition", "Cannot acquire fresh phone fix: providers unavailable");
        handler.removeCallbacks(expire);
        handler.postDelayed(expire, 30000);
    }

    void stopIfIdle() { if (!actions.hasActions()) stopRequests(); }

    private void stopRequests() {
        handler.removeCallbacks(expire);
        if (manager != null && requesting) {
            try {
                manager.removeUpdates(locations);
            } catch (SecurityException error) {
                LogX.w("WTK/PhonePosition", "Unable to stop active phone location", error);
            }
        }
        requesting = false;
    }

    private boolean permitted() {
        return ContextCompat.checkSelfPermission(map.getContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void observe(Location location) {
        if (location == null || !PhoneFixValidator.isUsable(location.getLatitude(), location.getLongitude())) return;
        actions.observe(new PhonePosition(location.getLatitude(), location.getLongitude(),
                location.hasAltitude() ? location.getAltitude() : Double.NaN,
                location.hasAccuracy() ? location.getAccuracy() : Double.NaN,
                Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()
                        ? location.getVerticalAccuracyMeters() : Double.NaN, location.getTime()));
    }
}
