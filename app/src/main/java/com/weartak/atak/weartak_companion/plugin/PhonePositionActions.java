package com.weartak.atak.weartak_companion.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Keeps action identity stable while a newly requested phone fix is acquired. */
final class PhonePositionActions {
    private static final class Action {
        final Consumer<PhonePosition> publish;
        final boolean continuous;
        final long started;
        PhonePosition sent;

        Action(Consumer<PhonePosition> publish, boolean continuous, long started) {
            this.publish = publish;
            this.continuous = continuous;
            this.started = started;
        }
    }

    private final Map<String, Action> actions = new LinkedHashMap<>();
    private PhonePosition best;

    synchronized void observe(PhonePosition fix) {
        if (fix == null || best != null && (fix.time < best.time
                || fix.time == best.time && fix.ce >= best.ce)) return;
        best = fix;
        for (Map.Entry<String, Action> entry : new ArrayList<>(actions.entrySet())) {
            Action action = entry.getValue();
            if (action.sent != fix) {
                action.publish.accept(fix);
                action.sent = fix;
            }
            if (!action.continuous && fix.time >= action.started) actions.remove(entry.getKey());
        }
    }

    synchronized void request(String key, boolean continuous, long now,
                              Consumer<PhonePosition> publish, Runnable unavailable) {
        if (continuous && actions.containsKey(key)) return;
        Action action = new Action(publish, continuous, now);
        actions.put(key, action);
        if (best != null) {
            action.sent = best;
            publish.accept(best);
        } else {
            unavailable.run();
        }
    }

    synchronized PhonePosition best() { return best; }
    synchronized void cancel(String key) { actions.remove(key); }
    synchronized void expireOneShots(long now) {
        actions.entrySet().removeIf(entry -> !entry.getValue().continuous
                && now - entry.getValue().started >= 30000);
    }
    synchronized boolean hasActions() { return !actions.isEmpty(); }
    synchronized void stop() { actions.clear(); }
}
