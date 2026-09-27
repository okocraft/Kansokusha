package net.okocraft.kansokusha.common.search;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Historical values available for platform search completion and permission scoping.
 *
 * <p>Completion metadata remains associated with the event type that produced it so platform
 * permission filtering can be applied before values are exposed to a command sender.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public record SearchMetadata(Map<Key, EventValues> events) {

    public SearchMetadata {
        Objects.requireNonNull(events, "events");
        var copy = new LinkedHashMap<Key, EventValues>();
        events.forEach((eventType, values) ->
            copy.put(Objects.requireNonNull(eventType, "eventType"), Objects.requireNonNull(values, "values"))
        );
        events = Map.copyOf(copy);
    }

    public static SearchMetadata empty() {
        return new SearchMetadata(Map.of());
    }

    public Set<Key> eventTypes() {
        return this.events.keySet();
    }

    public SearchMetadata retainEventTypes(Set<Key> allowedEventTypes) {
        Objects.requireNonNull(allowedEventTypes, "allowedEventTypes");
        var retained = new LinkedHashMap<Key, EventValues>();
        for (var eventType : allowedEventTypes) {
            var values = this.events.get(eventType);
            if (values != null) {
                retained.put(eventType, values);
            }
        }
        return new SearchMetadata(retained);
    }

    public Set<Key> worlds() {
        return aggregate(EventValues::worlds);
    }

    public Set<Key> actorTypes() {
        return aggregate(EventValues::actorTypes);
    }

    public Set<Key> targetTypes() {
        return aggregate(EventValues::targetTypes);
    }

    private Set<Key> aggregate(Function<EventValues, Set<Key>> values) {
        var result = new LinkedHashSet<Key>();
        for (var metadata : this.events.values()) {
            result.addAll(values.apply(metadata));
        }
        return Set.copyOf(result);
    }

    public record EventValues(
        Set<Key> worlds,
        Set<Key> actorTypes,
        Set<Key> targetTypes
    ) {

        public EventValues {
            worlds = Set.copyOf(worlds);
            actorTypes = Set.copyOf(actorTypes);
            targetTypes = Set.copyOf(targetTypes);
        }

        public static EventValues empty() {
            return new EventValues(Set.of(), Set.of(), Set.of());
        }
    }
}
