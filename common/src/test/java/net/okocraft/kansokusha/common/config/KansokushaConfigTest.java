package net.okocraft.kansokusha.common.config;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

class KansokushaConfigTest {

    @Test
    void testValidRetentionConfigurationLoadsThroughHolder(@TempDir Path dir) throws Exception {
        writeConfig(
            dir,
            """
                debug: true
                server-key: example:paper
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
        Assertions.assertEquals(Optional.of(Key.key("example", "paper")), config.localServerKey());

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
        var generatedConfig = dir.resolve("config.yml");
        Assertions.assertTrue(Files.exists(generatedConfig));

        var skeleton = Files.readString(generatedConfig);
        Assertions.assertFalse(skeleton.contains("kansokusha:audit"));
        Assertions.assertFalse(skeleton.contains("kansokusha:session"));
        Assertions.assertFalse(skeleton.contains("kansokusha:default"));
        Assertions.assertFalse(skeleton.contains("kansokusha:short"));
    }

    @Test
    void testBuiltInRetentionExampleLoads(@TempDir Path dir) throws Exception {
        var example = Files.readString(findRepositoryFile("docs/examples/v1-built-in-retention.yml"));
        writeConfig(
            dir,
            """
                ingestion:
                  queue-capacity: 16
                  max-batch-size: 8
                  max-batch-delay: PT0.1S
                """ + example
        );

        var holder = new KansokushaConfig.Holder(dir);
        holder.reload();

        var retention = holder.get().retentionSettings();
        Assertions.assertEquals(
            Duration.ofDays(180),
            retention.policies().get(Key.key("kansokusha", "audit"))
        );
        Assertions.assertEquals(
            Duration.ofDays(30),
            retention.policies().get(Key.key("kansokusha", "session"))
        );
        Assertions.assertEquals(
            Duration.ofDays(30),
            retention.policies().get(Key.key("kansokusha", "default"))
        );
        Assertions.assertEquals(
            Duration.ofDays(7),
            retention.policies().get(Key.key("kansokusha", "short"))
        );

        assertBuiltInMappings(
            retention,
            "audit",
            "block_break",
            "block_place",
            "sign_change",
            "bucket_empty",
            "bucket_fill",
            "block_harvest",
            "flower_pot_change",
            "block_ignite",
            "tnt_prime",
            "explosion_block_change",
            "piston_move",
            "entity_block_change",
            "sponge_absorb",
            "block_fertilize",
            "cauldron_level_change",
            "item_drop",
            "item_pickup",
            "book_edit",
            "lectern_change",
            "player_trade",
            "player_gamemode_change",
            "player_spawn_change",
            "player_death",
            "paper_player_command",
            "paper_server_command",
            "velocity_command",
            "entity_place",
            "armor_stand_manipulate",
            "entity_leash_change",
            "item_frame_change",
            "entity_tame",
            "entity_name_change",
            "entity_break",
            "gamerule_change",
            "world_difficulty_change",
            "world_border_change",
            "world_spawn_change",
            "whitelist_change",
            "backend_registry_change"
        );
        assertBuiltInMappings(
            retention,
            "short",
            "block_burn",
            "natural_block_change",
            "fluid_change",
            "container_transfer",
            "container_pickup",
            "container_process"
        );
        assertBuiltInMappings(
            retention,
            "session",
            "server_connected",
            "paper_join",
            "paper_quit",
            "paper_kick",
            "player_world_change",
            "player_teleport",
            "velocity_post_login",
            "velocity_disconnect",
            "backend_kick"
        );
        assertBuiltInMappings(
            retention,
            "default",
            "paper_chat",
            "velocity_chat"
        );
        Assertions.assertEquals(56, retention.eventTypeMappings().size());
        Assertions.assertEquals(
            Key.key("kansokusha", "short"),
            retention.qualifiedEventTypeMappings().get(
                new KansokushaConfig.QualifiedEventType(
                    Key.key("kansokusha", "cauldron_level_change"),
                    Key.key("kansokusha", "natural")
                )
            )
        );
        Assertions.assertEquals(1, retention.qualifiedEventTypeMappings().size());
        Assertions.assertEquals(
            Key.key("kansokusha", "default"),
            retention.fallbackPolicy()
        );
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

        var duplicateQualifiedMapping = """
            retention:
              policies:
                - key: example:audit
                  duration: PT1H
              event-type-mappings:
                - event-type: example:event
                  qualifier: example:natural
                  policy: example:audit
                - event-type: example:event
                  qualifier: example:natural
                  policy: example:audit
              fallback-policy: example:audit
            """;
        assertInvalid(
            dir.resolve("qualified-mapping"),
            duplicateQualifiedMapping,
            "duplicate qualified event type mapping"
        );
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
    void testOptionalServerIdentityValidation(@TempDir Path dir) throws Exception {
        writeConfig(dir.resolve("missing"), validConfig("PT1H", "example:fallback"));
        var missing = new KansokushaConfig.Holder(dir.resolve("missing"));
        missing.reload();
        Assertions.assertEquals(Optional.empty(), missing.get().localServerKey());

        assertInvalid(
            dir.resolve("malformed"),
            validConfig("PT1H", "example:fallback").replace(
                "debug: true",
                "debug: true\nserver-key: Invalid Key"
            ),
            "server-key"
        );
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
            dir.resolve("qualifier-key"),
            """
                retention:
                  policies:
                    - key: example:audit
                      duration: PT1H
                  event-type-mappings:
                    - event-type: example:event
                      qualifier: Invalid Key
                      policy: example:audit
                  fallback-policy: example:audit
                """,
            "retention.event-type-mappings[0].qualifier"
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

    private static void assertBuiltInMappings(
        KansokushaConfig.RetentionSettings retention,
        String policy,
        String... eventTypes
    ) {
        var expectedPolicy = Key.key("kansokusha", policy);
        for (var eventType : eventTypes) {
            Assertions.assertEquals(
                expectedPolicy,
                retention.eventTypeMappings().get(Key.key("kansokusha", eventType)),
                () -> "Unexpected retention mapping for kansokusha:" + eventType
            );
        }
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

    private static Path findRepositoryFile(String relativePath) {
        for (
            Path directory = Path.of("").toAbsolutePath();
            directory != null;
            directory = directory.getParent()
        ) {
            var candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate repository file: " + relativePath);
    }

    private static void writeConfig(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yml"), content);
    }
}
