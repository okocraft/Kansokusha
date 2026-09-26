package net.okocraft.kansokusha.velocity.plugin;

import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.velocity.builtin.VelocityBackendRegistryChangeListener;
import net.okocraft.kansokusha.velocity.builtin.VelocityChatSubscriber;
import net.okocraft.kansokusha.velocity.builtin.VelocityCommandSubscriber;
import net.okocraft.kansokusha.velocity.builtin.VelocityPlayerSessionListener;
import net.okocraft.kansokusha.velocity.builtin.VelocityServerConnectedListener;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class KansokushaVelocityPluginTest {

    @Test
    void testBuiltInListenerLifecycleAndServerConnectedRegression(@TempDir Path dir)
        throws Exception {
        writeConfig(dir);
        var logger = Mockito.mock(Logger.class);
        var eventManager = Mockito.mock(EventManager.class);
        var proxyServer = proxyServer(eventManager);
        var plugin = new KansokushaVelocityPlugin(logger, proxyServer, dir);

        Mockito.verifyNoInteractions(eventManager);
        Mockito.doAnswer(invocation -> {
            Assertions.assertDoesNotThrow(Kansokusha::api);
            return null;
        }).when(eventManager).register(Mockito.eq(plugin), Mockito.any());

        plugin.onProxyInitialize(null);

        var listenerCaptor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(eventManager, Mockito.times(5))
            .register(Mockito.eq(plugin), listenerCaptor.capture());

        var listeners = listenerCaptor.getAllValues();
        Assertions.assertEquals(
            List.of(
                VelocityServerConnectedListener.class,
                VelocityPlayerSessionListener.class,
                VelocityChatSubscriber.class,
                VelocityCommandSubscriber.class,
                VelocityBackendRegistryChangeListener.class
            ),
            listeners.stream().map(Object::getClass).toList()
        );
        Assertions.assertNotNull(
            VelocityServerConnectedListener.class
                .getMethod("onServerConnected", ServerConnectedEvent.class)
                .getAnnotation(Subscribe.class)
        );

        ((VelocityServerConnectedListener) listeners.getFirst())
            .onServerConnected(serverConnectedEvent("game", "lobby"));

        Mockito.doAnswer(invocation -> {
            Assertions.assertDoesNotThrow(Kansokusha::api);
            return null;
        }).when(eventManager).unregisterListener(Mockito.eq(plugin), Mockito.any());

        plugin.onProxyShutdown(null);

        for (var listener : listeners) {
            Mockito.verify(eventManager).unregisterListener(plugin, listener);
        }
        Assertions.assertThrows(IllegalStateException.class, Kansokusha::api);
        Mockito.verify(logger, Mockito.never())
            .error(Mockito.anyString(), Mockito.any(Throwable.class));

        var databasePath = dir.resolve("kansokusha.duckdb");
        try (
            var connection = DriverManager.getConnection("jdbc:duckdb:" + databasePath);
            var statement = connection.createStatement();
            var rows = statement.executeQuery(
                """
                SELECT et.event_type_key, s.server_key, hex(e.payload)
                FROM events e
                JOIN payload_generations pg ON pg.id = e.payload_generation_id
                JOIN event_types et ON et.id = pg.event_type_id
                JOIN servers s ON s.id = e.server_id
                """
            )
        ) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals("kansokusha:server_connected", rows.getString(1));
            Assertions.assertEquals("kansokusha:velocity-server/game", rows.getString(2));
            Assertions.assertEquals(
                "000000206B616E736F6B757368613A76656C6F636974792D7365727665722F6C6F626279",
                rows.getString(3)
            );
            Assertions.assertFalse(rows.next());
        }
    }

    @Test
    void testRegistrationFailureRollsBackListenersAndClosesRuntime(@TempDir Path dir)
        throws Exception {
        writeConfig(dir);
        var logger = Mockito.mock(Logger.class);
        var eventManager = Mockito.mock(EventManager.class);
        var proxyServer = proxyServer(eventManager);
        var plugin = new KansokushaVelocityPlugin(logger, proxyServer, dir);
        var registrationCount = new AtomicInteger();

        Mockito.doAnswer(invocation -> {
            Assertions.assertDoesNotThrow(Kansokusha::api);
            if (registrationCount.incrementAndGet() == 3) {
                throw new IllegalStateException("synthetic listener registration failure");
            }
            return null;
        }).when(eventManager).register(Mockito.eq(plugin), Mockito.any());
        Mockito.doAnswer(invocation -> {
            Assertions.assertDoesNotThrow(Kansokusha::api);
            return null;
        }).when(eventManager).unregisterListener(Mockito.eq(plugin), Mockito.any());

        var failure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> plugin.onProxyInitialize(null)
        );
        Assertions.assertEquals(
            "synthetic listener registration failure",
            failure.getMessage()
        );

        var listenerCaptor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(eventManager, Mockito.times(3))
            .register(Mockito.eq(plugin), listenerCaptor.capture());
        var attempted = listenerCaptor.getAllValues();

        Mockito.verify(eventManager).unregisterListener(plugin, attempted.get(1));
        Mockito.verify(eventManager).unregisterListener(plugin, attempted.get(0));
        Mockito.verify(eventManager, Mockito.never())
            .unregisterListener(plugin, attempted.get(2));
        Assertions.assertThrows(IllegalStateException.class, Kansokusha::api);

        plugin.onProxyShutdown(null);
        Mockito.verify(logger, Mockito.never())
            .error(Mockito.anyString(), Mockito.any(Throwable.class));
    }

    private static ProxyServer proxyServer(EventManager eventManager) {
        var proxyServer = Mockito.mock(ProxyServer.class);
        Mockito.when(proxyServer.getEventManager()).thenReturn(eventManager);
        return proxyServer;
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
