package net.okocraft.kansokusha.velocity.plugin;

import com.velocitypowered.api.event.EventManager;
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
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

class KansokushaVelocityPluginTest {

    @Test
    void testBuiltInListenerLifecycleAndServerConnectedRegression(@TempDir Path dir)
        throws Exception {
        var logger = Mockito.mock(Logger.class);
        var eventManager = Mockito.mock(EventManager.class);
        var proxyServer = proxyServer(eventManager);
        var plugin = new KansokushaVelocityPlugin(logger, proxyServer, dir);

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
        Assertions.assertTrue(Kansokusha.api().localServerKey().isEmpty());

        ((VelocityServerConnectedListener) listeners.getFirst())
            .onServerConnected(serverConnectedEvent("game", "lobby"));

        plugin.onProxyShutdown(null);

        Assertions.assertThrows(IllegalStateException.class, Kansokusha::api);
        Mockito.verify(logger, Mockito.never())
            .error(Mockito.anyString(), Mockito.any(Throwable.class));

        var databasePath = dir.resolve("kansokusha.duckdb");
        try (
            var connection = DriverManager.getConnection("jdbc:duckdb:" + databasePath);
            var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT event_type, server, hex(payload) FROM events")
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
}
