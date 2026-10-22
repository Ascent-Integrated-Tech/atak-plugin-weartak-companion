"""Exercise the real GatewayMapBridge with a deterministic JVM/ATAK test boundary.

Run from the repository root: python scripts/tests/test_gateway_map_bridge.py
Requires a JDK, not an Android device or additional Python packages. Test doubles
replace scheduling and ATAK export only; the production bridge is compiled as-is.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


PACKAGE = "com.weartak.atak.weartak_companion.plugin"
STUBS = {
    "android/content/SharedPreferences.java": """
package android.content;
public interface SharedPreferences {}
""",
    "android/os/Looper.java": """
package android.os;
public class Looper { public static Looper getMainLooper() { return new Looper(); } }
""",
    "android/os/Handler.java": """
package android.os;
import java.util.*;
public class Handler {
    private static final List<Runnable> tasks = new ArrayList<>();
    public static int cancelled;
    public Handler(Looper looper) {}
    public void removeCallbacksAndMessages(Object token) { tasks.clear(); }
    public void removeCallbacks(Runnable task) {
        if (tasks.removeIf(r -> r == task)) cancelled++;
    }
    public boolean postDelayed(Runnable task, long delay) {
        if (delay < 100) throw new AssertionError("Recovery must be paced");
        tasks.add(task); return true;
    }
    public static boolean tick() {
        if (tasks.isEmpty()) return false;
        tasks.remove(0).run(); return true;
    }
    public static int queued() { return tasks.size(); }
}
""",
    "com/atakmap/android/maps/MapItem.java": """
package com.atakmap.android.maps;
public class MapItem {
    public interface OnMetadataChangedListener { void onMetadataChanged(MapItem item, String key); }
    public final String uid;
    public String type = "a-f-G", title = "initial";
    public boolean nevercot;
    public Object group = new Object();
    public MapItem(String uid) { this.uid = uid; }
    public String getUID() { return uid; }
    public String getType() { return type; }
    public Object getGroup() { return group; }
    public boolean hasMetaValue(String key) { return nevercot && key.equals("nevercot"); }
}
""",
    "com/atakmap/android/maps/PointMapItem.java": """
package com.atakmap.android.maps;
public class PointMapItem extends MapItem {
    public interface OnPointChangedListener { void onPointChanged(PointMapItem item); }
    private OnPointChangedListener listener, retained;
    public PointMapItem(String uid) { super(uid); }
    public void addOnPointChangedListener(OnPointChangedListener value) { listener = retained = value; }
    public void removeOnPointChangedListener(OnPointChangedListener value) { listener = null; }
    public void change(String value) { title = value; if (listener != null) listener.onPointChanged(this); }
    public void lateChange() { retained.onPointChanged(this); }
}
""",
    "com/atakmap/android/maps/MapEvent.java": """
package com.atakmap.android.maps;
public class MapEvent {
    public static final String ITEM_ADDED = "added", ITEM_IMPORTED = "imported", ITEM_REMOVED = "removed";
    private final String type;
    private final MapItem item;
    public MapEvent(String type, MapItem item) { this.type = type; this.item = item; }
    public String getType() { return type; }
    public MapItem getItem() { return item; }
}
""",
    "com/atakmap/android/maps/MapEventDispatcher.java": """
package com.atakmap.android.maps;
import java.util.*;
public class MapEventDispatcher {
    public interface MapEventDispatchListener { void onMapEvent(MapEvent event); }
    private final Map<String, MapEventDispatchListener> listeners = new HashMap<>();
    public void addMapEventListenerToBase(String type, MapEventDispatchListener listener) {
        listeners.put(type, listener);
    }
    public void removeMapEventListenerFromBase(String type, MapEventDispatchListener listener) {
        listeners.remove(type);
    }
    public void emit(String type, MapItem item) { listeners.get(type).onMapEvent(new MapEvent(type, item)); }
}
""",
    "com/atakmap/android/maps/MapView.java": """
package com.atakmap.android.maps;
import java.util.*;
public class MapView {
    public static class Root {
        public final List<MapItem> items = new ArrayList<>();
        public int scans;
        public List<MapItem> getItemsRecursive() { scans++; return new ArrayList<>(items); }
    }
    public final Root root = new Root();
    public final MapEventDispatcher dispatcher = new MapEventDispatcher();
    public Root getRootGroup() { return root; }
    public MapEventDispatcher getMapEventDispatcher() { return dispatcher; }
    public PointMapItem getSelfMarker() { return null; }
}
""",
    "com/atakmap/coremap/cot/event/CotEvent.java": """
package com.atakmap.coremap.cot.event;
import com.atakmap.android.maps.PointMapItem;
public class CotEvent {
    private final PointMapItem item;
    public CotEvent(PointMapItem item) { this.item = item; }
    public String getType() { return item.getType(); }
    public String getUID() { return item.getUID(); }
    public void buildXml(StringBuilder xml) { xml.append(item.title); }
}
""",
    "com/atakmap/android/importexport/CotEventFactory.java": """
package com.atakmap.android.importexport;
import com.atakmap.android.maps.*;
import com.atakmap.coremap.cot.event.CotEvent;
public class CotEventFactory {
    public static CotEvent createCotEvent(MapItem item) { return new CotEvent((PointMapItem) item); }
}
""",
    "com/weartak/atak/weartak_companion/plugin/GatewayCot.java": """
package com.weartak.atak.weartak_companion.plugin;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.cot.event.CotEvent;
final class GatewayCot {
    static boolean supportedType(String type) { return "a-f-G".equals(type) || "b-t-f".equals(type); }
    static boolean isChatType(String type) { return "b-t-f".equals(type); }
    static CotEvent validate(String xml) { return new CotEvent(new PointMapItem(xml)); }
}
""",
    "com/weartak/atak/weartak_companion/plugin/GatewayOrigins.java": """
package com.weartak.atak.weartak_companion.plugin;
import android.content.SharedPreferences;
import java.util.*;
final class GatewayOrigins {
    private final Map<String, String> owners = new HashMap<>();
    GatewayOrigins(SharedPreferences prefs) {}
    void claim(String uid, String address) { owners.put(uid, address); }
    boolean contains(String uid) { return owners.containsKey(uid); }
    void forgetPeer(String address) { owners.values().removeIf(address::equals); }
    void clear() { owners.clear(); }
}
""",
    "com/weartak/atak/weartak_companion/plugin/MetadataObservers.java": """
package com.weartak.atak.weartak_companion.plugin;
import com.atakmap.android.maps.MapItem;
final class MetadataObservers {
    static void add(MapItem item, MapItem.OnMetadataChangedListener listener) {}
    static void remove(MapItem item, MapItem.OnMetadataChangedListener listener) {}
}
""",
}

HARNESS = """
package com.weartak.atak.weartak_companion.plugin;
import android.os.Handler;
import com.atakmap.android.maps.*;
import java.lang.reflect.*;
import java.util.*;

public class GatewayMapBridgeRegression {
    final MapView map = new MapView();
    final List<PointMapItem> points = new ArrayList<>();
    final Map<String, String> sent = new LinkedHashMap<>();
    final List<String> errors = new ArrayList<>();
    final ScanMap<PointMapItem, Boolean> observed = new ScanMap<>();
    Runnable onPublish;
    int exports;
    final GatewayMapBridge bridge = new GatewayMapBridge(map, (uid, xml) -> {
        sent.put(uid, xml); exports++;
        if (onPublish != null) {
            Runnable action = onPublish; onPublish = null; action.run();
        }
    }, errors::add);
    static final class ScanMap<K, V> extends LinkedHashMap<K, V> {
        int inspected;
        @Override public Set<Map.Entry<K, V>> entrySet() {
            Set<Map.Entry<K, V>> entries = super.entrySet();
            return new AbstractSet<Map.Entry<K, V>>() {
                public int size() { return entries.size(); }
                public Iterator<Map.Entry<K, V>> iterator() {
                    Iterator<Map.Entry<K, V>> iterator = entries.iterator();
                    return new Iterator<Map.Entry<K, V>>() {
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Map.Entry<K, V> next() { inspected++; return iterator.next(); }
                        public void remove() { iterator.remove(); }
                    };
                }
            };
        }
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    GatewayMapBridgeRegression(int count) throws Exception {
        Field ledger = GatewayMapBridge.class.getDeclaredField("observed");
        ledger.setAccessible(true); ledger.set(bridge, observed);
        for (int i = 0; i < count; i++) {
            PointMapItem p = new PointMapItem("point-" + i);
            points.add(p); map.root.items.add(p);
        }
        bridge.start();
    }
    void changed() { for (PointMapItem p : points) p.change("latest-" + p.uid); }
    void bounds() throws Exception {
        Field pending = GatewayMapBridge.class.getDeclaredField("pending");
        pending.setAccessible(true);
        check(((Map<?, ?>) pending.get(bridge)).size() <= 128, "Pending exceeded 128");
        check(Handler.queued() <= 1, "Multiple recovery callbacks queued");
    }
    boolean tick() throws Exception {
        int before = exports, scans = observed.inspected;
        boolean result = Handler.tick();
        check(exports - before <= 128, "More than 128 exports in one tick");
        check(observed.inspected - scans <= 128, "Unbounded observer scan");
        bounds();
        return result;
    }
    void drain() throws Exception {
        int ticks = 0, scans = map.root.scans;
        while (tick()) {
            check(++ticks <= 100, "Recovery did not quiesce");
        }
        check(map.root.scans == scans, "Recovery repeatedly scanned root snapshots");
    }
    void remove(PointMapItem p) {
        p.group = null; map.root.items.remove(p);
        map.dispatcher.emit(MapEvent.ITEM_REMOVED, p);
    }
    void overflow() throws Exception {
        changed();
        points.get(0).change("coalesced pending");
        points.get(points.size() - 1).change("coalesced deferred");
        bounds(); drain();
        check(sent.size() == points.size(),
                "Overflow stranded supported state: expected " + points.size() + ", emitted " + sent.size());
        for (PointMapItem p : points) check(p.title.equals(sent.get(p.uid)), "Not latest: " + p.uid);
        check(errors.size() == 1, "Repeated overflow should not flood error notifications");
    }
    void snapshot() throws Exception {
        bridge.snapshot(); drain();
        check(sent.size() == points.size(), "Large snapshot did not converge");
    }
    void removalAndFilters() throws Exception {
        changed();
        remove(points.get(0)); remove(points.get(384));
        points.get(384).lateChange();
        bridge.origin(points.get(383).uid, "watch");
        points.get(382).nevercot = true;
        points.get(381).type = "u-d-p";
        points.get(380).type = "b-t-f";
        MapItem polygon = new MapItem("polygon");
        map.root.items.add(polygon);
        map.dispatcher.emit(MapEvent.ITEM_ADDED, polygon);
        drain();
        check(sent.size() == 379, "Removal/origin/unsupported filtering changed: " + sent.size());
        for (int i : new int[]{0, 380, 381, 382, 383, 384})
            check(!sent.containsKey(points.get(i).uid), "Excluded state emitted: " + i);
        check(!sent.containsKey(polygon.uid), "Non-point geometry emitted");
        check(!observed.containsKey(points.get(384)), "Removed deferred point retained");
    }
    void reset() throws Exception {
        changed(); bridge.stop(); bounds();
        check(observed.isEmpty() && Handler.queued() == 0, "Stop retained recovery work");
        bridge.start(); drain();
        check(sent.isEmpty(), "Old session work replayed after restart");
        points.get(384).change("fresh session"); drain();
        check(sent.size() == 1 && "fresh session".equals(sent.get("point-384")), "Fresh session lost");
    }
    void repeatedOverflow() throws Exception {
        changed();
        int cancelled = Handler.cancelled;
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 256; i++) points.get(i).change("hot-" + round);
            check(Handler.cancelled == cancelled, "Live updates postponed the scheduled flush");
            check(tick(), "Recovery unexpectedly stopped");
        }
        check(sent.size() == points.size(), "Repeated overflow starved later entities");
        drain();
        for (PointMapItem p : points) check(p.title.equals(sent.get(p.uid)), "Churn not latest: " + p.uid);
    }
    void sparseRecovery() throws Exception {
        for (int i = 768; i < points.size(); i++) points.get(i).change("tail");
        drain();
        check(sent.size() == points.size() - 768, "Clean observer prefix stranded dirty tail");
    }
    void reentrantChangeAndRemoval() throws Exception {
        changed();
        onPublish = () -> {
            points.get(0).change("changed during export");
            remove(points.get(128));
            bridge.origin(points.get(129).uid, "watch");
        };
        drain();
        check("changed during export".equals(sent.get("point-0")), "Export cleared a newer change");
        check(!sent.containsKey("point-128"), "Removal during recovery emitted a point");
        check(!sent.containsKey("point-129"), "Origin claim during recovery echoed a point");
        check(sent.size() == points.size() - 2, "Reentrant recovery stranded points");
    }
    interface Scenario { void run(GatewayMapBridgeRegression test) throws Exception; }
    static void run(String name, int count, Scenario scenario) throws Exception {
        GatewayMapBridgeRegression test = new GatewayMapBridgeRegression(count);
        try { scenario.run(test); } finally { test.bridge.stop(); }
        System.out.println("PASS " + name);
    }
    public static void main(String[] args) throws Exception {
        run("overflow and coalescing", 385, GatewayMapBridgeRegression::overflow);
        run("snapshot overflow", 385, GatewayMapBridgeRegression::snapshot);
        run("removal and eligibility", 385, GatewayMapBridgeRegression::removalAndFilters);
        run("session reset", 385, GatewayMapBridgeRegression::reset);
        run("repeated overflow fairness", 1025, GatewayMapBridgeRegression::repeatedOverflow);
        run("bounded sparse recovery", 1025, GatewayMapBridgeRegression::sparseRecovery);
        run("changes during publication", 385, GatewayMapBridgeRegression::reentrantChangeAndRemoval);
    }
}
"""


class GatewayMapBridgeTest(unittest.TestCase):
    def test_bridge_regressions(self):
        root = Path.cwd()
        source = root / "app/src/main/java" / Path(*PACKAGE.split(".")) / "GatewayMapBridge.java"
        self.assertTrue(source.is_file(), "Run this test from the repository root")
        javac = shutil.which("javac")
        java = shutil.which("java")
        if not javac and os.environ.get("JAVA_HOME"):
            javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac.exe")
            java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe")
        self.assertTrue(javac and java, "A JDK is required")
        with tempfile.TemporaryDirectory(prefix="gateway-map-test-") as directory:
            work = Path(directory)
            sources = []
            fixtures = dict(STUBS)
            fixtures[PACKAGE.replace(".", "/") + "/GatewayMapBridgeRegression.java"] = HARNESS
            for relative, text in fixtures.items():
                path = work / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
                sources.append(str(path))
            classes = work / "classes"
            compile_result = subprocess.run(
                [javac, "-d", str(classes), str(source), *sources],
                capture_output=True, text=True, check=False, timeout=60,
            )
            self.assertEqual(0, compile_result.returncode, compile_result.stderr)
            result = subprocess.run(
                [java, "-cp", str(classes), PACKAGE + ".GatewayMapBridgeRegression"],
                capture_output=True, text=True, check=False, timeout=30,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            print(result.stdout, end="")


if __name__ == "__main__":
    unittest.main()
