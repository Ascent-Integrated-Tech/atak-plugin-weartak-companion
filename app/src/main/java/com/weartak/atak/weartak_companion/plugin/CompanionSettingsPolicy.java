package com.weartak.atak.weartak_companion.plugin;

final class CompanionSettingsPolicy {
    private CompanionSettingsPolicy() {}

    static boolean phoneManaged(boolean setupPurpose, boolean gatewayPurpose) {
        return !setupPurpose && !gatewayPurpose;
    }
}
