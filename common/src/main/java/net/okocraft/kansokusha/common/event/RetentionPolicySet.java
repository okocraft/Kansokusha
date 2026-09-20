package net.okocraft.kansokusha.common.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@NotNullByDefault
public final class RetentionPolicySet {

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

        var policies = new LinkedHashMap<Key, RetentionPolicy>();
        for (var entry : settings.policies().entrySet()) {
            var key = Objects.requireNonNull(entry.getKey(), "policy key");
            var duration = Objects.requireNonNull(entry.getValue(), "policy duration");
            validateDuration(key, duration);
            policies.put(key, new RetentionPolicy(key, duration));
        }

        if (policies.isEmpty()) {
            throw new IllegalArgumentException("At least one retention policy is required.");
        }

        var fallback = policies.get(settings.fallbackPolicy());
        if (fallback == null) {
            throw new IllegalArgumentException(
                "Fallback references unknown retention policy " + settings.fallbackPolicy().asString()
            );
        }

        var exactMappings = new LinkedHashMap<Key, RetentionPolicy>();
        for (var entry : settings.eventTypeMappings().entrySet()) {
            var eventType = Objects.requireNonNull(entry.getKey(), "event type key");
            var policyKey = Objects.requireNonNull(entry.getValue(), "mapped policy key");
            var policy = policies.get(policyKey);
            if (policy == null) {
                throw new IllegalArgumentException(
                    "Event type " + eventType.asString()
                        + " references unknown retention policy " + policyKey.asString()
                );
            }
            exactMappings.put(eventType, policy);
        }

        return new RetentionPolicySet(exactMappings, fallback);
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
        final long expiresAtMillis;

        try {
            occurredAtMillis = occurredAt.toEpochMilli();
            expiresAtMillis = Math.addExact(occurredAtMillis, policy.duration().toMillis());
        } catch (ArithmeticException e) {
            throw new RetentionResolutionException(
                "Retention expiry is outside the supported millisecond timestamp range for event type "
                    + submission.eventType().asString(),
                e
            );
        }

        return new AcceptedEvent(
            submission,
            policy.key(),
            Instant.ofEpochMilli(expiresAtMillis)
        );
    }

    private static void validateDuration(Key key, Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                "Retention policy " + key.asString() + " must have a positive duration."
            );
        }
        if (duration.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(
                "Retention policy " + key.asString() + " must use whole milliseconds."
            );
        }

        try {
            duration.toMillis();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                "Retention policy " + key.asString() + " exceeds the supported millisecond range.",
                e
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
