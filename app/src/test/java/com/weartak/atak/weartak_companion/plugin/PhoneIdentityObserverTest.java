package com.weartak.atak.weartak_companion.plugin;

import android.content.SharedPreferences;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class PhoneIdentityObserverTest {
    @Test public void observesPreferencesAndSelfIdentityThenUnregistersOnStop() {
        int[] changes = {0};
        PhoneIdentityObserver observer = new PhoneIdentityObserver(() -> changes[0]++);
        SharedPreferences prefs = mock(SharedPreferences.class);
        Marker self = mock(Marker.class);
        observer.start(prefs, self);
        ArgumentCaptor<SharedPreferences.OnSharedPreferenceChangeListener> preference =
                ArgumentCaptor.forClass(SharedPreferences.OnSharedPreferenceChangeListener.class);
        verify(prefs).registerOnSharedPreferenceChangeListener(preference.capture());
        ArgumentCaptor<MapItem.OnMetadataChangedListener> metadata =
                ArgumentCaptor.forClass(MapItem.OnMetadataChangedListener.class);
        verify(self).addOnMetadataChangedListener(eq("callsign"), metadata.capture());
        for (String key : new String[]{"locationCallsign", "locationTeam", "atakRoleType"})
            preference.getValue().onSharedPreferenceChanged(prefs, key);
        for (String key : new String[]{"callsign", "team", "atakRoleType", "role"}) {
            verify(self).addOnMetadataChangedListener(eq(key), same(metadata.getValue()));
            metadata.getValue().onMetadataChanged(self, key);
        }
        assertEquals(7, changes[0]);
        observer.stop();
        observer.stop();
        verify(prefs).unregisterOnSharedPreferenceChangeListener(preference.getValue());
        for (String key : new String[]{"callsign", "team", "atakRoleType", "role"})
            verify(self).removeOnMetadataChangedListener(key, metadata.getValue());
    }

    @Test public void restartingReleasesPreviousHostAndAllowsMissingSelfMarker() {
        PhoneIdentityObserver observer = new PhoneIdentityObserver(() -> {});
        SharedPreferences old = mock(SharedPreferences.class);
        SharedPreferences next = mock(SharedPreferences.class);
        observer.start(old, null);
        observer.start(next, null);
        verify(old).unregisterOnSharedPreferenceChangeListener(any());
        verify(next).registerOnSharedPreferenceChangeListener(any());
        observer.stop();
        verify(next).unregisterOnSharedPreferenceChangeListener(any());
    }

    @Test public void bridgePhoneCallsignSourceIsLiveRatherThanConstructionSnapshot() {
        MapView.setTestContext(mock(android.content.Context.class));
        try {
            MapView map = MapView.getMapView();
            MapView.setTestCallsign("OLD");
            assertEquals("OLD", PhoneIdentity.callsign(map));
            MapView.setTestCallsign("NEW");
            assertEquals("NEW", PhoneIdentity.callsign(map));
            assertEquals("", PhoneIdentity.callsign(null));
        } finally {
            MapView.setTestContext(null);
        }
    }
}
