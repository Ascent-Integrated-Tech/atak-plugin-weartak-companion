package com.weartak.atak.weartak_companion.plugin;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import com.atakmap.android.chat.GeoChatService;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

final class WatchChatEchoSuppressor implements GeoChatService.GeoChatDetailHandler,
        CotServiceRemote.CotEventListener {
    interface ListenerHost {
        void registerDetailHandler(GeoChatService.GeoChatDetailHandler handler);
        void unregisterDetailHandler(GeoChatService.GeoChatDetailHandler handler);
        void removeGeoChatService();
        void addGeoChatService();
        void addSuppressor(CotServiceRemote.CotEventListener listener);
        void removeSuppressor(CotServiceRemote.CotEventListener listener);
        void forwardToGeoChatService(CotEvent event, Bundle extras);
    }

    private static final String TAG = "WTK/ChatEcho";
    private static final int CAPACITY = 256;
    private static final long LIFETIME_MILLIS = 10 * 60 * 1000;

    private final ListenerHost host;
    private final ChatListenerSwap listenerSwap = new ChatListenerSwap();
    private final WatchChatEchoTracker tracker = new WatchChatEchoTracker(CAPACITY, LIFETIME_MILLIS);
    private final ThreadLocal<Boolean> watchSend = new ThreadLocal<>();
    private volatile boolean active;

    WatchChatEchoSuppressor() {
        this(new AtakListenerHost());
    }

    WatchChatEchoSuppressor(ListenerHost host) {
        if (host == null) throw new IllegalArgumentException("ATAK chat listener host is required");
        this.host = host;
    }

    synchronized void start() {
        if (active) return;
        active = true;
        try {
            listenerSwap.start(listenerSwapHost());
        } catch (RuntimeException failure) {
            active = false;
            tracker.clear();
            throw failure;
        }
        Log.w(TAG, "Installed exact watch-chat echo filter; ATAK listener replacement is non-atomic");
    }

    synchronized void stop() {
        if (!active) return;
        active = false;
        try {
            listenerSwap.stop(listenerSwapHost());
        } finally {
            tracker.clear();
        }
        Log.i(TAG, "Restored ATAK GeoChatService listener");
    }

    boolean isActive() {
        return active;
    }

    void send(Runnable action) {
        if (action == null) throw new IllegalArgumentException("Watch chat action is required");
        if (!active) {
            action.run();
            return;
        }
        Boolean previous = watchSend.get();
        watchSend.set(true);
        try {
            action.run();
        } finally {
            if (previous == null) watchSend.remove();
            else watchSend.set(previous);
        }
    }

    @Override public String getDetailName() {
        return "_weartak_watch_chat_echo";
    }

    @Override public boolean toCotDetail(Bundle message, CotEvent event, CotDetail detail) {
        if (!active || !Boolean.TRUE.equals(watchSend.get())) return false;
        CotDetail chat = event == null ? null : event.findDetail("__chat");
        CotDetail remarks = event == null ? null : event.findDetail("remarks");
        if (chat == null || remarks == null) {
            Log.w(TAG, "Watch chat missing CoT identity details; echo will not be suppressed");
            return false;
        }
        try {
            tracker.record(event.getType(), event.getUID(), remarks.getAttribute("source"),
                    chat.getAttribute("id"), chat.getAttribute("messageId"),
                    remarks.getInnerText(), SystemClock.elapsedRealtime());
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Watch chat identity incomplete; echo will not be suppressed", e);
        }
        return false;
    }

    @Override public boolean toChatBundle(Bundle message, CotEvent event, CotDetail detail) {
        return false;
    }

    @Override public void onCotEvent(CotEvent event, Bundle extras) {
        CotDetail chat = event == null ? null : event.findDetail("__chat");
        CotDetail remarks = event == null ? null : event.findDetail("remarks");
        if (active && chat != null && remarks != null
                && tracker.isEcho(event.getType(), event.getUID(), remarks.getAttribute("source"),
                chat.getAttribute("id"), chat.getAttribute("messageId"), remarks.getInnerText(),
                SystemClock.elapsedRealtime())) {
            Log.i(TAG, "Suppressed exact watch chat echo before ATAK incoming processing");
            return;
        }
        host.forwardToGeoChatService(event, extras);
    }

    private ChatListenerSwap.Host listenerSwapHost() {
        return new ChatListenerSwap.Host() {
            @Override public void registerDetailHandler() { host.registerDetailHandler(WatchChatEchoSuppressor.this); }
            @Override public void unregisterDetailHandler() { host.unregisterDetailHandler(WatchChatEchoSuppressor.this); }
            @Override public void removeOriginalListener() { host.removeGeoChatService(); }
            @Override public void restoreOriginalListener() { host.addGeoChatService(); }
            @Override public void installWrapper() { host.addSuppressor(WatchChatEchoSuppressor.this); }
            @Override public void removeWrapper() { host.removeSuppressor(WatchChatEchoSuppressor.this); }
        };
    }

    private static final class AtakListenerHost implements ListenerHost {
        private final GeoChatService service;
        private final CommsMapComponent comms;

        AtakListenerHost() {
            service = GeoChatService.getInstance();
            comms = CommsMapComponent.getInstance();
            if (service == null || comms == null) {
                throw new IllegalStateException("ATAK chat services unavailable for echo suppression");
            }
        }

        @Override public void registerDetailHandler(GeoChatService.GeoChatDetailHandler handler) {
            service.registerGeoChatDetailHandler(handler);
        }

        @Override public void unregisterDetailHandler(GeoChatService.GeoChatDetailHandler handler) {
            service.unregisterGeoChatDetailHandler(handler);
        }

        @Override public void removeGeoChatService() {
            comms.removeOnCotEventListener(service);
        }

        @Override public void addGeoChatService() {
            comms.addOnCotEventListener(service);
        }

        @Override public void addSuppressor(CotServiceRemote.CotEventListener listener) {
            comms.addOnCotEventListener(listener);
        }

        @Override public void removeSuppressor(CotServiceRemote.CotEventListener listener) {
            comms.removeOnCotEventListener(listener);
        }

        @Override public void forwardToGeoChatService(CotEvent event, Bundle extras) {
            service.onCotEvent(event, extras);
        }
    }
}
