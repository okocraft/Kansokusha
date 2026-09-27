package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.velocity.testsupport.CommandTester;
import net.okocraft.kansokusha.velocity.testsupport.TestSources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class EventCommandTest {

    private static final UUID EVENT_ID =
        UUID.fromString("0199a123-4567-789a-8bcd-ef0123456791");
    private static final Key EVENT_TYPE = Key.key("kansokusha", "velocity_chat");

    private final CommandTester tester = CommandTester.of(EventCommand.createEventCommand());
    private SearchableApi api;

    @BeforeEach
    void setUp() {
        this.api = Mockito.mock(SearchableApi.class);
        Kansokusha.setApi(this.api);
    }

    @AfterEach
    void tearDown() {
        Kansokusha.setApi(null);
    }

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
            EventCommandSupport.eventPermission(EVENT_TYPE)
        );
        Mockito.when(this.api.findEvent(EVENT_ID))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(event())));

        Assertions.assertEquals(1, this.tester.execute(console, "event " + EVENT_ID));

        Mockito.verify(this.api).findEvent(EVENT_ID);
        Mockito.verify(console).sendMessage(
            EventCommandMessages.EVENT_ID.asComponent()
                .append(Component.text(": "))
                .append(Component.text(EVENT_ID.toString()))
        );
    }

    @Test
    void testInvalidIdIsRejectedBeforeBackendLookup() throws Exception {
        ConsoleCommandSource console = TestSources.console();
        TestSources.grant(console, EventCommand.PERMISSION);

        Assertions.assertEquals(0, this.tester.execute(console, "event invalid"));

        Mockito.verify(console).sendMessage(EventCommandMessages.INVALID_ID.asComponent());
        Mockito.verify(this.api, Mockito.never()).findEvent(Mockito.any());
    }

    private static EventDetail event() {
        return new EventDetail(
            EVENT_ID,
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            Instant.parse("2026-09-27T10:00:00Z"),
            Optional.empty(),
            Optional.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Instant.parse("2026-10-27T10:00:00Z"),
            Optional.empty()
        );
    }

    private interface SearchableApi extends KansokushaApi, EventSearchBackend {
    }
}
