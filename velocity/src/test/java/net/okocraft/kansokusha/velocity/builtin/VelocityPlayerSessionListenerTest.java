package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

class VelocityPlayerSessionListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-26T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testPostLoginRecordsSuccessfulProxySessionWithoutBackendSemantics()
        throws Exception {
        var api = api();
        var listener = listener(api);
        var player = player();

        Mockito.when(player.getRemoteAddress()).thenReturn(
            new InetSocketAddress("203.0.113.10", 54321)
        );
        Mockito.when(player.getVirtualHost()).thenReturn(
            Optional.of(InetSocketAddress.createUnresolved("play.example.test", 25565))
        );
        Mockito.when(player.getRawVirtualHost()).thenReturn(
            Optional.of("Play.Example.Test.")
        );

        listener.onPostLogin(new PostLoginEvent(player));

        var submission = submission(api);
        Assertions.assertEquals(
            VelocityPlayerSessionListener.POST_LOGIN_EVENT_TYPE,
            submission.eventType()
        );
        assertProxyCommonFields(submission);

        var payload = VelocityPlayerSessionPayloadCodec.decodePostLogin(
            submission.payload()
        );
        Assertions.assertEquals("TestPlayer", payload.username());
        Assertions.assertEquals(
            new VelocityPlayerSessionPayloadCodec.Address("203.0.113.10", 54321),
            payload.remoteAddress()
        );
        Assertions.assertEquals(
            new VelocityPlayerSessionPayloadCodec.Address(
                "play.example.test",
                25565
            ),
            payload.virtualHost()
        );
        Assertions.assertEquals("Play.Example.Test.", payload.rawVirtualHost());
        Mockito.verify(player, Mockito.never()).getCurrentServer();
    }

    @Test
    void testDisconnectPreservesEveryLoginStatus() throws Exception {
        for (var status : DisconnectEvent.LoginStatus.values()) {
            var api = api();
            var listener = listener(api);
            var player = player();
            Mockito.when(player.getCurrentServer()).thenReturn(Optional.empty());

            listener.onDisconnect(new DisconnectEvent(player, status));

            var submission = submission(api);
            Assertions.assertEquals(
                VelocityPlayerSessionListener.DISCONNECT_EVENT_TYPE,
                submission.eventType()
            );
            assertProxyCommonFields(submission);

            var payload = VelocityPlayerSessionPayloadCodec.decodeDisconnect(
                submission.payload()
            );
            Assertions.assertEquals("TestPlayer", payload.username());
            Assertions.assertEquals(status, payload.loginStatus());
            Assertions.assertNull(payload.currentBackendKey());
        }
    }

    @Test
    void testDisconnectStoresCanonicalCurrentBackendKey() throws Exception {
        var api = api();
        var listener = listener(api);
        var player = player();
        Mockito.when(player.getCurrentServer()).thenReturn(
            Optional.of(connection("Survival-1"))
        );

        listener.onDisconnect(
            new DisconnectEvent(
                player,
                DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
            )
        );

        var submission = submission(api);
        var payload = VelocityPlayerSessionPayloadCodec.decodeDisconnect(
            submission.payload()
        );
        Assertions.assertEquals(
            VelocityServerKeyCodec.encode("Survival-1").orElseThrow(),
            payload.currentBackendKey()
        );
        Assertions.assertEquals(
            VelocityPlayerSessionListener.PROXY_SERVER_KEY,
            submission.serverKey()
        );
    }

    @Test
    void testInvalidCurrentBackendStillRecordsDisconnectAndWarnsOnce()
        throws Exception {
        var api = api();
        var logger = Mockito.mock(Logger.class);
        var listener = VelocityPlayerSessionListener.register(
            api,
            logger,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var player = player();
        Mockito.when(player.getCurrentServer()).thenReturn(
            Optional.of(connection("東京"))
        );
        var event = new DisconnectEvent(
            player,
            DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
        );

        listener.onDisconnect(event);
        listener.onDisconnect(event);

        var captor = ArgumentCaptor.forClass(EventSubmission.class);
        Mockito.verify(api, Mockito.times(2)).submit(captor.capture());
        for (var submission : captor.getAllValues()) {
            var payload = VelocityPlayerSessionPayloadCodec.decodeDisconnect(
                submission.payload()
            );
            Assertions.assertNull(payload.currentBackendKey());
        }
        Mockito.verify(logger, Mockito.times(1))
            .warn(Mockito.anyString(), Mockito.eq("東京"));
    }

    @Test
    void testBackendKickRecordsDisconnectRedirectAndNotifyFinalResults()
        throws Exception {
        var api = api();
        var listener = listener(api);
        var player = player();
        var source = server("Lobby");
        var redirectTarget = server("Game");

        var disconnect = new KickedFromServerEvent(
            player,
            source,
            Component.text("backend disconnect"),
            false,
            KickedFromServerEvent.DisconnectPlayer.create(
                Component.text("proxy disconnect")
            )
        );
        var redirect = new KickedFromServerEvent(
            player,
            source,
            Component.text("backend redirect"),
            true,
            KickedFromServerEvent.Notify.create(Component.text("initial"))
        );
        redirect.setResult(
            KickedFromServerEvent.RedirectPlayer.create(
                redirectTarget,
                Component.text("redirect message")
            )
        );
        var notify = new KickedFromServerEvent(
            player,
            source,
            null,
            false,
            KickedFromServerEvent.Notify.create(Component.text("notify message"))
        );

        listener.onKickedFromServer(disconnect);
        listener.onKickedFromServer(redirect);
        listener.onKickedFromServer(notify);

        var captor = ArgumentCaptor.forClass(EventSubmission.class);
        Mockito.verify(api, Mockito.times(3)).submit(captor.capture());
        var submissions = captor.getAllValues();

        var disconnectSubmission = submissions.get(0);
        assertKickCommonFields(disconnectSubmission, "Lobby");
        var disconnectPayload = VelocityPlayerSessionPayloadCodec.decodeBackendKick(
            disconnectSubmission.payload()
        );
        Assertions.assertEquals(
            Component.text("backend disconnect"),
            disconnectPayload.originalReason()
        );
        Assertions.assertFalse(disconnectPayload.duringServerConnect());
        Assertions.assertEquals(
            VelocityPlayerSessionPayloadCodec.ProxyAction.DISCONNECT,
            disconnectPayload.action()
        );
        Assertions.assertNull(disconnectPayload.redirectTarget());
        Assertions.assertEquals(
            Component.text("proxy disconnect"),
            disconnectPayload.message()
        );

        var redirectSubmission = submissions.get(1);
        assertKickCommonFields(redirectSubmission, "Lobby");
        var redirectPayload = VelocityPlayerSessionPayloadCodec.decodeBackendKick(
            redirectSubmission.payload()
        );
        Assertions.assertEquals(
            Component.text("backend redirect"),
            redirectPayload.originalReason()
        );
        Assertions.assertTrue(redirectPayload.duringServerConnect());
        Assertions.assertEquals(
            VelocityPlayerSessionPayloadCodec.ProxyAction.REDIRECT,
            redirectPayload.action()
        );
        Assertions.assertEquals(
            VelocityServerKeyCodec.encode("Game").orElseThrow(),
            redirectPayload.redirectTarget()
        );
        Assertions.assertEquals(
            Component.text("redirect message"),
            redirectPayload.message()
        );

        var notifySubmission = submissions.get(2);
        assertKickCommonFields(notifySubmission, "Lobby");
        var notifyPayload = VelocityPlayerSessionPayloadCodec.decodeBackendKick(
            notifySubmission.payload()
        );
        Assertions.assertNull(notifyPayload.originalReason());
        Assertions.assertFalse(notifyPayload.duringServerConnect());
        Assertions.assertEquals(
            VelocityPlayerSessionPayloadCodec.ProxyAction.NOTIFY,
            notifyPayload.action()
        );
        Assertions.assertNull(notifyPayload.redirectTarget());
        Assertions.assertEquals(
            Component.text("notify message"),
            notifyPayload.message()
        );
    }

    @Test
    void testInvalidRedirectTargetDoesNotCreatePartialKickRecord() {
        var api = api();
        var logger = Mockito.mock(Logger.class);
        var listener = VelocityPlayerSessionListener.register(
            api,
            logger,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var event = new KickedFromServerEvent(
            player(),
            server("lobby"),
            Component.text("backend"),
            true,
            KickedFromServerEvent.RedirectPlayer.create(
                server("東京"),
                Component.text("redirect")
            )
        );

        listener.onKickedFromServer(event);

        Mockito.verify(api, Mockito.never()).submit(Mockito.any());
        Mockito.verify(logger).warn(Mockito.anyString(), Mockito.eq("東京"));
    }

    @Test
    void testAwaitingAndShutdownPathsOnlyAttemptBoundedSubmission() {
        var api = api();
        Mockito.when(api.submit(Mockito.any()))
            .thenReturn(SubmissionOutcome.INGESTION_UNAVAILABLE);
        var listener = listener(api);
        var player = player();
        Mockito.when(player.getRemoteAddress()).thenReturn(
            new InetSocketAddress("203.0.113.10", 54321)
        );
        Mockito.when(player.getVirtualHost()).thenReturn(Optional.empty());
        Mockito.when(player.getRawVirtualHost()).thenReturn(Optional.empty());
        Mockito.when(player.getCurrentServer()).thenReturn(Optional.empty());

        Assertions.assertAll(
            () -> Assertions.assertDoesNotThrow(
                () -> listener.onPostLogin(new PostLoginEvent(player))
            ),
            () -> Assertions.assertDoesNotThrow(
                () -> listener.onDisconnect(
                    new DisconnectEvent(
                        player,
                        DisconnectEvent.LoginStatus.CANCELLED_BY_PROXY
                    )
                )
            ),
            () -> Assertions.assertDoesNotThrow(
                () -> listener.onKickedFromServer(
                    new KickedFromServerEvent(
                        player,
                        server("lobby"),
                        Component.text("backend"),
                        false,
                        KickedFromServerEvent.Notify.create(
                            Component.text("notify")
                        )
                    )
                )
            )
        );
        Mockito.verify(api, Mockito.times(3)).submit(Mockito.any());
    }

    @Test
    void testClassIsAnIndependentSubscriberAndKickHandlerRunsLast()
        throws Exception {
        var postLogin = VelocityPlayerSessionListener.class
            .getMethod("onPostLogin", PostLoginEvent.class)
            .getAnnotation(Subscribe.class);
        var disconnect = VelocityPlayerSessionListener.class
            .getMethod("onDisconnect", DisconnectEvent.class)
            .getAnnotation(Subscribe.class);
        var kick = VelocityPlayerSessionListener.class
            .getMethod("onKickedFromServer", KickedFromServerEvent.class)
            .getAnnotation(Subscribe.class);

        Assertions.assertNotNull(postLogin);
        Assertions.assertNotNull(disconnect);
        Assertions.assertNotNull(kick);
        Assertions.assertEquals(Short.MIN_VALUE, kick.priority());
    }

    @Test
    void testRegistrationConflictFails() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any()))
            .thenReturn(
                RegistrationOutcome.REGISTERED,
                RegistrationOutcome.CONFLICT
            );

        var failure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> VelocityPlayerSessionListener.register(
                api,
                Mockito.mock(Logger.class)
            )
        );

        Assertions.assertTrue(
            failure.getMessage().contains("kansokusha:velocity_disconnect")
        );
    }

    private static VelocityPlayerSessionListener listener(KansokushaApi api) {
        return VelocityPlayerSessionListener.register(
            api,
            Mockito.mock(Logger.class),
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static KansokushaApi api() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any()))
            .thenReturn(RegistrationOutcome.REGISTERED);
        Mockito.when(api.submit(Mockito.any())).thenReturn(SubmissionOutcome.ACCEPTED);
        return api;
    }

    private static EventSubmission submission(KansokushaApi api) {
        var captor = ArgumentCaptor.forClass(EventSubmission.class);
        Mockito.verify(api).submit(captor.capture());
        return captor.getValue();
    }

    private static Player player() {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getUsername()).thenReturn("TestPlayer");
        return player;
    }

    private static ServerConnection connection(String serverName) {
        var connection = Mockito.mock(ServerConnection.class);
        Mockito.when(connection.getServer()).thenReturn(server(serverName));
        return connection;
    }

    private static RegisteredServer server(String name) {
        var server = Mockito.mock(RegisteredServer.class);
        Mockito.when(server.getServerInfo()).thenReturn(
            new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565))
        );
        return server;
    }

    private static void assertProxyCommonFields(EventSubmission submission) {
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(
            VelocityPlayerSessionListener.PROXY_SERVER_KEY,
            submission.serverKey()
        );
        Assertions.assertNull(submission.worldKey());
        Assertions.assertNull(submission.position());
        Assertions.assertEquals(
            new PlayerSubject(PLAYER_ID),
            submission.subject()
        );
    }

    private static void assertKickCommonFields(
        EventSubmission submission,
        String sourceServerName
    ) {
        Assertions.assertEquals(
            VelocityPlayerSessionListener.BACKEND_KICK_EVENT_TYPE,
            submission.eventType()
        );
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(
            VelocityServerKeyCodec.encode(sourceServerName).orElseThrow(),
            submission.serverKey()
        );
        Assertions.assertNull(submission.worldKey());
        Assertions.assertNull(submission.position());
        Assertions.assertEquals(
            new PlayerSubject(PLAYER_ID),
            submission.subject()
        );
    }
}
