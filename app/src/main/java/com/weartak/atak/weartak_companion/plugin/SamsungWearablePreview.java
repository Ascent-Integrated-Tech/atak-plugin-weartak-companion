package com.weartak.atak.weartak_companion.plugin;

final class SamsungWearablePreview {
    private SamsungWearablePreview() {}

    static boolean isEnabled(boolean debugBuild, String hardware, boolean markerExists) {
        return debugBuild && markerExists
                && ("ranchu".equals(hardware) || "goldfish".equals(hardware));
    }
}
