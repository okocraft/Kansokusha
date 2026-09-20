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

    RegistrationOutcome registerEventType(EventTypeDefinition definition);

    SubmissionOutcome submit(EventSubmission submission);
}
