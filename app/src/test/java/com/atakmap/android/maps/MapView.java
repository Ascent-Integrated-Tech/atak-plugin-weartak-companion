package com.atakmap.android.maps;

import android.content.Context;

/** Host-context boundary for local JVM tests; no ATAK runtime or device is loaded. */
public final class MapView {
    private static MapView instance;
    private final Context context;
    private String callsign;

    private MapView(Context context) { this.context = context; }
    public static MapView getMapView() { return instance; }
    public Context getContext() { return context; }
    public String getDeviceCallsign() { return callsign; }
    public static void setTestCallsign(String callsign) { instance.callsign = callsign; }
    public static void setTestContext(Context context) {
        instance = context == null ? null : new MapView(context);
    }
}
