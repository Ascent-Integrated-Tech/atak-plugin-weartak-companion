package com.weartak.atak.weartak_companion.plugin;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class PhoneGnssDiagnosticsTest {
    private static final String SESSION = "01234567-89ab-cdef-0123-456789abcdef";

    @Test public void startUsesHostServicesWithoutDereferencingNullableApplicationContext() throws Exception {
        LocationManager manager = mock(LocationManager.class);
        when(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)).thenReturn(true);
        Context host = hostContext(manager, PackageManager.PERMISSION_GRANTED);
        assertNull(host.getApplicationContext());
        PhoneGnssDiagnostics diagnostics = new PhoneGnssDiagnostics();
        Handler handler = mock(Handler.class);
        set(diagnostics, "handler", handler);
        AtomicInteger reports = new AtomicInteger();

        diagnostics.start(host, SESSION, 7L, envelope -> reports.incrementAndGet());

        assertEquals(SESSION, diagnostics.session());
        assertEquals(7L, diagnostics.readySession());
        assertEquals(1, reports.get());
        assertTrue(diagnostics.payloadSnapshot().getBoolean("providerAvailable"));
        LocationListener observer = (LocationListener) get(diagnostics, "passiveFixListener");
        assertNotNull(observer);
        verify(manager).requestLocationUpdates(eq(LocationManager.PASSIVE_PROVIDER), eq(30_000L),
                eq(0f), same(observer), any());
        verify(handler).postDelayed(any(Runnable.class), eq(30_000L));

        diagnostics.stop();
        verify(manager).removeUpdates(observer);
        assertNull(get(diagnostics, "locationManager"));
        assertNull(get(diagnostics, "passiveFixListener"));
        assertNull(diagnostics.session());
    }

    @Test public void startWithHostPermissionDeniedReportsUnavailableWithoutRegistering() throws Exception {
        LocationManager manager = mock(LocationManager.class);
        PhoneGnssDiagnostics diagnostics = new PhoneGnssDiagnostics();
        set(diagnostics, "handler", mock(Handler.class));
        AtomicInteger reports = new AtomicInteger();

        diagnostics.start(hostContext(manager, PackageManager.PERMISSION_DENIED),
                SESSION, 7L, envelope -> reports.incrementAndGet());

        assertEquals(1, reports.get());
        assertEquals("Location permission not granted", diagnostics.payloadSnapshot().getString("error"));
        assertNull(get(diagnostics, "passiveFixListener"));
        verifyNoInteractions(manager);
        diagnostics.stop();
    }

    private static Context hostContext(LocationManager manager, int permission) {
        return new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return null; }
            @Override public Object getSystemService(String name) {
                assertEquals(Context.LOCATION_SERVICE, name);
                return manager;
            }
            @Override public int checkSelfPermission(String name) {
                assertTrue(Manifest.permission.ACCESS_FINE_LOCATION.equals(name) ||
                        Manifest.permission.ACCESS_COARSE_LOCATION.equals(name));
                return permission;
            }
        };
    }

    @Test
    public void unsupportedGnssApiReportsUnavailableWithoutCreatingCallbacks() throws Exception {
        assertTrue(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N);
        PhoneGnssDiagnostics diagnostics = new PhoneGnssDiagnostics();
        java.lang.reflect.Method register = PhoneGnssDiagnostics.class
                .getDeclaredMethod("registerGnssCallbacks");
        register.setAccessible(true);

        register.invoke(diagnostics);

        JSONObject payload = diagnostics.payloadSnapshot();
        assertFalse(payload.getBoolean("satelliteStatusSupported"));
        assertFalse(payload.getBoolean("measurementsSupported"));
        assertNull(get(diagnostics, "statusCallback"));
        assertNull(get(diagnostics, "measurementsCallback"));
    }

    @Test
    public void matchingDisableStopsObservationClearsCallbacksAndCancelsFurtherReports() throws Exception {
        PhoneGnssDiagnostics diagnostics = activeObserver();
        AtomicInteger reports = new AtomicInteger();
        set(diagnostics, "listener", (PhoneGnssDiagnostics.Listener) envelope -> reports.incrementAndGet());

        assertTrue(diagnostics.stopForSession(stop(SESSION), 7L));

        assertNull(diagnostics.session());
        assertEquals(-1L, diagnostics.readySession());
        assertEquals(false, get(diagnostics, "running"));
        for (String field : new String[] {
                "passiveFixListener", "statusCallback", "measurementsCallback", "listener", "locationManager"
        }) assertNull(field, get(diagnostics, field));
        ((Runnable) get(diagnostics, "heartbeat")).run();
        assertEquals(0, reports.get());
        assertFalse(diagnostics.stopForSession(stop(SESSION), 7L));
    }

    @Test
    public void oldDiagnosticOrBleSessionCannotTearDownCurrentCallbacks() throws Exception {
        PhoneGnssDiagnostics diagnostics = activeObserver();
        Object passive = get(diagnostics, "passiveFixListener");
        Object status = get(diagnostics, "statusCallback");
        Object measurements = get(diagnostics, "measurementsCallback");

        assertFalse(diagnostics.stopForSession(stop("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), 7L));
        assertFalse(diagnostics.stopForSession(stop(SESSION), 6L));
        JSONObject wrongVersion = stop(SESSION).put("version", 2);
        assertFalse(diagnostics.stopForSession(wrongVersion, 7L));

        assertEquals(SESSION, diagnostics.session());
        assertEquals(true, get(diagnostics, "running"));
        assertSame(passive, get(diagnostics, "passiveFixListener"));
        assertSame(status, get(diagnostics, "statusCallback"));
        assertSame(measurements, get(diagnostics, "measurementsCallback"));
        diagnostics.stop();
    }

    private static PhoneGnssDiagnostics activeObserver() throws Exception {
        PhoneGnssDiagnostics diagnostics = new PhoneGnssDiagnostics();
        set(diagnostics, "session", SESSION);
        set(diagnostics, "readySession", 7L);
        set(diagnostics, "running", true);
        set(diagnostics, "passiveFixListener", new LocationListener() {
            @Override public void onLocationChanged(Location location) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        });
        set(diagnostics, "statusCallback", new GnssStatus.Callback() { });
        set(diagnostics, "measurementsCallback", new GnssMeasurementsEvent.Callback() { });
        return diagnostics;
    }

    private static JSONObject stop(String session) throws Exception {
        return new JSONObject().put("msgType", LocationDiagnosticsProtocol.STOP)
                .put("version", LocationDiagnosticsProtocol.VERSION).put("session", session);
    }

    private static void set(PhoneGnssDiagnostics diagnostics, String name, Object value) throws Exception {
        field(name).set(diagnostics, value);
    }

    private static Object get(PhoneGnssDiagnostics diagnostics, String name) throws Exception {
        return field(name).get(diagnostics);
    }

    private static Field field(String name) throws Exception {
        Field field = PhoneGnssDiagnostics.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
