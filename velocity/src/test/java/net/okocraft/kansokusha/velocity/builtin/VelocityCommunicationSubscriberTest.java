package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.identity.Identity;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class VelocityCommunicationSubscriberTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-26T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174006");

    @Test
    void testSubscribersDoNotForceAsyncDispatch() throws Exception {
        var chat = VelocityChatSubscriber.class
            .getMethod("record", PlayerChatEvent.class)
            .getAnnotation(Subscribe.class);
        var command = VelocityCommandSubscriber.class
            .getMethod("record", CommandExecuteEvent.class)
            .getAnnotation(Subscribe.class);

        Assertions.assertNotNull(chat);
        Assertions.assertFalse(chat.async());
        Assertions.assertNotNull(command);
        Assertions.assertFalse(command.async());
    }

    @Test
    void testChatRecordsOriginalMessageWithoutReadingResult() throws Exception {
        var api = api();
        var subscriber = VelocityChatSubscriber.register(api, fixedClock());
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        var event = Mockito.mock(PlayerChatEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getMessage()).thenReturn("original message");
        Mockito.when(event.getResult()).thenThrow(new AssertionError("result must not be read"));

        subscriber.record(event);

        var submission = submission(api);
        Assertions.assertEquals(VelocityChatSubscriber.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertNull(submission.serverKey());
        Assertions.assertNull(submission.worldKey());
        Assertions.assertNull(submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        Assertions.assertEquals(
            "original message",
            VelocityCommunicationPayloadCodec.decodeChat(submission.payload())
        );
        Mockito.verify(event, Mockito.never()).getResult();
    }

    @Test
    void testPlayerCommandRecordsOriginalCommandAndPlayerDescriptorWithoutResult()
        throws Exception {
        var api = api();
        var subscriber = VelocityCommandSubscriber.register(api, fixedClock());
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getUsername()).thenReturn("TestPlayer");
        var event = commandEvent(player, "  velocity info", CommandExecuteEvent.Source.PLAYER);
        Mockito.when(event.getResult()).thenThrow(new AssertionError("result must not be read"));

        subscriber.record(event);

        var submission = submission(api);
        Assertions.assertEquals(VelocityCommandSubscriber.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertNull(submission.serverKey());
        Assertions.assertNull(submission.worldKey());
        Assertions.assertNull(submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        var payload = VelocityCommunicationPayloadCodec.decodeCommand(submission.payload());
        Assertions.assertEquals("player", payload.sourceKind());
        Assertions.assertEquals("TestPlayer", payload.sourceName());
        Assertions.assertEquals("  velocity info", payload.command());
        Mockito.verify(event, Mockito.never()).getResult();
    }

    @Test
    void testConsoleCommandUsesConsoleDescriptorWithoutSubject() throws Exception {
        var api = api();
        var subscriber = VelocityCommandSubscriber.register(api, fixedClock());
        var console = Mockito.mock(ConsoleCommandSource.class);
        var event = commandEvent(console, "plugins", CommandExecuteEvent.Source.API);

        subscriber.record(event);

        var submission = submission(api);
        Assertions.assertNull(submission.serverKey());
        Assertions.assertNull(submission.subject());
        var payload = VelocityCommunicationPayloadCodec.decodeCommand(submission.payload());
        Assertions.assertEquals("console", payload.sourceKind());
        Assertions.assertEquals("CONSOLE", payload.sourceName());
        Assertions.assertEquals("plugins", payload.command());
    }

    @Test
    void testApiCommandUsesAdventureSourceNameWithoutSubject() throws Exception {
        var api = api();
        var subscriber = VelocityCommandSubscriber.register(api, fixedClock());
        var source = Mockito.mock(CommandSource.class);
        Mockito.when(source.get(Identity.NAME)).thenReturn(java.util.Optional.of("automation"));
        var event = commandEvent(source, "custom original", CommandExecuteEvent.Source.API);

        subscriber.record(event);

        var submission = submission(api);
        Assertions.assertNull(submission.serverKey());
        Assertions.assertNull(submission.subject());
        var payload = VelocityCommunicationPayloadCodec.decodeCommand(submission.payload());
        Assertions.assertEquals("api", payload.sourceKind());
        Assertions.assertEquals("automation", payload.sourceName());
        Assertions.assertEquals("custom original", payload.command());
    }

    @Test
    void testRegistrationConflictFails() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any()))
            .thenReturn(RegistrationOutcome.CONFLICT);

        var failure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> VelocityChatSubscriber.register(api)
        );

        Assertions.assertTrue(failure.getMessage().contains("kansokusha:velocity_chat"));
    }

    private static CommandExecuteEvent commandEvent(
        CommandSource source,
        String command,
        CommandExecuteEvent.Source invocationSource
    ) {
        var event = Mockito.mock(CommandExecuteEvent.class);
        Mockito.when(event.getCommandSource()).thenReturn(source);
        Mockito.when(event.getCommand()).thenReturn(command);
        Mockito.when(event.getInvocationInfo()).thenReturn(
            new CommandExecuteEvent.InvocationInfo(
                CommandExecuteEvent.SignedState.UNSUPPORTED,
                invocationSource
            )
        );
        return event;
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

    private static Clock fixedClock() {
        return Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
    }
}
