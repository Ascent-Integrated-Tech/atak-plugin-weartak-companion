package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class BleJsonFramerTest {
    @Test public void fragmentedUtf8IdentityIsPreserved() {
        BleJsonFramer framer = new BleJsonFramer();
        List<String> lines = new ArrayList<>();
        for (byte b : "{\"cs\":\"Équipe 日本\"}\n".getBytes(StandardCharsets.UTF_8)) {
            lines.addAll(framer.accept(new byte[]{b}));
        }
        assertEquals(Arrays.asList("{\"cs\":\"Équipe 日本\"}"), lines);
    }
    @Test public void resetCannotCombineTwoSessionsAndMultipleLinesWork() {
        BleJsonFramer framer = new BleJsonFramer();
        framer.accept("old".getBytes(StandardCharsets.UTF_8));
        framer.clear();
        assertEquals(Arrays.asList("new", "second"),
                framer.accept("new\n\nsecond\n".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void unboundedNotificationFramesFailClosed() {
        BleJsonFramer framer = new BleJsonFramer();
        byte[] bytes = new byte[262145];
        Arrays.fill(bytes, (byte) 'a');
        assertThrows(IllegalArgumentException.class, () -> framer.accept(bytes));
        assertEquals(Arrays.asList("ok"), framer.accept("ok\n".getBytes(StandardCharsets.UTF_8)));
    }
}
