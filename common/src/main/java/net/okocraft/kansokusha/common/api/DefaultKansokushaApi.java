package net.okocraft.kansokusha.common.api;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.common.event.registry.InMemoryRuntimeEventTypeRegistry;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@ApiStatus.Internal
@NotNullByDefault
public final class DefaultKansokushaApi implements KansokushaApi, AutoCloseable {

    private final InMemoryRuntimeEventTypeRegistry registry;
    private final EventIntake intake;
    private final Optional<Key> localServerKey;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultKansokushaApi(InMemoryRuntimeEventTypeRegistry registry, EventIntake intake) {
        this(registry, intake, Optional.empty());
    }

    public DefaultKansokushaApi(InMemoryRuntimeEventTypeRegistry registry, EventIntake intake, Key localServerKey) {
        this(registry, intake, Optional.of(Objects.requireNonNull(localServerKey, "localServerKey")));
    }

    private DefaultKansokushaApi(
        InMemoryRuntimeEventTypeRegistry registry, EventIntake intake, Optional<Key> localServerKey
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

        return this.registry.register(definition);
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
        return switch (this.intake.accept(submission)) {
            case ACCEPTED -> SubmissionOutcome.ACCEPTED;
            case UNAVAILABLE -> SubmissionOutcome.INGESTION_UNAVAILABLE;
            case CLOSED -> SubmissionOutcome.CLOSED;
        };
    }

    @Override
    public void close() {
        this.closed.set(true);
    }
}
