package net.okocraft.kansokusha.common.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

class RetentionPolicySetTest {

    private static final Key SHORT_POLICY = Key.key("test", "short");
    private static final Key AUDIT_POLICY = Key.key("test", "audit");
    private static final Key AUDIT_EVENT = Key.key("test", "audit-event");
    private static final Key OTHER_EVENT = Key.key("test", "other-event");
    private static final Key NATURAL_QUALIFIER = Key.key("test", "natural");
    private static final Key SERVER_KEY = Key.key("test", "server");
    private static final EventPayload PAYLOAD = EventPayload.copyOf(new byte[]{1});

    @Test
    void testExactMappingAndFallbackResolveDifferentDurations() throws Exception {
        var policies = new LinkedHashMap<Key, Duration>();
        policies.put(SHORT_POLICY, Duration.ofHours(1));
        policies.put(AUDIT_POLICY, Duration.ofDays(30));

        var settings = new KansokushaConfig.RetentionSettings(
            policies,
            Map.of(AUDIT_EVENT, AUDIT_POLICY),
            SHORT_POLICY
        );
        var policySet = RetentionPolicySet.from(settings);
        var occurredAt = Instant.parse("2026-01-02T03:04:05.987654321Z");

        var audit = policySet.resolve(submission(AUDIT_EVENT, occurredAt));
        var fallback = policySet.resolve(submission(OTHER_EVENT, occurredAt));

        Assertions.assertEquals(AUDIT_POLICY, audit.retentionPolicyKey());
        Assertions.assertEquals(
            Instant.parse("2026-02-01T03:04:05.987Z"),
            audit.expiresAt()
        );
        Assertions.assertEquals(SHORT_POLICY, fallback.retentionPolicyKey());
        Assertions.assertEquals(
            Instant.parse("2026-01-02T04:04:05.987Z"),
            fallback.expiresAt()
        );
    }


    @Test
    void testQualifiedMappingOverridesExactMappingWithoutChangingFallback() throws Exception {
        var settings = new KansokushaConfig.RetentionSettings(
            Map.of(
                SHORT_POLICY, Duration.ofDays(7),
                AUDIT_POLICY, Duration.ofDays(180)
            ),
            Map.of(AUDIT_EVENT, AUDIT_POLICY),
            Map.of(
                new KansokushaConfig.QualifiedEventType(AUDIT_EVENT, NATURAL_QUALIFIER),
                SHORT_POLICY
            ),
            SHORT_POLICY
        );
        var policySet = RetentionPolicySet.from(settings);
        var occurredAt = Instant.parse("2026-01-02T03:04:05Z");

        var qualified = policySet.resolve(
            submission(
                AUDIT_EVENT,
                occurredAt,
                PAYLOAD.withRetentionQualifier(NATURAL_QUALIFIER)
            )
        );
        var exact = policySet.resolve(submission(AUDIT_EVENT, occurredAt));
        var fallback = policySet.resolve(submission(OTHER_EVENT, occurredAt));

        Assertions.assertEquals(SHORT_POLICY, qualified.retentionPolicyKey());
        Assertions.assertEquals(AUDIT_POLICY, exact.retentionPolicyKey());
        Assertions.assertEquals(SHORT_POLICY, fallback.retentionPolicyKey());
    }

    @Test
    void testExpiryIsBasedOnOccurrenceTimeNotResolutionTime() throws Exception {
        var policySet = RetentionPolicySet.from(
            new KansokushaConfig.RetentionSettings(
                Map.of(SHORT_POLICY, Duration.ofMillis(1500)),
                Map.of(),
                SHORT_POLICY
            )
        );
        var occurredAt = Instant.parse("2020-05-06T07:08:09.123999999Z");

        var resolved = policySet.resolve(submission(OTHER_EVENT, occurredAt));

        Assertions.assertEquals(
            Instant.parse("2020-05-06T07:08:10.623Z"),
            resolved.expiresAt()
        );
    }

    @Test
    void testConfigurationSnapshotIsIndependentFromSourceMaps() {
        var policies = new LinkedHashMap<Key, Duration>();
        policies.put(SHORT_POLICY, Duration.ofHours(1));
        var mappings = new LinkedHashMap<Key, Key>();
        mappings.put(AUDIT_EVENT, SHORT_POLICY);

        var settings = new KansokushaConfig.RetentionSettings(
            policies,
            mappings,
            SHORT_POLICY
        );
        var policySet = RetentionPolicySet.from(settings);

        policies.put(AUDIT_POLICY, Duration.ofDays(30));
        mappings.put(OTHER_EVENT, AUDIT_POLICY);

        Assertions.assertEquals(SHORT_POLICY, policySet.resolvePolicy(OTHER_EVENT).key());
        Assertions.assertEquals(Duration.ofHours(1), policySet.resolvePolicy(AUDIT_EVENT).duration());
    }

    @Test
    void testExpiryOverflowFailsWithoutWraparound() {
        var policySet = RetentionPolicySet.from(
            new KansokushaConfig.RetentionSettings(
                Map.of(SHORT_POLICY, Duration.ofSeconds(1)),
                Map.of(),
                SHORT_POLICY
            )
        );

        var error = Assertions.assertThrows(
            RetentionResolutionException.class,
            () -> policySet.resolve(
                submission(OTHER_EVENT, Instant.ofEpochMilli(Long.MAX_VALUE - 500))
            )
        );

        Assertions.assertTrue(error.getMessage().contains(OTHER_EVENT.asString()));
    }

    @Test
    void testDuckDbInfinitySentinelsAreRejected() throws Exception {
        var oneMillisecond = RetentionPolicySet.from(
            new KansokushaConfig.RetentionSettings(
                Map.of(SHORT_POLICY, Duration.ofMillis(1)),
                Map.of(),
                SHORT_POLICY
            )
        );

        var positiveExpirySentinel = Assertions.assertThrows(
            RetentionResolutionException.class,
            () -> oneMillisecond.resolve(
                submission(OTHER_EVENT, Instant.ofEpochMilli(Long.MAX_VALUE - 1))
            )
        );
        Assertions.assertTrue(positiveExpirySentinel.getMessage().contains("expiresAt"));

        var positiveOccurrenceSentinel = Assertions.assertThrows(
            RetentionResolutionException.class,
            () -> oneMillisecond.resolve(
                submission(OTHER_EVENT, Instant.ofEpochMilli(Long.MAX_VALUE))
            )
        );
        Assertions.assertTrue(positiveOccurrenceSentinel.getMessage().contains("occurredAt"));

        var negativeOccurrenceSentinel = Assertions.assertThrows(
            RetentionResolutionException.class,
            () -> oneMillisecond.resolve(
                submission(OTHER_EVENT, Instant.ofEpochMilli(-Long.MAX_VALUE))
            )
        );
        Assertions.assertTrue(negativeOccurrenceSentinel.getMessage().contains("occurredAt"));

        var upperFinite = oneMillisecond.resolve(
            submission(OTHER_EVENT, Instant.ofEpochMilli(Long.MAX_VALUE - 2))
        );
        Assertions.assertEquals(
            Instant.ofEpochMilli(Long.MAX_VALUE - 1),
            upperFinite.expiresAt()
        );

        var lowerFinite = oneMillisecond.resolve(
            submission(OTHER_EVENT, Instant.ofEpochMilli(-Long.MAX_VALUE + 1))
        );
        Assertions.assertEquals(
            Instant.ofEpochMilli(-Long.MAX_VALUE + 2),
            lowerFinite.expiresAt()
        );
    }

    @Test
    void testOccurrenceOutsideEpochMillisecondRangeFails() {
        var policySet = RetentionPolicySet.from(
            new KansokushaConfig.RetentionSettings(
                Map.of(SHORT_POLICY, Duration.ofMillis(1)),
                Map.of(),
                SHORT_POLICY
            )
        );

        Assertions.assertThrows(
            RetentionResolutionException.class,
            () -> policySet.resolve(submission(OTHER_EVENT, Instant.MAX))
        );
    }

    private static EventSubmission submission(Key eventType, Instant occurredAt) {
        return submission(eventType, occurredAt, PAYLOAD);
    }

    private static EventSubmission submission(
        Key eventType,
        Instant occurredAt,
        EventPayload payload
    ) {
        return new EventSubmission(
            eventType,
            PayloadGeneration.FIRST,
            occurredAt,
            SERVER_KEY,
            null,
            null,
            null,
            payload
        );
    }
}
