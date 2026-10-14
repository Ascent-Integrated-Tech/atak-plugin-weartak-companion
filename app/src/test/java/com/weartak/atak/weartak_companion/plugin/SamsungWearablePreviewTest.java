package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SamsungWearablePreviewTest {
    @Test public void debugEmulatorWithMarkerEnablesPreview() {
        assertTrue(SamsungWearablePreview.isEnabled(true, "ranchu", true));
        assertTrue(SamsungWearablePreview.isEnabled(true, "goldfish", true));
    }

    @Test public void releaseBuildNeverEnablesPreview() {
        assertFalse(SamsungWearablePreview.isEnabled(false, "ranchu", true));
    }

    @Test public void physicalDeviceNeverEnablesPreview() {
        assertFalse(SamsungWearablePreview.isEnabled(true, "qcom", true));
        assertFalse(SamsungWearablePreview.isEnabled(true, null, true));
    }

    @Test public void absentMarkerKeepsRealDetection() {
        assertFalse(SamsungWearablePreview.isEnabled(true, "ranchu", false));
    }
}
