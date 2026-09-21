package net.okocraft.kansokusha.velocity.plugin;

import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.common.storage.DuckDbDatabase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
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
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(runtime.api()).thenReturn(api);
        Mockito.when(api.registerEventType(Mockito.any()))
            .thenReturn(RegistrationOutcome.REGISTERED);
        var publication = Mockito.mock(VelocityRuntimeLifecycle.ApiPublication.class);
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
                },
                publication
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
        Mockito.verify(publication).publish(Mockito.any());
        Mockito.verify(publication).unpublish(Mockito.any());
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

    @Test
    void testServerConnectedEventsDelegateOnlyWhileRuntimeIsActive(@TempDir Path dir)
        throws Exception {
        writeConfig(dir);
        var logger = Mockito.mock(Logger.class);
        var lifecycle = Mockito.mock(VelocityRuntimeLifecycle.class);
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(lifecycle.api()).thenReturn(api);
        Mockito.when(api.registerEventType(Mockito.any()))
            .thenReturn(RegistrationOutcome.REGISTERED);
        Mockito.when(api.submit(Mockito.any())).thenReturn(SubmissionOutcome.ACCEPTED);
        var plugin = new KansokushaVelocityPlugin(
            logger,
            dir,
            (dataDirectory, reporter) -> lifecycle
        );

        plugin.onProxyInitialize(null);
        plugin.onServerConnected(serverConnectedEvent("game", "lobby"));

        Mockito.verify(lifecycle).start();
        Mockito.verify(api).registerEventType(Mockito.any());
        Mockito.verify(api).submit(Mockito.any());

        plugin.onProxyShutdown(null);
        plugin.onServerConnected(serverConnectedEvent("ignored", null));

        Mockito.verify(lifecycle).close();
        Mockito.verify(api, Mockito.times(1)).submit(Mockito.any());
    }

    @Test
    void testBuiltInRegistrationConflictClosesStartedLifecycle(@TempDir Path dir)
        throws Exception {
        writeConfig(dir);
        var logger = Mockito.mock(Logger.class);
        var lifecycle = Mockito.mock(VelocityRuntimeLifecycle.class);
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(lifecycle.api()).thenReturn(api);
        Mockito.when(api.registerEventType(Mockito.any()))
            .thenReturn(RegistrationOutcome.CONFLICT);
        var plugin = new KansokushaVelocityPlugin(
            logger,
            dir,
            (dataDirectory, reporter) -> lifecycle
        );

        Assertions.assertThrows(
            IllegalStateException.class,
            () -> plugin.onProxyInitialize(null)
        );

        Mockito.verify(lifecycle).start();
        Mockito.verify(lifecycle).close();
        Mockito.verify(api, Mockito.never()).submit(Mockito.any());
    }

    private static ServerConnectedEvent serverConnectedEvent(
        String targetName,
        String previousName
    ) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        );
        var target = server(targetName);
        var previous = previousName == null ? null : server(previousName);
        return new ServerConnectedEvent(player, target, previous);
    }

    private static RegisteredServer server(String name) {
        var server = Mockito.mock(RegisteredServer.class);
        Mockito.when(server.getServerInfo()).thenReturn(
            new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565))
        );
        return server;
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
