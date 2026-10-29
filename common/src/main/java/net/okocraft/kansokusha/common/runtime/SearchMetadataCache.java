package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.storage.QueuedEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the search metadata in memory so that commands do not aggregate the events table every time.
 *
 * <p>The cache is loaded from storage on first use, extended with each committed batch, and dropped
 * when expired events are deleted because that can remove values. It is confined to the storage
 * thread and is not thread-safe.</p>
 */
@NotNullByDefault
final class SearchMetadataCache {

    private @Nullable Map<Key, MutableValues> values;
    private @Nullable SearchMetadata snapshot;

    void load(SearchMetadata metadata) {
        var loaded = new LinkedHashMap<Key, MutableValues>();
        metadata.events().forEach((eventType, eventValues) -> loaded.put(eventType, new MutableValues(
            new HashSet<>(eventValues.worlds()),
            new HashSet<>(eventValues.actorTypes()),
            new HashSet<>(eventValues.targetTypes())
        )));
        this.values = loaded;
        this.snapshot = metadata;
    }

    void invalidate() {
        this.values = null;
        this.snapshot = null;
    }

    /**
     * Reflects events that were committed to storage. Does nothing while the cache is not loaded,
     * because the next load reads them from storage.
     */
    void add(List<QueuedEvent> events) {
        var values = this.values;
        if (values == null) {
            return;
        }

        var changed = false;
        for (var queued : events) {
            var submission = queued.submission();
            var eventValues = values.get(submission.eventType());
            if (eventValues == null) {
                eventValues = new MutableValues(new HashSet<>(), new HashSet<>(), new HashSet<>());
                values.put(submission.eventType(), eventValues);
                changed = true;
            }

            var world = submission.worldKey();
            if (world != null) {
                changed |= eventValues.worlds().add(world);
            }
            var actorType = switch (submission.actor()) {
                case EntityActor entity -> entity.entityType();
                case BlockActor block -> block.blockType();
                case null, default -> null;
            };
            if (actorType != null) {
                changed |= eventValues.actorTypes().add(actorType);
            }
            var targetType = submission.targetType();
            if (targetType != null) {
                changed |= eventValues.targetTypes().add(targetType);
            }
        }

        if (changed) {
            this.snapshot = null;
        }
    }

    /**
     * Returns the metadata, rebuilding the immutable snapshot only if values were added since the last call.
     * Must only be called while the cache is loaded.
     */
    SearchMetadata snapshot() {
        var snapshot = this.snapshot;
        if (snapshot == null) {
            var values = this.values;
            if (values == null) {
                throw new IllegalStateException("The search metadata is not loaded.");
            }
            var events = new LinkedHashMap<Key, SearchMetadata.EventValues>();
            values.forEach((eventType, eventValues) -> events.put(eventType, new SearchMetadata.EventValues(
                eventValues.worlds(),
                eventValues.actorTypes(),
                eventValues.targetTypes()
            )));
            snapshot = new SearchMetadata(events);
            this.snapshot = snapshot;
        }
        return snapshot;
    }

    boolean isLoaded() {
        return this.values != null;
    }

    private record MutableValues(Set<Key> worlds, Set<Key> actorTypes, Set<Key> targetTypes) {
    }
}
