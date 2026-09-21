package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class VelocityServerConnectedListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-22T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testInitialConnectionUsesTargetCommonServerAndNullPreviousPayload()
        throws Exception {
        var api = api();
        var listener = VelocityServerConnectedListener.register(
            api,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );

        listener.onServerConnected(event("lobby", null));

        var captor = ArgumentCaptor.forClass(EventSubmission.class);
        Mockito.verify(api).submit(captor.capture());
        var submission = captor.getValue();

        Assertions.assertEquals(VelocityServerConnectedListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(VelocityServerKeyCodec.encode("lobby"), submission.serverKey());
        Assertions.assertNull(submission.worldKey());
        Assertions.assertNull(submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        Assertions.assertTrue(
            VelocityServerConnectedPayloadCodec.decode(submission.payload()).isEmpty()
        );
    }

    @Test
    void testBackendSwitchStoresOnlyPreviousCanonicalServerKey() throws Exception {
        var api = api();
        var listener = VelocityServerConnectedListener.register(
            api,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );

        listener.onServerConnected(event("game", "lobby"));

        var captor = ArgumentCaptor.forClass(EventSubmission.class);
        Mockito.verify(api).submit(captor.capture());
        var submission = captor.getValue();

        Assertions.assertEquals(VelocityServerKeyCodec.encode("game"), submission.serverKey());
        Assertions.assertEquals(
            VelocityServerKeyCodec.encode("lobby"),
            VelocityServerConnectedPayloadCodec.decode(submission.payload()).orElseThrow()
        );
        Assertions.assertFalse(
            new String(submission.payload().copyBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .contains(VelocityServerKeyCodec.encode("game").asString())
        );
    }

    @Test
    void testRejectedAdmissionDoesNotBlockOrThrow() {
        var api = api();
        Mockito.when(api.submit(Mockito.any()))
            .thenReturn(SubmissionOutcome.INGESTION_UNAVAILABLE);
        var listener = VelocityServerConnectedListener.register(api);

        Assertions.assertDoesNotThrow(
            () -> listener.onServerConnected(event("game", "lobby"))
        );
        Mockito.verify(api).submit(Mockito.any());
    }

    @Test
    void testRegistrationConflictFails() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any()))
            .thenReturn(RegistrationOutcome.CONFLICT);

        var failure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> VelocityServerConnectedListener.register(api)
        );

        Assertions.assertTrue(failure.getMessage().contains("kansokusha:server_connected"));
    }

    private static KansokushaApi api() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any()))
            .thenReturn(RegistrationOutcome.REGISTERED);
        Mockito.when(api.submit(Mockito.any())).thenReturn(SubmissionOutcome.ACCEPTED);
        return api;
    }

    private static ServerConnectedEvent event(String targetName, String previousName) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);

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
