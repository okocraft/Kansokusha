package net.okocraft.kansokusha.common.search;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Set;

/**
 * Historical values available for platform search completion and permission scoping.
 */
@ApiStatus.Internal
@NotNullByDefault
public record SearchMetadata(
    Set<Key> eventTypes,
    Set<Key> worlds,
    Set<Key> actorTypes,
    Set<Key> targetTypes
) {

    public SearchMetadata {
        eventTypes = Set.copyOf(eventTypes);
        worlds = Set.copyOf(worlds);
        actorTypes = Set.copyOf(actorTypes);
        targetTypes = Set.copyOf(targetTypes);
    }

    public static SearchMetadata empty() {
        return new SearchMetadata(Set.of(), Set.of(), Set.of(), Set.of());
    }
}
