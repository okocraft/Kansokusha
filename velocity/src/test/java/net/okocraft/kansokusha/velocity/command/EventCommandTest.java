package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.velocity.testsupport.CommandTester;
import net.okocraft.kansokusha.velocity.testsupport.TestSources;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class EventCommandTest {

    private static final UUID EVENT_ID =
        UUID.fromString("0199a123-4567-789a-8bcd-ef0123456791");
    private static final Key EVENT_TYPE = Key.key("kansokusha", "velocity_chat");

    private final EventSearchBackend api = Mockito.mock(EventSearchBackend.class);
    private final CommandTester tester = CommandTester.of(EventCommand.createEventCommand(this.api));

    @Test
    void testEventPermissionIsRequired() {
        ConsoleCommandSource console = TestSources.console();

        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(console, "event " + EVENT_ID)
        );

        TestSources.deny(console, EventCommand.PERMISSION);
        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(console, "event " + EVENT_ID)
        );
    }

    @Test
    void testVelocityCommandLooksUpAndRendersAuthorizedEvent() throws Exception {
        ConsoleCommandSource console = TestSources.console();
        TestSources.grant(
            console,
            EventCommand.PERMISSION,
            SearchCommandSupport.eventPermission(EVENT_TYPE)
        );
        Mockito.when(this.api.findEvent(EVENT_ID))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(event())));

        Assertions.assertEquals(1, this.tester.execute(console, "event " + EVENT_ID));

        Mockito.verify(this.api).findEvent(EVENT_ID);
        Mockito.verify(console).sendMessage(
            EventCommandMessages.DETAIL_LINE.apply(
                EventCommandMessages.EVENT_ID.asComponent(),
                Component.text(EVENT_ID.toString())
            )
        );
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
