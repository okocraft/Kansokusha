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
    void testBundledDefaultIsWrittenAndLoaded(@TempDir Path dir) throws IOException {
        var config = KansokushaConfig.load(dir.resolve("plugins/Kansokusha"));

        Assertions.assertTrue(Files.isRegularFile(dir.resolve("plugins/Kansokusha/config.yml")));
        Assertions.assertEquals(Optional.empty(), config.serverKey());
        Assertions.assertEquals(10_000, config.queueCapacity());
        Assertions.assertEquals(1_000, config.batchSize());
        Assertions.assertEquals(Duration.ofSeconds(1), config.flushInterval());
        Assertions.assertEquals(Duration.ofHours(1), config.cleanupInterval());

        var retention = config.retention();
        Assertions.assertEquals(Duration.ofDays(180), retention.durationOf(Key.key("kansokusha", "block_break")));
        Assertions.assertEquals(Duration.ofDays(7), retention.durationOf(Key.key("kansokusha", "fluid_change")));
        Assertions.assertEquals(Duration.ofDays(30), retention.durationOf(Key.key("kansokusha", "paper_chat")));
        Assertions.assertEquals(Duration.ofDays(30), retention.durationOf(Key.key("example", "unknown")));
    }

    @Test
    void testExistingFileIsLoaded(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("config.yml"), """
            server-key: example:lobby
            queue-capacity: 4
            batch-size: 2
            flush-interval: PT0.5S
            cleanup-interval: PT5M
            retention:
              default: P1D
              policies:
                long:
                  duration: P365D
                  event-types:
                    - example:important
            """);

        var config = KansokushaConfig.load(dir);

        Assertions.assertEquals(Optional.of(Key.key("example", "lobby")), config.serverKey());
        Assertions.assertEquals(4, config.queueCapacity());
        Assertions.assertEquals(2, config.batchSize());
        Assertions.assertEquals(Duration.ofMillis(500), config.flushInterval());
        Assertions.assertEquals(Duration.ofMinutes(5), config.cleanupInterval());
        Assertions.assertEquals(Duration.ofDays(365), config.retention().durationOf(Key.key("example", "important")));
        Assertions.assertEquals(Duration.ofDays(1), config.retention().durationOf(Key.key("example", "other")));
    }

    @Test
    void testInvalidValuesAreRejected(@TempDir Path dir) throws IOException {
        assertInvalid(dir, "queue-capacity", config("''", "0", "PT1S", ""));
        assertInvalid(dir, "batch-size", config("''", "1", "PT1S", "").replace("batch-size: 1", "batch-size: 0"));
        assertInvalid(dir, "flush-interval", config("''", "1", "1s", ""));
        assertInvalid(dir, "at least 1 millisecond", config("''", "1", "PT0S", ""));
        assertInvalid(dir, "server-key", config("lobby", "1", "PT1S", ""));
        assertInvalid(dir, "more than one retention policy", config("''", "1", "PT1S", """
              policies:
                a:
                  duration: P1D
                  event-types: [example:twice]
                b:
                  duration: P2D
                  event-types: [example:twice]
            """));
    }

    private static String config(String serverKey, String queueCapacity, String flushInterval, String policies) {
        return """
            server-key: %s
            queue-capacity: %s
            batch-size: 1
            flush-interval: %s
            cleanup-interval: PT1H
            retention:
              default: P1D
            """.formatted(serverKey, queueCapacity, flushInterval) + policies;
    }

    private static void assertInvalid(Path dir, String expectedMessage, String yaml) throws IOException {
        Files.writeString(dir.resolve("config.yml"), yaml);

        var error = Assertions.assertThrows(IOException.class, () -> KansokushaConfig.load(dir));
        Assertions.assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }
}
