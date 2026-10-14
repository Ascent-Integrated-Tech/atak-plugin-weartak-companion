package com.weartak.atak.weartak_companion.plugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Decode only complete lines: notifications can split a UTF-8 callsign between bytes. */
final class BleJsonFramer {
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    List<String> accept(byte[] bytes) {
        List<String> lines = new ArrayList<>();
        for (byte value : bytes) {
            if (value == '\n') {
                String line = new String(pending.toByteArray(), StandardCharsets.UTF_8).trim();
                pending.reset();
                if (!line.isEmpty()) lines.add(line);
            } else {
                if (pending.size() >= 262144) {
                    pending.reset();
                    throw new IllegalArgumentException("BLE JSON frame exceeds 256 KiB");
                }
                pending.write(value);
            }
        }
        return lines;
    }
    void clear() { pending.reset(); }
}
