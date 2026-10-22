"""Run the production phone metadata reader against deterministic ATAK API doubles.

No device, credentials, Android runtime or network is used. Run from the repo root
with a JDK on PATH (or JAVA_HOME).
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


PACKAGE = "com.weartak.atak.weartak_companion.plugin"
STUBS = {
    "com/atakmap/android/maps/MapView.java": """
package com.atakmap.android.maps;
public class MapView {
    public String team;
    public MapView getMapData() { return this; }
    public Object getContext() { return this; }
    public String getMetaString(String key, String fallback) {
        if (!key.equals("deviceTeam")) throw new AssertionError(key);
        return team == null ? fallback : team;
    }
}
""",
    "com/atakmap/android/preference/AtakPreferences.java": """
package com.atakmap.android.preference;
public class AtakPreferences {
    public static String role;
    public static boolean publish = true;
    public static AtakPreferences getInstance(Object context) {
        if (context == null) throw new AssertionError("Host context required");
        return new AtakPreferences();
    }
    public String get(String key, String fallback) {
        if (!key.equals("atakRoleType")) throw new AssertionError(key);
        return role == null ? fallback : role;
    }
    public boolean get(String key, boolean fallback) {
        if (!key.equals("dispatchTAKVersionNumber") || !fallback) throw new AssertionError(key);
        return publish;
    }
}
""",
    "com/atakmap/android/cot/detail/TakVersionDetailHandler.java": """
package com.atakmap.android.cot.detail;
public class TakVersionDetailHandler {
    public static String version = "5.6.actual.123";
    public static String getVersion() { return version; }
    public static String getDeviceDescription() { return "Actual Phone"; }
    public static String getDeviceOS() { return "Actual OS"; }
}
""",
    "com/atakmap/android/util/ATAKConstants.java": """
package com.atakmap.android.util;
public class ATAKConstants {
    public static String getBrand() { return "Actual ATAK"; }
    public static String getVersionBrand() { return "CIV"; }
}
""",
    "com/weartak/atak/weartak_companion/plugin/LegacyPliCot.java": """
package com.weartak.atak.weartak_companion.plugin;
class LegacyPliCot {
    static class Metadata {
        final String team, role, platform, version, device, os;
        Metadata(String team, String role, String platform, String version, String device, String os) {
            this.team = team; this.role = role; this.platform = platform;
            this.version = version; this.device = device; this.os = os;
        }
    }
}
""",
    "com/weartak/atak/weartak_companion/plugin/PhoneIdentityHarness.java": """
package com.weartak.atak.weartak_companion.plugin;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.cot.detail.TakVersionDetailHandler;
public class PhoneIdentityHarness {
    static void equal(String expected, String actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        MapView map = new MapView();
        equal("Blue", PhoneIdentity.team(map));
        equal("Team Member", PhoneIdentity.role(map));
        equal("Blue", PhoneIdentity.team(null));
        equal("Team Member", PhoneIdentity.role(null));
        map.team = " Cyan ";
        AtakPreferences.role = " Medic ";
        LegacyPliCot.Metadata data = PhoneIdentity.metadata(map);
        equal("Cyan", data.team);
        equal("Medic", data.role);
        equal("Actual ATAK", data.platform);
        equal("5.6.actual.123-CIV", data.version);
        equal("Actual Phone", data.device);
        equal("Actual OS", data.os);
        map.team = "Green";
        AtakPreferences.role = "Team Lead";
        data = PhoneIdentity.metadata(map);
        equal("Green", data.team);
        equal("Team Lead", data.role);
        AtakPreferences.publish = false;
        data = PhoneIdentity.metadata(map);
        equal(null, data.platform);
        equal(null, data.version);
        equal(null, data.device);
        equal(null, data.os);
        equal("Green", data.team);
        AtakPreferences.publish = true;
        TakVersionDetailHandler.version = "";
        equal("", PhoneIdentity.metadata(map).version);
        map.team = " ";
        AtakPreferences.role = "";
        equal("Blue", PhoneIdentity.team(map));
        equal("Team Member", PhoneIdentity.role(map));
        System.out.println("PhoneIdentity runtime API boundary checks passed");
    }
}
""",
}


class PhoneIdentityTest(unittest.TestCase):
    def test_runtime_phone_sources_and_privacy(self):
        root = Path(__file__).resolve().parents[2]
        java_home = os.environ.get("JAVA_HOME")
        javac = str(Path(java_home) / "bin" / "javac") if java_home else shutil.which("javac")
        java = str(Path(java_home) / "bin" / "java") if java_home else shutil.which("java")
        self.assertTrue(javac and java, "A JDK is required")
        with tempfile.TemporaryDirectory(prefix="weartak-phone-identity-") as directory:
            work = Path(directory)
            sources = []
            for name, source in STUBS.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
                sources.append(str(path))
            production = root / "app" / "src" / "main" / "java" / Path(*PACKAGE.split("."))
            sources.append(str(production / "PhoneIdentity.java"))
            subprocess.run([javac, "-d", str(work), *sources], check=True, capture_output=True, text=True)
            result = subprocess.run([java, "-cp", str(work), PACKAGE + ".PhoneIdentityHarness"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("checks passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
