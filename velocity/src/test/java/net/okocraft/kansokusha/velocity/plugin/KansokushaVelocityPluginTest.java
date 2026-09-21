package net.okocraft.kansokusha.velocity.plugin;

import net.okocraft.kansokusha.common.storage.DuckDbDatabase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

class KansokushaVelocityPluginTest {

    @Test
    void testProxyLifecycleStartsAndClosesRuntime(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        var logger = Mockito.mock(Logger.class);
        var plugin = new KansokushaVelocityPlugin(logger, dir);

        plugin.onProxyInitialize(null);

        var databasePath = dir.resolve("kansokusha.duckdb");
        Assertions.assertTrue(Files.isRegularFile(databasePath));
        Mockito.verify(logger, Mockito.never()).error(Mockito.anyString(), Mockito.any(Throwable.class));

        plugin.onProxyShutdown(null);

        try (var reopened = DuckDbDatabase.open(databasePath)) {
            Assertions.assertNotNull(reopened);
        }
    }

    private static void writeConfig(Path dir) throws Exception {
        Files.writeString(
            dir.resolve("config.yml"),
            """
                ingestion:
                  queue-capacity: 4
                  max-batch-size: 4
                  max-batch-delay: PT1H
                retention:
                  policies:
                    - key: example:default
                      duration: P1D
                  fallback-policy: example:default
                  cleanup-interval: PT1H
                  max-rows-per-pass: 100
                """
        );
    }
}
