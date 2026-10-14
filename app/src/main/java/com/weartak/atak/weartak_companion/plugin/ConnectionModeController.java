package com.weartak.atak.weartak_companion.plugin;

/** Main-thread transport ownership. Invalidate callbacks before stopping the previous route. */
public final class ConnectionModeController {
    public enum Mode {
        SAMSUNG("samsung_system_bond", "Use Samsung Wearable Bond"),
        TRADITIONAL("traditional_ble", "Traditional BLE Pairing"),
        GARMIN("garmin_connect_iq", "Garmin Connect IQ");

        public final String value;
        public final String label;

        Mode(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    public interface Transport {
        void stop();
        void start(Mode mode, long token);
    }

    private final Transport transport;
    private Mode mode = Mode.TRADITIONAL;
    private long generation;
    private boolean running;
    private boolean gatewayPurpose;

    public ConnectionModeController(Transport transport) { this.transport = transport; }

    public static Mode restore(String saved, boolean legacySamsungEnabled) {
        if (saved == null) return legacySamsungEnabled ? Mode.SAMSUNG : Mode.TRADITIONAL;
        for (Mode mode : Mode.values()) if (mode.value.equals(saved)) return mode;
        return Mode.TRADITIONAL;
    }

    public Mode getMode() { return mode; }
    public boolean isPairingLocked() { return gatewayPurpose; }
    public boolean isCurrent(long token) { return running && generation == token; }

    public void start(Mode selected) {
        if (gatewayPurpose) selected = Mode.TRADITIONAL;
        if (running && mode == selected) return;
        stop();
        mode = selected;
        running = true;
        transport.start(mode, generation);
    }

    public void start(Mode selected, boolean gatewayPurpose) {
        this.gatewayPurpose = gatewayPurpose;
        start(selected);
    }

    public void select(Mode selected) {
        if (!gatewayPurpose) start(selected);
    }

    public void changePurpose(boolean gatewayPurpose) {
        this.gatewayPurpose = gatewayPurpose;
        stop();
        if (gatewayPurpose) mode = Mode.TRADITIONAL;
        running = true;
        transport.start(mode, generation);
    }
    public void restart() {
        stop();
        running = true;
        transport.start(mode, generation);
    }

    public void stop() {
        ++generation;
        running = false;
        transport.stop();
    }
}
