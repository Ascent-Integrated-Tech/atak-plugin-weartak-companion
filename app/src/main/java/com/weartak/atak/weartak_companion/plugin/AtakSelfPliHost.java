package com.weartak.atak.weartak_companion.plugin;

import android.os.Handler;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.coremap.cot.event.CotDetail;

/** ATAK side of {@link SelfPliAugmenter}: self-PLI user remarks and the additional biometrics detail. */
final class AtakSelfPliHost implements SelfPliAugmenter.Host, SelfPliAugmenter.Clock {
    /** ATAK builds the self-PLI remarks element from this preference. */
    static final String USER_REMARKS = "userRemarks";
    static final String MANAGED_REMARKS = "weartak_companion_managed_self_remarks";
    static final String BIOMETRICS = "biometrics";

    private final AtakPreferences prefs;
    private final Handler handler;

    AtakSelfPliHost(MapView mapView, Handler handler) {
        this.prefs = AtakPreferences.getInstance(mapView.getContext());
        this.handler = handler;
    }

    @Override public String userRemarks() { return prefs.get(USER_REMARKS, ""); }

    @Override public void setUserRemarks(String remarks) { prefs.set(USER_REMARKS, remarks); }

    @Override public String managedRemarks() { return prefs.get(MANAGED_REMARKS, ""); }

    @Override public void setManagedRemarks(String line) {
        if (line == null || line.isEmpty()) prefs.remove(MANAGED_REMARKS);
        else prefs.set(MANAGED_REMARKS, line);
    }

    @Override public void setBiometrics(WatchSelfStatus.Contribution contribution) {
        CotMapComponent cot = CotMapComponent.getInstance();
        if (cot == null) return;
        if (contribution == null || contribution.hr == null) {
            cot.removeAdditionalDetail(BIOMETRICS);
            return;
        }
        CotDetail biometrics = new CotDetail(BIOMETRICS);
        biometrics.setAttribute("version", Integer.toString(WatchSelfStatus.VERSION));
        CotDetail device = new CotDetail("device");
        device.setAttribute("sampleTime", contribution.sampleTime());
        device.addChild(text("model", contribution.model));
        device.addChild(text("uid", contribution.deviceUid));
        device.addChild(text("hr", Integer.toString(contribution.hr)));
        biometrics.addChild(device);
        cot.addAdditionalDetail(BIOMETRICS, biometrics);
    }

    private static CotDetail text(String name, String value) {
        CotDetail detail = new CotDetail(name);
        detail.setInnerText(value);
        return detail;
    }

    @Override public void log(String message) { LogX.i("WTK/SelfPli", message); }

    @Override public long now() { return System.currentTimeMillis(); }

    @Override public void schedule(Runnable action, long delayMs) { handler.postDelayed(action, delayMs); }

    @Override public void cancel(Runnable action) { handler.removeCallbacks(action); }
}
