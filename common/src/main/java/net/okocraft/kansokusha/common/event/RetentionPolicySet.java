package net.okocraft.kansokusha.common.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@NotNullByDefault
public final class RetentionPolicySet {

    private static final long MIN_FINITE_TIMESTAMP_MILLIS = -Long.MAX_VALUE + 1;
    private static final long MAX_FINITE_TIMESTAMP_MILLIS = Long.MAX_VALUE - 1;

    private final Map<Key, RetentionPolicy> exactMappings;
    private final RetentionPolicy fallback;

    private RetentionPolicySet(
        Map<Key, RetentionPolicy> exactMappings,
        RetentionPolicy fallback
    ) {
        this.exactMappings = Map.copyOf(exactMappings);
        this.fallback = fallback;
    }

    public static RetentionPolicySet from(KansokushaConfig.RetentionSettings settings) {
        Objects.requireNonNull(settings, "settings");

        var exactMappings = new HashMap<Key, RetentionPolicy>();
        settings.eventTypeMappings().forEach(
            (eventType, policyKey) -> exactMappings.put(eventType, policy(settings, policyKey))
        );
        return new RetentionPolicySet(exactMappings, policy(settings, settings.fallbackPolicy()));
    }

    private static RetentionPolicy policy(KansokushaConfig.RetentionSettings settings, Key key) {
        return new RetentionPolicy(key, settings.policies().get(key));
    }

    public RetentionPolicy resolvePolicy(Key eventType) {
        Objects.requireNonNull(eventType, "eventType");
        return this.exactMappings.getOrDefault(eventType, this.fallback);
    }

    public AcceptedEvent resolve(EventSubmission submission) throws RetentionResolutionException {
        Objects.requireNonNull(submission, "submission");

        var policy = this.resolvePolicy(submission.eventType());
        var occurredAt = submission.occurredAt().truncatedTo(ChronoUnit.MILLIS);

        final long occurredAtMillis;
        try {
            occurredAtMillis = occurredAt.toEpochMilli();
        } catch (ArithmeticException e) {
            throw new RetentionResolutionException(
                "occurredAt is outside the supported millisecond range for event type "
                    + submission.eventType().asString(),
                e
            );
        }
        requireFiniteDuckDbTimestamp(occurredAtMillis, "occurredAt", submission.eventType());

        final long expiresAtMillis;
        try {
            expiresAtMillis = Math.addExact(occurredAtMillis, policy.duration().toMillis());
        } catch (ArithmeticException e) {
            throw new RetentionResolutionException(
                "expiresAt is outside the supported millisecond range for event type "
                    + submission.eventType().asString(),
                e
            );
        }
        requireFiniteDuckDbTimestamp(expiresAtMillis, "expiresAt", submission.eventType());

        return new AcceptedEvent(
            submission,
            policy.key(),
            Instant.ofEpochMilli(expiresAtMillis)
        );
    }

    private static void requireFiniteDuckDbTimestamp(
        long epochMillis,
        String field,
        Key eventType
    ) throws RetentionResolutionException {
        if (epochMillis < MIN_FINITE_TIMESTAMP_MILLIS || epochMillis > MAX_FINITE_TIMESTAMP_MILLIS) {
            throw new RetentionResolutionException(
                field + " resolves to a DuckDB TIMESTAMP_MS infinity sentinel or out-of-range value for event type "
                    + eventType.asString()
            );
        }
    }

    public record RetentionPolicy(Key key, Duration duration) {

        public RetentionPolicy {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(duration, "duration");
        }
    }
}
