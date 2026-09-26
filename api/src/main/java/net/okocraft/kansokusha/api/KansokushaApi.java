package net.okocraft.kansokusha.api;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Optional;

/**
 * Platform-neutral entry point for event providers.
 */
@NotNullByDefault
public interface KansokushaApi {

    /**
     * Returns the local server identity when this API is bound to one server.
     *
     * <p>Paper integrations expose their configured server key here. Proxy
     * integrations such as Velocity do not have one local server identity.</p>
     */
    Optional<Key> localServerKey();

    /**
     * Registers an event type. Registering the same definition again has no effect.
     *
     * @throws IllegalArgumentException if the key is already registered with another payload generation
     */
    void registerEventType(EventTypeDefinition definition);

    /**
     * Hands an event to the asynchronous writer without waiting for storage I/O.
     *
     * <p>{@code true} means that the event was queued; it does not guarantee that the event has
     * already been persisted. {@code false} means that the event was dropped because the queue is
     * full or Kansokusha has shut down.</p>
     *
     * @throws IllegalArgumentException if the event type is not registered with the submitted payload generation
     */
    boolean submit(EventSubmission submission);
}
