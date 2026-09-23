package net.okocraft.kansokusha.velocity.plugin;

import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.okocraft.kansokusha.api.Kansokusha;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

class KansokushaVelocityPluginTest {

    @Test
    void testProxyLifecycleRecordsServerConnectionsUntilShutdown(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        var logger = Mockito.mock(Logger.class);
        var plugin = new KansokushaVelocityPlugin(logger, dir);

        plugin.onProxyInitialize(null);

        Assertions.assertTrue(Kansokusha.api().localServerKey().isEmpty());
        plugin.onServerConnected(serverConnectedEvent("game", "lobby"));

        plugin.onProxyShutdown(null);

        Assertions.assertThrows(IllegalStateException.class, Kansokusha::api);
        plugin.onServerConnected(serverConnectedEvent("ignored", null));
        Mockito.verify(logger, Mockito.never()).error(Mockito.anyString(), Mockito.any(Throwable.class));

        var databasePath = dir.resolve("kansokusha.duckdb");
        try (
            var connection = DriverManager.getConnection("jdbc:duckdb:" + databasePath);
            var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT count(*) FROM events")
        ) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals(1, rows.getInt(1));
        }
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
