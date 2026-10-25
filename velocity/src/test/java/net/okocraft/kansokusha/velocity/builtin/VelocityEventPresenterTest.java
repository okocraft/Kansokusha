package net.okocraft.kansokusha.velocity.builtin;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.command.EventRenderer;
import net.okocraft.kansokusha.common.language.LanguageProvider;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.StoredPayload;
import net.okocraft.kansokusha.velocity.command.KansokushaCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

class VelocityEventPresenterTest {

    private final EventRenderer renderer = VelocityEventPresenter.renderer(Clock.systemUTC(), ZoneOffset.UTC);

    @AfterEach
    void cleanup() {
        LanguageProvider.unload();
    }

    @Test
    void testConnectionAndOriginalCommand(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var event = event("server_connected", VelocityServerConnectedPayloadCodec.encode(Key.key("velocity:lobby")));
        var content = new VelocityEventPresenter().present(event, true, this.renderer).orElseThrow();
        Assertions.assertTrue(plain(content.summary()).contains("connected to velocity:survival"));
        Assertions.assertTrue(plain(content.summary()).contains("velocity:lobby"));
        var command = "<red>/say " + "a".repeat(200) + " END";
        content = new VelocityEventPresenter().present(event("velocity_command",
            VelocityCommunicationPayloadCodec.encodeCommand("console", "Console", command)), true, this.renderer).orElseThrow();
        Assertions.assertFalse(plain(content.summary()).contains(" END"));
        Assertions.assertTrue(content.details().stream().map(VelocityEventPresenterTest::plain).anyMatch(text -> text.contains(command)));
        Assertions.assertTrue(plain(content.summary()).contains("entered a command"));
    }

    @Test
    void testLoginNetworkInformationOnlyAppearsInDetailsAndKickTextIsInert(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var content = new VelocityEventPresenter().present(event("velocity_post_login",
            VelocityPlayerSessionPayloadCodec.encodePostLogin("Alice",
                InetSocketAddress.createUnresolved("192.0.2.1", 25565), null, null)), true, this.renderer).orElseThrow();
        Assertions.assertFalse(plain(content.summary()).contains("192.0.2.1"));
        Assertions.assertTrue(content.details().stream().map(VelocityEventPresenterTest::plain).anyMatch(text -> text.contains("192.0.2.1")));
        var reason = Component.text("Reason").clickEvent(ClickEvent.runCommand("/danger"));
        content = new VelocityEventPresenter().present(event("backend_kick",
            VelocityPlayerSessionPayloadCodec.encodeBackendKick(reason, false,
                VelocityPlayerSessionPayloadCodec.ProxyAction.REDIRECT, Key.key("velocity:lobby"), reason)), true, this.renderer).orElseThrow();
        Assertions.assertTrue(plain(content.summary()).contains("Reason"));
        Assertions.assertFalse(content.summary().toString().contains("/danger"));
        Assertions.assertTrue(content.details().stream().map(VelocityEventPresenterTest::plain)
            .anyMatch(text -> text.contains("/danger")));
        Assertions.assertTrue(plain(content.summary()).contains("velocity:lobby"));
    }

    @Test
    void testAllSevenRegisteredVelocityTypesHaveDecoders(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var backend = Key.key("velocity:lobby");
        var fixtures = java.util.Map.of(
            "server_connected", VelocityServerConnectedPayloadCodec.encode(null),
            "velocity_post_login", VelocityPlayerSessionPayloadCodec.encodePostLogin("Alice",
                InetSocketAddress.createUnresolved("192.0.2.1", 12345), null, null),
            "velocity_disconnect", VelocityPlayerSessionPayloadCodec.encodeDisconnect("Alice",
                com.velocitypowered.api.event.connection.DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN, backend),
            "backend_kick", VelocityPlayerSessionPayloadCodec.encodeBackendKick(null, false,
                VelocityPlayerSessionPayloadCodec.ProxyAction.DISCONNECT, null, null),
            "velocity_chat", VelocityCommunicationPayloadCodec.encodeChat("original"),
            "velocity_command", VelocityCommunicationPayloadCodec.encodeCommand("console", "Console", "original"),
            "backend_registry_change", VelocityBackendRegistryChangePayloadCodec.encode(
                VelocityBackendRegistryChangePayloadCodec.Action.REGISTER, backend,
                new com.velocitypowered.api.proxy.server.ServerInfo("Lobby", InetSocketAddress.createUnresolved("example.invalid", 25565)))
        );
        var defaults = VelocityEventPresenter.defaultMessages().stream().flatMap(map -> map.keySet().stream())
            .filter(key -> key.startsWith("kansokusha.display.compact.event."))
            .map(key -> key.substring("kansokusha.display.compact.event.".length())).collect(java.util.stream.Collectors.toSet());
        Assertions.assertEquals(fixtures.keySet(), defaults);
        for (var fixture : fixtures.entrySet()) {
            var content = new VelocityEventPresenter().present(event(fixture.getKey(), fixture.getValue()), true, this.renderer);
            Assertions.assertTrue(content.isPresent(), fixture.getKey());
            Assertions.assertFalse(plain(content.orElseThrow().summary()).contains("kansokusha.display.event."));
        }
    }

    private static SearchPage.Event event(String type, EventPayload payload) {
        return new SearchPage.Event(UUID.randomUUID(), Key.key("kansokusha", type), Instant.now(),
            Optional.of(Key.key("velocity:survival")), Optional.empty(), Optional.empty(),
            Optional.of(new PlayerActor(UUID.randomUUID())), Optional.of("Alice"), Optional.empty(), Optional.empty(),
            Optional.of(new StoredPayload(PayloadGeneration.FIRST, payload)));
    }

    private static String plain(Component value) {
        return PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(value, Locale.ENGLISH));
    }
}
