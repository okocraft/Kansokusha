package net.okocraft.kansokusha.common.api;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.common.event.registry.RegistrationStatus;
import net.okocraft.kansokusha.common.event.registry.RuntimeEventTypeRegistry;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@ApiStatus.Internal
@NotNullByDefault
public final class DefaultKansokushaApi implements KansokushaApi, AutoCloseable {

    private final RuntimeEventTypeRegistry registry;
    private final EventIntake intake;
    private final Optional<Key> localServerKey;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultKansokushaApi(RuntimeEventTypeRegistry registry, EventIntake intake) {
        this(registry, intake, Optional.empty());
    }

    public DefaultKansokushaApi(RuntimeEventTypeRegistry registry, EventIntake intake, Key localServerKey) {
        this(registry, intake, Optional.of(Objects.requireNonNull(localServerKey, "localServerKey")));
    }

    private DefaultKansokushaApi(
        RuntimeEventTypeRegistry registry, EventIntake intake, Optional<Key> localServerKey
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.intake = Objects.requireNonNull(intake, "intake");
        this.localServerKey = localServerKey;
    }

    @Override
    public Optional<Key> localServerKey() {
        return this.localServerKey;
    }

    @Override
    public RegistrationOutcome registerEventType(EventTypeDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (this.closed.get()) {
            return RegistrationOutcome.CLOSED;
        }

        return switch (this.registry.register(definition)) {
            case REGISTERED -> RegistrationOutcome.REGISTERED;
            case ALREADY_REGISTERED -> RegistrationOutcome.ALREADY_REGISTERED;
            case CONFLICT -> RegistrationOutcome.CONFLICT;
        };
    }

    @Override
    public SubmissionOutcome submit(EventSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        if (this.closed.get()) {
            return SubmissionOutcome.CLOSED;
        }

        EventTypeDefinition definition = this.registry.find(submission.eventType()).orElse(null);
        if (definition == null) {
            return SubmissionOutcome.UNREGISTERED_EVENT_TYPE;
        }
        if (!definition.payloadGeneration().equals(submission.payloadGeneration())) {
            return SubmissionOutcome.PAYLOAD_GENERATION_MISMATCH;
        }
        return this.intake.accept(submission)
            ? SubmissionOutcome.ACCEPTED
            : SubmissionOutcome.INGESTION_UNAVAILABLE;
    }

    @Override
    public void close() {
        this.closed.set(true);
    }
}
