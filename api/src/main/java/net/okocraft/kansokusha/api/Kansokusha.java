package net.okocraft.kansokusha.api;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides the Kansokusha API published by the running platform plugin.
 */
@NotNullByDefault
public final class Kansokusha {

    static final KansokushaApi CLOSED_API = new KansokushaApi() {
        @Override
        public Optional<Key> localServerKey() {
            return Optional.empty();
        }

        @Override
        public RegistrationOutcome registerEventType(EventTypeDefinition definition) {
            return RegistrationOutcome.CLOSED;
        }

        @Override
        public SubmissionOutcome submit(EventSubmission submission) {
            return SubmissionOutcome.CLOSED;
        }
    };

    static final AtomicReference<KansokushaApi> API = new AtomicReference<>(CLOSED_API);

    private Kansokusha() {
    }

    public static KansokushaApi api() {
        return API.get();
    }
}
