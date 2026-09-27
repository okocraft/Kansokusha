package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class EventCommandTest {

    private static final UUID EVENT_ID =
        UUID.fromString("0199a123-4567-789a-8bcd-ef0123456790");
    private static final Key EVENT_TYPE = Key.key("kansokusha", "block_break");

    private final EventSearchBackend api = Mockito.mock(EventSearchBackend.class);
    private final CommandTester tester = CommandTester.of(
        EventCommand.createEventCommand(this.api).build()
    );

    @Test
    void testEventPermissionIsRequired() {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);

        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(TestSources.ofSenderOnly(console), "event " + EVENT_ID)
        );

        TestSources.deny(console, EventCommand.PERMISSION);
        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(TestSources.ofSenderOnly(console), "event " + EVENT_ID)
        );
    }

    @Test
    void testPaperCommandLooksUpAndRendersAuthorizedEvent() throws Exception {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(
            console,
            EventCommand.PERMISSION,
            SearchCommandSupport.eventPermission(EVENT_TYPE)
        );
        Mockito.when(this.api.findEvent(EVENT_ID))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(event())));

        Assertions.assertEquals(
            1,
            this.tester.execute(TestSources.ofSenderOnly(console), "event " + EVENT_ID)
        );

        Mockito.verify(this.api).findEvent(EVENT_ID);
        Mockito.verify(console).sendMessage(
            EventCommandMessages.EVENT_ID.asComponent()
                .append(net.kyori.adventure.text.Component.text(": "))
                .append(net.kyori.adventure.text.Component.text(EVENT_ID.toString()))
        );
    }

    @Test
    void testInvalidIdIsRejectedBeforeBackendLookup() throws Exception {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(console, EventCommand.PERMISSION);

        Assertions.assertEquals(
            0,
            this.tester.execute(TestSources.ofSenderOnly(console), "event invalid")
        );

        Mockito.verify(console).sendMessage(EventCommandMessages.INVALID_ID.asComponent());
        Mockito.verify(this.api, Mockito.never()).findEvent(Mockito.any());
    }

    private static EventDetail event() {
        return new EventDetail(
            new SearchPage.Event(
                EVENT_ID,
                EVENT_TYPE,
                Instant.parse("2026-09-27T10:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            ),
            PayloadGeneration.FIRST,
            Instant.parse("2026-10-27T10:00:00Z")
        );
    }
}
