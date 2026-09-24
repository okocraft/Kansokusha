package net.okocraft.kansokusha.paper.builtin;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Thread-safe identity map from an in-flight platform event to its LOWEST-priority snapshot.
 */
@NotNullByDefault
final class PaperInFlightMap<E, S> {

    private final Map<E, S> entries = new IdentityHashMap<>();

    synchronized void put(E event, S snapshot) {
        this.entries.put(event, snapshot);
    }

    synchronized @Nullable S remove(E event) {
        return this.entries.remove(event);
    }

    synchronized void clear() {
        this.entries.clear();
    }

    synchronized int size() {
        return this.entries.size();
    }
}
