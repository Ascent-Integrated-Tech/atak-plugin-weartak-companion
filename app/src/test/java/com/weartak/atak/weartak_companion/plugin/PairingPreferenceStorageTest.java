package com.weartak.atak.weartak_companion.plugin;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import com.atakmap.android.maps.MapView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class PairingPreferenceStorageTest {
    private final PrefContext host = new PrefContext(true);
    private final PrefContext plugin = new PrefContext(false);

    @Before public void hostOwnsStorage() {
        MapView.setTestContext(new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return host; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                throw new AssertionError("Use the host application, not the activity");
            }
        });
    }

    @After public void clearHost() { MapView.setTestContext(null); }

    @Test public void preferredAddressSurvivesClientRecreationInHostStorage() throws Exception {
        invoke(client(null), "savePreferredAddress", new Class<?>[]{String.class}, "watch-a");

        assertEquals("watch-a", client(null).getPreferredAddress());
        assertEquals("watch-a", host.values("wtk_pairing").get("preferred_address"));
        assertTrue(plugin.files.isEmpty());
    }

    @Test public void existingPairingKeysRemainReadableAndUidUpdatesKeepAddress() throws Exception {
        host.values("wtk_pairing").put("preferred_address", "watch-a");
        host.values("wtk_pairing").put("paired_uid", "old-uid");
        WearTakBleClient client = client(null);
        assertEquals("watch-a", client.getPreferredAddress());
        assertEquals("old-uid", client.getPairedUid());

        invoke(client, "savePairedUid", new Class<?>[]{String.class, String.class}, "new-uid", "WATCH");

        assertEquals("new-uid", client(null).getPairedUid());
        assertEquals("WATCH", host.values("wtk_pairing").get("paired_callsign"));
        assertEquals("watch-a", client(null).getPreferredAddress());
    }

    @Test public void trustLossOnlyClearsMatchingAddressAndDoesNotArmRetries() throws Exception {
        WearTakBleClient client = client(null);
        invoke(client, "savePreferredAddress", new Class<?>[]{String.class}, "watch-a");
        invoke(client, "clearPreferredAddressIfMatches", new Class<?>[]{String.class}, "watch-b");
        assertEquals("watch-a", client.getPreferredAddress());
        invoke(client, "clearPreferredAddressIfMatches", new Class<?>[]{String.class}, "watch-a");
        assertNull(client(null).getPreferredAddress());
        assertFalse(host.files.containsKey(AutoReconnectPreference.NAME));
    }

    @Test public void gatewayPeersSingleWatchAndRetryNamespacesRemainIsolated() throws Exception {
        WearTakBleClient single = client(null);
        WearTakBleClient a = client("watch-a");
        WearTakBleClient b = client("watch-b");
        invoke(single, "savePreferredAddress", new Class<?>[]{String.class}, "single");
        invoke(a, "savePreferredAddress", new Class<?>[]{String.class}, "watch-a");
        invoke(b, "savePreferredAddress", new Class<?>[]{String.class}, "watch-b");
        invoke(a, "savePairedUid", new Class<?>[]{String.class, String.class}, "uid-a", "A");
        invoke(b, "savePairedUid", new Class<?>[]{String.class, String.class}, "uid-b", "B");

        assertEquals("single", client(null).getPreferredAddress());
        assertEquals("watch-a", client("watch-a").getPreferredAddress());
        assertEquals("uid-a", client("watch-a").getPairedUid());
        assertEquals("uid-b", client("watch-b").getPairedUid());
        invoke(a, "clearPreferredAddress", new Class<?>[0]);
        assertNull(client("watch-a").getPreferredAddress());
        assertEquals("watch-b", client("watch-b").getPreferredAddress());
        assertFalse(host.files.containsKey(GatewayPolicy.retryPreferences("watch-a")));
        assertFalse(host.files.containsKey(AutoReconnectPreference.NAME));
    }

    @Test public void companionSettingsUseExistingHostFileWithoutOverwritingConfirmedValues() {
        Map<String, Object> saved = host.values("weartak_companion_prefs");
        saved.put("connection_mode", ConnectionModeController.Mode.SAMSUNG.value);
        saved.put("wearable_connection_paused", true);
        saved.put("preferred_device_address", "watch-a");
        saved.put("preferred_device_name", "WATCH");
        saved.put("preferred_device_set_at_ms", 123L);
        SharedPreferences prefs = PluginPrefs.get(plugin, "weartak_companion_prefs");

        assertEquals(ConnectionModeController.Mode.SAMSUNG, ConnectionModeController.restore(
                prefs.getString("connection_mode", null), false));
        assertTrue(prefs.getBoolean("wearable_connection_paused", false));
        prefs.edit().putString("preferred_device_name", "RENAMED").apply();
        SharedPreferences reloaded = PluginPrefs.get(plugin, "weartak_companion_prefs");
        assertEquals(ConnectionModeController.Mode.SAMSUNG, ConnectionModeController.restore(
                reloaded.getString("connection_mode", null), false));
        assertTrue(reloaded.getBoolean("wearable_connection_paused", false));
        assertEquals("watch-a", reloaded.getString("preferred_device_address", null));
        assertEquals("RENAMED", reloaded.getString("preferred_device_name", null));
        assertEquals(123L, reloaded.getLong("preferred_device_set_at_ms", 0L));
        assertTrue(plugin.files.isEmpty());
    }

    private WearTakBleClient client(String peer) throws Exception {
        WearTakBleClient client = peer == null
                ? new WearTakBleClient(plugin, null)
                : new WearTakBleClient(plugin, null, peer);
        // Initialize the same application context as start(), without starting Bluetooth.
        Field app = WearTakBleClient.class.getDeclaredField("appContext");
        app.setAccessible(true);
        app.set(client, plugin.getApplicationContext());
        return client;
    }

    private static void invoke(WearTakBleClient client, String name, Class<?>[] types,
                               Object... args) throws Exception {
        Method method = WearTakBleClient.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(client, args);
    }

    private static final class PrefContext extends ContextWrapper {
        final Map<String, Map<String, Object>> files = new HashMap<>();
        final boolean writable;
        PrefContext(boolean writable) { super(null); this.writable = writable; }
        @Override public Context getApplicationContext() { return this; }
        Map<String, Object> values(String name) {
            return files.computeIfAbsent(name, n -> new HashMap<>());
        }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (!writable) throw new AssertionError("ATAK UID cannot write plugin storage: " + name);
            assertEquals(Context.MODE_PRIVATE, mode);
            Map<String, Object> values = values(name);
            SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("put")) {
                            values.put((String) args[0], args[1]);
                            return proxy;
                        }
                        if ("remove".equals(method.getName())) {
                            values.remove(args[0]);
                            return proxy;
                        }
                        if ("apply".equals(method.getName())) return null;
                        throw new AssertionError(method.getName());
                    });
            return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if (method.getName().startsWith("get"))
                            return values.getOrDefault(args[0], args[1]);
                        if ("edit".equals(method.getName())) return editor;
                        throw new AssertionError(method.getName());
                    });
        }
    }
}
