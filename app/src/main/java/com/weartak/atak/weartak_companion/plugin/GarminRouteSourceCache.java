package com.weartak.atak.weartak_companion.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Last-valid routes belong to their originating map item, not just their CoT UID. */
final class GarminRouteSourceCache<T> {
    private final Map<T, GarminRouteProtocol.Route> lastValid = new IdentityHashMap<>();
    private final Set<T> reportedInvalid = Collections.newSetFromMap(new IdentityHashMap<>());

    List<GarminRouteProtocol.Route> scan(Collection<T> items,
            Function<T, GarminRouteProtocol.Route> normalize, Consumer<String> rejected) {
        Set<T> active = Collections.newSetFromMap(new IdentityHashMap<>());
        active.addAll(items);
        lastValid.keySet().retainAll(active);
        reportedInvalid.retainAll(active);
        Map<String, GarminRouteProtocol.Route> found = new LinkedHashMap<>();
        for (T item : items) {
            GarminRouteProtocol.Route route;
            try {
                route = normalize.apply(item);
                if (route == null) throw new IllegalArgumentException("Route cannot be exported");
                lastValid.put(item, route);
                reportedInvalid.remove(item);
            } catch (RuntimeException e) {
                route = lastValid.get(item);
                if (reportedInvalid.add(item) && rejected != null) {
                    rejected.accept("Invalid ATAK route was not sent.");
                }
            }
            if (route != null) found.put(route.key(), route);
        }
        return new ArrayList<>(found.values());
    }

    void clear() {
        lastValid.clear();
        reportedInvalid.clear();
    }
}
