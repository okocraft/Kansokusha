package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent;
import com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class VelocityBackendRegistryChangeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-26T00:00:00Z");

    @Test
    void testRegisterAndUnregisterRecordCanonicalKeyAndEventTimeServerInfo() throws Exception {
        var api = api();
        var listener = VelocityBackendRegistryChangeListener.register(
            api,
            Mockito.mock(Logger.class),
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var server = server(
            "Survival-1",
            InetSocketAddress.createUnresolved("backend.internal", 25566)
        );

        listener.onServerRegistered(new ServerRegisteredEvent(server));
        listener.onServerUnregistered(new ServerUnregisteredEvent(server));

        var captor = ArgumentCaptor.forClass(EventSubmission.class);
        Mockito.verify(api, Mockito.times(2)).submit(captor.capture());
        var submissions = captor.getAllValues();

        assertSubmission(
            submissions.get(0),
            VelocityBackendRegistryChangePayloadCodec.Action.REGISTER,
            "Survival-1",
            "backend.internal",
            25566
        );
        assertSubmission(
            submissions.get(1),
            VelocityBackendRegistryChangePayloadCodec.Action.UNREGISTER,
            "Survival-1",
            "backend.internal",
            25566
        );
    }

    @Test
    void testInvalidServerNamesAreSkippedAndWarnedOnceAcrossActions() {
        var api = api();
        var logger = Mockito.mock(Logger.class);
        var listener = VelocityBackendRegistryChangeListener.register(api, logger);
        var server = server(
            "東京",
            InetSocketAddress.createUnresolved("backend.internal", 25565)
        );

        listener.onServerRegistered(new ServerRegisteredEvent(server));
        listener.onServerRegistered(new ServerRegisteredEvent(server));
        listener.onServerUnregistered(new ServerUnregisteredEvent(server));

        Mockito.verify(api, Mockito.never()).submit(Mockito.any());
        Mockito.verify(logger, Mockito.times(1))
            .warn(Mockito.anyString(), Mockito.eq("東京"));
    }

    @Test
    void testRejectedAdmissionDoesNotBlockOrThrow() {
        var api = api();
        Mockito.when(api.submit(Mockito.any()))
            .thenReturn(false);
        var listener = VelocityBackendRegistryChangeListener.register(
            api,
            Mockito.mock(Logger.class)
        );

        Assertions.assertDoesNotThrow(
            () -> listener.onServerRegistered(
                new ServerRegisteredEvent(
                    server(
                        "game",
                        InetSocketAddress.createUnresolved("backend.internal", 25565)
                    )
                )
            )
        );
        Mockito.verify(api).submit(Mockito.any());
    }

    @Test
    void testRegistryHandlersAreVelocitySubscriberMethods() throws Exception {
        var registered = VelocityBackendRegistryChangeListener.class
            .getMethod("onServerRegistered", ServerRegisteredEvent.class)
            .getAnnotation(Subscribe.class);
        var unregistered = VelocityBackendRegistryChangeListener.class
            .getMethod("onServerUnregistered", ServerUnregisteredEvent.class)
            .getAnnotation(Subscribe.class);

        Assertions.assertNotNull(registered);
        Assertions.assertFalse(registered.async());
        Assertions.assertNotNull(unregistered);
        Assertions.assertFalse(unregistered.async());
    }

    private static void assertSubmission(
        EventSubmission submission,
        VelocityBackendRegistryChangePayloadCodec.Action action,
        String rawName,
        String host,
        int port
    ) throws Exception {
        var expectedKey = VelocityServerKeyCodec.encode(rawName).orElseThrow();
        Assertions.assertEquals(VelocityBackendRegistryChangeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(expectedKey, submission.serverKey());
        Assertions.assertNull(submission.worldKey());
        Assertions.assertNull(submission.position());
        Assertions.assertNull(submission.subject());

        var payload = VelocityBackendRegistryChangePayloadCodec.decode(submission.payload());
        Assertions.assertEquals(action, payload.action());
        Assertions.assertEquals(expectedKey, payload.serverKey());
        Assertions.assertEquals(rawName, payload.serverInfo().getName());
        Assertions.assertEquals(host, payload.serverInfo().getAddress().getHostString());
        Assertions.assertEquals(port, payload.serverInfo().getAddress().getPort());
        Assertions.assertTrue(payload.serverInfo().getAddress().isUnresolved());
    }

    private static KansokushaApi api() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.submit(Mockito.any())).thenReturn(true);
        return api;
    }

    private static RegisteredServer server(String name, InetSocketAddress address) {
        var server = Mockito.mock(RegisteredServer.class);
        Mockito.when(server.getServerInfo()).thenReturn(new ServerInfo(name, address));
        return server;
    }
}
