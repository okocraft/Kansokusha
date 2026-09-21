package net.okocraft.kansokusha.velocity.plugin;

import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.common.storage.DuckDbDatabase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class KansokushaVelocityPluginTest {

    @Test
    void testShutdownWaitsForInitializationAndClosesPublishedRuntime(@TempDir Path dir)
        throws Exception {
        writeConfig(dir);
        var logger = Mockito.mock(Logger.class);
        var runtime = Mockito.mock(KansokushaRuntime.class);
        var startEntered = new CountDownLatch(1);
        var releaseStart = new CountDownLatch(1);
        var plugin = new KansokushaVelocityPlugin(
            logger,
            dir,
            (dataDirectory, reporter) -> new VelocityRuntimeLifecycle(
                dataDirectory,
                reporter,
                (actualDirectory, actualReporter) -> {
                    startEntered.countDown();
                    try {
                        releaseStart.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Interrupted while starting runtime.", e);
                    }
                    return runtime;
                }
            )
        );

        try (var executor = Executors.newFixedThreadPool(2)) {
            var initialize = executor.submit(() -> {
                plugin.onProxyInitialize(null);
                return null;
            });
            Assertions.assertTrue(startEntered.await(2, TimeUnit.SECONDS));

            var shutdownEntered = new CountDownLatch(1);
            var shutdown = executor.submit(() -> {
                shutdownEntered.countDown();
                plugin.onProxyShutdown(null);
                return null;
            });

            Assertions.assertTrue(shutdownEntered.await(2, TimeUnit.SECONDS));
            Assertions.assertFalse(shutdown.isDone());
            releaseStart.countDown();
            initialize.get(2, TimeUnit.SECONDS);
            shutdown.get(2, TimeUnit.SECONDS);
        }

        Mockito.verify(runtime).close();
    }

    @Test
    void testInitializationDoesNotStartAfterShutdownBegins(@TempDir Path dir) {
        var logger = Mockito.mock(Logger.class);
        var lifecycleCreated = new AtomicBoolean();
        var plugin = new KansokushaVelocityPlugin(
            logger,
            dir,
            (dataDirectory, reporter) -> {
                lifecycleCreated.set(true);
                return Mockito.mock(VelocityRuntimeLifecycle.class);
            }
        );

        plugin.onProxyShutdown(null);
        plugin.onProxyInitialize(null);

        Assertions.assertFalse(lifecycleCreated.get());
    }

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
