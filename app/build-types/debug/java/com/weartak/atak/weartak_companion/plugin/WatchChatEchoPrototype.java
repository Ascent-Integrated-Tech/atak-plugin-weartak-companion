package com.weartak.atak.weartak_companion.plugin;

import android.os.Bundle;

import com.atakmap.android.chat.GeoChatService;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

/** Debug-only observer for the production echo suppressor. */
final class WatchChatEchoPrototype implements GeoChatService.GeoChatDetailHandler {
    private final GeoChatService service;
    private final WatchChatEchoSuppressor suppressor;
    private final ThreadLocal<Boolean> watchSend = new ThreadLocal<>();
    private boolean active;
    private String lastWatchEvent;
    private String lastOutgoingEvent;

    WatchChatEchoPrototype(WatchChatEchoSuppressor suppressor) {
        if (suppressor == null || !suppressor.isActive()) {
            throw new IllegalStateException("Production chat echo suppressor is not active");
        }
        this.suppressor = suppressor;
        service = GeoChatService.getInstance();
        if (service == null) throw new IllegalStateException("ATAK chat service unavailable");
    }

    void start() {
        if (active) return;
        service.registerGeoChatDetailHandler(this);
        active = true;
    }

    void stop() {
        if (!active) return;
        active = false;
        service.unregisterGeoChatDetailHandler(this);
        lastWatchEvent = null;
        lastOutgoingEvent = null;
    }

    void send(Runnable action) {
        Boolean previous = watchSend.get();
        watchSend.set(true);
        try {
            suppressor.send(action);
        } finally {
            if (previous == null) watchSend.remove();
            else watchSend.set(previous);
        }
    }

    @Override public String getDetailName() {
        return "_weartak_echo_prototype";
    }

    @Override public boolean toCotDetail(Bundle message, CotEvent event, CotDetail detail) {
        if (!active || event == null) return false;
        lastOutgoingEvent = event.toString();
        if (Boolean.TRUE.equals(watchSend.get())) lastWatchEvent = event.toString();
        return false;
    }

    @Override public boolean toChatBundle(Bundle message, CotEvent event, CotDetail detail) {
        return false;
    }

    CotEvent lastWatchEvent() {
        if (lastWatchEvent == null) throw new IllegalStateException("Send a simulated watch chat first");
        return CotEvent.parse(lastWatchEvent);
    }

    CotEvent lastOutgoingEvent() {
        if (lastOutgoingEvent == null) throw new IllegalStateException("Send a chat first");
        return CotEvent.parse(lastOutgoingEvent);
    }
}
