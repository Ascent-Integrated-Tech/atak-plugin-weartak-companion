package com.weartak.atak.weartak_companion.plugin;

import android.os.Handler;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.coremap.cot.event.CotAttribute;
import com.atakmap.coremap.cot.event.CotDetail;

import java.lang.reflect.Field;
import java.util.Map;

/** ATAK side of {@link SelfPliAugmenter}: self-PLI user remarks and the additional biometrics detail. */
final class AtakSelfPliHost implements SelfPliAugmenter.Host, SelfPliAugmenter.Clock {
    /** ATAK builds the self-PLI remarks element from this preference. */
    static final String USER_REMARKS = "userRemarks";
    static final String MANAGED_REMARKS = "weartak_companion_managed_self_remarks";
    static final String BIOMETRICS = "biometrics";
    static final String PRIVATE_BIOMETRICS = "weartak_companion_biometrics";

    private final AtakPreferences prefs;
    private final Handler handler;
    /** Last element this plugin published under the shared key, and the provider element it extended. */
    private CotDetail published;
    private CotDetail providerBase;

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
        CotDetail device = contribution == null || !contribution.hasPhysio() ? null : device(contribution);
        Map<String, CotDetail> details = additionalDetails(cot);
        if (details == null) {
            // Cannot see other providers: use a private key so another biometrics entry is never replaced.
            if (device == null) cot.removeAdditionalDetail(PRIVATE_BIOMETRICS);
            else cot.addAdditionalDetail(PRIVATE_BIOMETRICS, merged(null, device));
            return;
        }
        CotDetail present = details.get(BIOMETRICS);
        CotDetail base = present != null && present == published ? providerBase : present;
        if (device == null) {
            if (present != null && present == published) {
                if (base == null) cot.removeAdditionalDetail(BIOMETRICS);
                else cot.addAdditionalDetail(BIOMETRICS, base);
            }
            published = null;
            providerBase = null;
            return;
        }
        CotDetail next = merged(base, device);
        cot.addAdditionalDetail(BIOMETRICS, next);
        published = next;
        providerBase = base;
    }

    /** Copies another provider's biometrics element and appends this watch's device entry. */
    private static CotDetail merged(CotDetail base, CotDetail device) {
        CotDetail result = new CotDetail(BIOMETRICS);
        if (base == null) {
            result.setAttribute("version", Integer.toString(WatchSelfStatus.VERSION));
        } else {
            for (CotAttribute attribute : base.getAttributes())
                result.setAttribute(attribute.getName(), attribute.getValue());
            for (CotDetail child : base.getChildren()) result.addChild(child);
        }
        result.addChild(device);
        return result;
    }

    private static CotDetail device(WatchSelfStatus.Contribution contribution) {
        CotDetail device = new CotDetail("device");
        device.setAttribute("sampleTime", contribution.sampleTime());
        device.addChild(text("model", contribution.model));
        device.addChild(text("uid", contribution.deviceUid));
        if (contribution.hr != null)
            device.addChild(metric("hr", contribution.hr, contribution.hrText()));
        if (contribution.skinTempF != null)
            device.addChild(metric("skt", contribution.skinTempF, contribution.skinTempText()));
        if (contribution.exertion != null)
            device.addChild(metric("exert", contribution.exertion, contribution.exertionText()));
        return device;
    }

    private static CotDetail metric(String name, WatchSelfStatus.Sample sample, String value) {
        CotDetail metric = text(name, value);
        metric.setAttribute("sampleTime", sample.time());
        return metric;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CotDetail> additionalDetails(CotMapComponent cot) {
        try {
            Field match = null;
            for (Field field : CotMapComponent.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                if ("additionalDetails".equals(field.getName())) { match = field; break; }
                match = match == null ? field : null;
            }
            if (match == null) return null;
            match.setAccessible(true);
            Object value = match.get(cot);
            return value instanceof Map ? (Map<String, CotDetail>) value : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
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
