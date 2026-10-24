package com.weartak.atak.weartak_companion.plugin;

import android.content.SharedPreferences;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AtakUnitPreferencesObserverTest {
    @Test public void observesAllSevenKeysAndClearButIgnoresUnrelatedWrites() {
        int[] changes = {0};
        AtakUnitPreferencesObserver observer = new AtakUnitPreferencesObserver(() -> changes[0]++);
        SharedPreferences prefs = mock(SharedPreferences.class);
        observer.start(prefs);
        ArgumentCaptor<SharedPreferences.OnSharedPreferenceChangeListener> listener =
                ArgumentCaptor.forClass(SharedPreferences.OnSharedPreferenceChangeListener.class);
        verify(prefs).registerOnSharedPreferenceChangeListener(listener.capture());
        for (String key : AtakUnitPreferences.read(java.util.Collections.emptyMap()).keySet()) {
            listener.getValue().onSharedPreferenceChanged(prefs, key);
        }
        assertEquals(7, changes[0]);
        listener.getValue().onSharedPreferenceChanged(prefs, "callsign");
        listener.getValue().onSharedPreferenceChanged(prefs, "phoneManagedSettings");
        assertEquals(7, changes[0]);
        listener.getValue().onSharedPreferenceChanged(prefs, null);
        assertEquals(8, changes[0]);
        observer.stop();
        observer.stop();
        verify(prefs).unregisterOnSharedPreferenceChangeListener(listener.getValue());
    }

    @Test public void restartingUnregistersPreviousHostPreferences() {
        AtakUnitPreferencesObserver observer = new AtakUnitPreferencesObserver(() -> {});
        SharedPreferences old = mock(SharedPreferences.class);
        SharedPreferences next = mock(SharedPreferences.class);
        observer.start(old);
        observer.start(next);
        verify(old).unregisterOnSharedPreferenceChangeListener(any());
        verify(next).registerOnSharedPreferenceChangeListener(any());
        observer.stop();
        verify(next).unregisterOnSharedPreferenceChangeListener(any());
    }
}
