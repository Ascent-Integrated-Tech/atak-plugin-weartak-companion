package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.*;

public class ChatListenerSwapTest {
    @Test public void repeatedStartStopAndRestartRestoreExactlyOneListener() {
        FakeHost host = new FakeHost();
        ChatListenerSwap swap = new ChatListenerSwap();

        swap.start(host);
        swap.start(host);
        assertFalse(host.originalRegistered);
        assertTrue(host.wrapperRegistered);
        assertTrue(host.handlerRegistered);
        assertEquals(1, host.wrapperAdds);

        swap.stop(host);
        swap.stop(host);
        assertTrue(host.originalRegistered);
        assertFalse(host.wrapperRegistered);
        assertFalse(host.handlerRegistered);
        assertEquals(1, host.originalAdds);

        swap.start(host);
        assertTrue(host.wrapperRegistered);
        swap.stop(host);
        assertTrue(host.originalRegistered);
        assertFalse(host.wrapperRegistered);
        assertEquals(2, host.originalAdds);
    }

    @Test public void failedWrapperInstallRollsBackOriginalListenerAndDetailHandler() {
        FakeHost host = new FakeHost();
        host.failWrapperAdd = true;
        ChatListenerSwap swap = new ChatListenerSwap();

        try {
            swap.start(host);
            fail("Listener installation failure must be reported");
        } catch (IllegalStateException expected) {
            assertEquals("injected wrapper failure", expected.getMessage());
        }

        assertTrue(host.originalRegistered);
        assertFalse(host.wrapperRegistered);
        assertFalse(host.handlerRegistered);
        assertEquals(1, host.originalAdds);
    }

    private static final class FakeHost implements ChatListenerSwap.Host {
        boolean originalRegistered = true;
        boolean wrapperRegistered;
        boolean handlerRegistered;
        boolean failWrapperAdd;
        int wrapperAdds;
        int originalAdds;

        @Override public void registerDetailHandler() {
            assertFalse(handlerRegistered);
            handlerRegistered = true;
        }

        @Override public void unregisterDetailHandler() {
            assertTrue(handlerRegistered);
            handlerRegistered = false;
        }

        @Override public void removeOriginalListener() {
            assertTrue(originalRegistered);
            originalRegistered = false;
        }

        @Override public void restoreOriginalListener() {
            assertFalse(originalRegistered);
            originalRegistered = true;
            originalAdds++;
        }

        @Override public void installWrapper() {
            wrapperAdds++;
            if (failWrapperAdd) throw new IllegalStateException("injected wrapper failure");
            assertFalse(wrapperRegistered);
            wrapperRegistered = true;
        }

        @Override public void removeWrapper() {
            wrapperRegistered = false;
        }
    }
}
