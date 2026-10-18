package com.weartak.atak.weartak_companion.plugin;

final class ChatListenerSwap {
    interface Host {
        void registerDetailHandler();
        void unregisterDetailHandler();
        void removeOriginalListener();
        void restoreOriginalListener();
        void installWrapper();
        void removeWrapper();
    }

    private boolean active;

    synchronized void start(Host host) {
        if (active) return;
        host.registerDetailHandler();
        boolean originalRemoved = false;
        boolean wrapperAttempted = false;
        active = true;
        try {
            host.removeOriginalListener();
            originalRemoved = true;
            wrapperAttempted = true;
            host.installWrapper();
        } catch (RuntimeException failure) {
            active = false;
            if (wrapperAttempted) host.removeWrapper();
            if (originalRemoved) host.restoreOriginalListener();
            host.unregisterDetailHandler();
            throw failure;
        }
    }

    synchronized void stop(Host host) {
        if (!active) return;
        active = false;
        try {
            host.removeWrapper();
        } finally {
            try {
                host.restoreOriginalListener();
            } finally {
                host.unregisterDetailHandler();
            }
        }
    }
}
