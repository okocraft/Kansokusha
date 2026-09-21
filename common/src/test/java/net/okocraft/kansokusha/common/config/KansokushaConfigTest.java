package net.okocraft.kansokusha.common.config;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

class KansokushaConfigTest {

    @Test
    void testValidRetentionConfigurationLoadsThroughHolder(@TempDir Path dir) throws Exception {
        writeConfig(
            dir,
            """
                debug: true
                ingestion:
                  queue-capacity: 1024
                  max-batch-size: 128
                  max-batch-delay: PT0.25S
                retention:
                  policies:
                    - key: example:short
                      duration: PT30M
                    - key: example:audit
                      duration: P30D
                  event-type-mappings:
                    - event-type: example:block_break
                      policy: example:audit
                  fallback-policy: example:short
                  cleanup-interval: PT5M
                  max-rows-per-pass: 250
                """
        );

        var holder = new KansokushaConfig.Holder(dir);
        holder.reload();

        var config = holder.get();
        Assertions.assertTrue(config.debug());

        var ingestion = config.ingestionSettings();
        Assertions.assertEquals(1024, ingestion.queueCapacity());
        Assertions.assertEquals(128, ingestion.maxBatchSize());
        Assertions.assertEquals(Duration.ofMillis(250), ingestion.maxBatchDelay());

        var retention = config.retentionSettings();
        Assertions.assertEquals(
            Duration.ofMinutes(30),
            retention.policies().get(Key.key("example", "short"))
        );
        Assertions.assertEquals(
            Duration.ofDays(30),
            retention.policies().get(Key.key("example", "audit"))
        );
        Assertions.assertEquals(
            Key.key("example", "audit"),
            retention.eventTypeMappings().get(Key.key("example", "block_break"))
        );
        Assertions.assertEquals(Key.key("example", "short"), retention.fallbackPolicy());

        var cleanup = config.retentionCleanupSettings();
        Assertions.assertEquals(Duration.ofMinutes(5), cleanup.interval());
        Assertions.assertEquals(250, cleanup.maxRowsPerPass());
    }

    @Test
    void testMissingConfigWritesSkeletonAndFailsValidation(@TempDir Path dir) throws Exception {
        var holder = new KansokushaConfig.Holder(dir);

        var error = Assertions.assertThrows(IOException.class, holder::reload);

        Assertions.assertTrue(error.getMessage().contains("retention.policies"));
        Assertions.assertTrue(Files.exists(dir.resolve("config.yml")));
    }

    @Test
    void testInvalidReloadKeepsPreviousValidSnapshot(@TempDir Path dir) throws Exception {
        writeConfig(dir, validConfig("PT1H", "example:fallback"));

        var holder = new KansokushaConfig.Holder(dir);
        holder.reload();
        var previous = holder.get();

        writeConfig(
            dir,
            """
                debug: false
                retention:
                  policies:
                    - key: example:fallback
                      duration: PT1H
                  fallback-policy: example:missing
                """
        );

        Assertions.assertThrows(IOException.class, holder::reload);
        Assertions.assertSame(previous, holder.get());
        Assertions.assertTrue(holder.get().debug());
    }

    @Test
    void testDuplicatePolicyAndMappingFail(@TempDir Path dir) throws Exception {
        var duplicatePolicy = """
            retention:
              policies:
                - key: example:audit
                  duration: PT1H
                - key: example:audit
                  duration: PT2H
              fallback-policy: example:audit
            """;
        assertInvalid(dir.resolve("policy"), duplicatePolicy, "duplicate retention policy key");

        var duplicateMapping = """
            retention:
              policies:
                - key: example:audit
                  duration: PT1H
              event-type-mappings:
                - event-type: example:event
                  policy: example:audit
                - event-type: example:event
                  policy: example:audit
              fallback-policy: example:audit
            """;
        assertInvalid(dir.resolve("mapping"), duplicateMapping, "duplicate event type mapping");
    }

    @Test
    void testUnknownPolicyReferencesFail(@TempDir Path dir) throws Exception {
        var unknownMapping = """
            retention:
              policies:
                - key: example:audit
                  duration: PT1H
              event-type-mappings:
                - event-type: example:event
                  policy: example:missing
              fallback-policy: example:audit
            """;
        assertInvalid(dir.resolve("mapping"), unknownMapping, "references unknown retention policy example:missing");

        var unknownFallback = """
            retention:
              policies:
                - key: example:audit
                  duration: PT1H
              fallback-policy: example:missing
            """;
        assertInvalid(dir.resolve("fallback"), unknownFallback, "fallback policy references unknown");
    }

    @Test
    void testInvalidIngestionSettingsFailWithActionableMessages(@TempDir Path dir) throws Exception {
        assertInvalid(
            dir.resolve("queue-capacity"),
            ingestionConfig("0", "8", "PT0.1S"),
            "ingestion.queue-capacity must be positive"
        );
        assertInvalid(
            dir.resolve("batch-size"),
            ingestionConfig("16", "0", "PT0.1S"),
            "ingestion.max-batch-size must be positive"
        );
        assertInvalid(
            dir.resolve("delay-malformed"),
            ingestionConfig("16", "8", "not-a-duration"),
            "ingestion.max-batch-delay is not a valid ISO-8601 duration"
        );
        assertInvalid(
            dir.resolve("delay-zero"),
            ingestionConfig("16", "8", "PT0S"),
            "ingestion.max-batch-delay must be positive"
        );
        assertInvalid(
            dir.resolve("delay-sub-millisecond"),
            ingestionConfig("16", "8", "PT0.000000001S"),
            "ingestion.max-batch-delay must resolve to whole milliseconds"
        );
        assertInvalid(
            dir.resolve("delay-overflow"),
            ingestionConfig("16", "8", "PT3000000H"),
            "ingestion.max-batch-delay exceeds the supported nanosecond range"
        );
    }

    @Test
    void testInvalidCleanupSettingsFailWithActionableMessages(@TempDir Path dir) throws Exception {
        assertInvalid(
            dir.resolve("cleanup-duration"),
            """
                retention:
                  policies:
                    - key: example:fallback
                      duration: PT1H
                  fallback-policy: example:fallback
                  cleanup-interval: PT0S
                  max-rows-per-pass: 10
                """,
            "retention.cleanup-interval must be positive"
        );
        assertInvalid(
            dir.resolve("cleanup-fractional"),
            """
                retention:
                  policies:
                    - key: example:fallback
                      duration: PT1H
                  fallback-policy: example:fallback
                  cleanup-interval: PT0.000000001S
                  max-rows-per-pass: 10
                """,
            "retention.cleanup-interval must resolve to whole milliseconds"
        );
        assertInvalid(
            dir.resolve("cleanup-bound"),
            """
                retention:
                  policies:
                    - key: example:fallback
                      duration: PT1H
                  fallback-policy: example:fallback
                  cleanup-interval: PT1M
                  max-rows-per-pass: 0
                """,
            "retention.max-rows-per-pass must be positive"
        );
    }

    @Test
    void testMalformedKeysAndDurationsFailWithActionableMessages(@TempDir Path dir) throws Exception {
        assertInvalid(
            dir.resolve("policy-key"),
            """
                retention:
                  policies:
                    - key: Invalid Key
                      duration: PT1H
                  fallback-policy: example:audit
                """,
            "retention.policies[0].key"
        );

        assertInvalid(
            dir.resolve("event-key"),
            """
                retention:
                  policies:
                    - key: example:audit
                      duration: PT1H
                  event-type-mappings:
                    - event-type: Invalid Key
                      policy: example:audit
                  fallback-policy: example:audit
                """,
            "retention.event-type-mappings[0].event-type"
        );

        assertInvalid(
            dir.resolve("implicit-namespace"),
            """
                retention:
                  policies:
                    - key: audit
                      duration: PT1H
                  fallback-policy: audit
                """,
            "must explicitly include a namespace"
        );

        assertInvalid(
            dir.resolve("empty-namespace"),
            """
                retention:
                  policies:
                    - key: :audit
                      duration: PT1H
                  fallback-policy: :audit
                """,
            "must explicitly include a namespace"
        );

        assertInvalid(dir.resolve("malformed"), validConfig("one hour", "example:fallback"), "ISO-8601 duration");
        assertInvalid(dir.resolve("zero"), validConfig("PT0S", "example:fallback"), "must be positive");
        assertInvalid(
            dir.resolve("fractional"),
            validConfig("PT0.000000001S", "example:fallback"),
            "whole milliseconds"
        );
        assertInvalid(
            dir.resolve("overflow"),
            validConfig("PT1000000000000000H", "example:fallback"),
            "millisecond range"
        );
    }

    private static String ingestionConfig(String queueCapacity, String maxBatchSize, String maxBatchDelay) {
        return """
            ingestion:
              queue-capacity: %s
              max-batch-size: %s
              max-batch-delay: %s
            retention:
              policies:
                - key: example:fallback
                  duration: PT1H
              fallback-policy: example:fallback
              cleanup-interval: PT5M
              max-rows-per-pass: 100
            """.formatted(queueCapacity, maxBatchSize, maxBatchDelay);
    }

    private static String validConfig(String duration, String fallback) {
        return """
            debug: true
            ingestion:
              queue-capacity: 16
              max-batch-size: 8
              max-batch-delay: PT0.1S
            retention:
              policies:
                - key: example:fallback
                  duration: %s
              fallback-policy: %s
              cleanup-interval: PT5M
              max-rows-per-pass: 100
            """.formatted(duration, fallback);
    }

    private static void assertInvalid(Path dir, String yaml, String expectedMessage) throws Exception {
        writeConfig(dir, yaml);

        var error = Assertions.assertThrows(
            IOException.class,
            () -> new KansokushaConfig.Holder(dir).reload()
        );
        Assertions.assertTrue(
            error.getMessage().contains(expectedMessage),
            () -> "Expected message containing '" + expectedMessage + "' but was: " + error.getMessage()
        );
    }

    private static void writeConfig(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yml"), content);
    }
}
