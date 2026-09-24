package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class PaperCommunicationListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174005");

    @Test
    void testListenersImplementBukkitListenerAndUseLowestWithoutIgnoringCancelled() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();

        Assertions.assertInstanceOf(
            Listener.class,
            PaperChatListener.register(api, PaperBlockEventTestSupport.SERVER_KEY)
        );
        Assertions.assertInstanceOf(
            Listener.class,
            PaperPlayerCommandListener.register(api, PaperBlockEventTestSupport.SERVER_KEY)
        );
        Assertions.assertInstanceOf(
            Listener.class,
            PaperServerCommandListener.register(api, PaperBlockEventTestSupport.SERVER_KEY)
        );

        assertRawHandler(PaperChatListener.class, "record", AsyncChatEvent.class);
        assertRawHandler(
            PaperPlayerCommandListener.class,
            "record",
            PlayerCommandPreprocessEvent.class
        );
        assertRawHandler(PaperServerCommandListener.class, "record", ServerCommandEvent.class);
        assertRawHandler(
            PaperServerCommandListener.class,
            "recordRemote",
            RemoteServerCommandEvent.class
        );
    }

    @Test
    void testChatRecordsOriginalComponentWithoutReadingDeliveryState() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperChatListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        var original = Component.text("original message");
        var event = new AsyncChatEvent(
            true,
            player,
            Set.of(),
            Mockito.mock(ChatRenderer.class),
            Component.text("already replaced"),
            original,
            Mockito.mock(SignedMessage.class)
        );
        event.setCancelled(true);

        listener.record(event);

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperChatListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(PaperBlockEventTestSupport.SERVER_KEY, submission.serverKey());
        Assertions.assertNull(submission.worldKey());
        Assertions.assertNull(submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            original,
            PaperComponentPayloadCodec.decode(string(payload, "message"))
        );
        Assertions.assertFalse(payload.contains("cancelled"));
        Assertions.assertFalse(payload.contains("viewers"));
        Assertions.assertFalse(payload.contains("renderer"));
        Mockito.verify(player, Mockito.never()).getLocation();
    }

    @Test
    void testPlayerCommandSnapshotsOriginalLineAndLocationBeforeLaterRewrite() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerCommandListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var world = PaperBlockEventTestSupport.world();
        var player = player(world, 12.75, 64.0, -8.25);
        var event = new PlayerCommandPreprocessEvent(player, "/original value", Set.of());
        event.setCancelled(true);

        listener.record(event);
        event.setMessage("/rewritten");

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperPlayerCommandListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        Assertions.assertEquals(
            net.kyori.adventure.key.Key.key("example", "world"),
            submission.worldKey()
        );
        Assertions.assertEquals(new BlockPosition(12, 64, -9), submission.position());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("/original value", string(payload, "command"));
        Assertions.assertFalse(payload.contains("cancelled"));
        Assertions.assertFalse(payload.contains("success"));
        Assertions.assertFalse(payload.contains("result"));
    }

    @Test
    void testServerCommandDistinguishesConsoleAndOtherSources() throws Exception {
        var consoleApi = new PaperBlockEventTestSupport.RecordingApi();
        var listener = serverListener(consoleApi);
        var console = Mockito.mock(ConsoleCommandSender.class);
        Mockito.when(console.getName()).thenReturn("CONSOLE");
        var consoleCommand = new AtomicReference<>("say original");
        var consoleEvent = Mockito.mock(ServerCommandEvent.class);
        Mockito.when(consoleEvent.getSender()).thenReturn(console);
        Mockito.when(consoleEvent.getCommand()).thenAnswer(ignored -> consoleCommand.get());
        Mockito.when(consoleEvent.isCancelled()).thenReturn(true);

        listener.record(consoleEvent);
        consoleCommand.set("say rewritten");
        Mockito.verify(consoleEvent, Mockito.never()).isCancelled();

        assertServerSubmission(
            onlySubmission(consoleApi),
            "console",
            "CONSOLE",
            "say original",
            null,
            null
        );

        var otherApi = new PaperBlockEventTestSupport.RecordingApi();
        listener = serverListener(otherApi);
        var other = Mockito.mock(CommandSender.class);
        Mockito.when(other.getName()).thenReturn("custom-source");
        var otherEvent = Mockito.mock(ServerCommandEvent.class);
        Mockito.when(otherEvent.getSender()).thenReturn(other);
        Mockito.when(otherEvent.getCommand()).thenReturn("custom command");
        listener.record(otherEvent);

        assertServerSubmission(
            onlySubmission(otherApi),
            "other",
            "custom-source",
            "custom command",
            null,
            null
        );
    }

    @Test
    void testServerCommandRecordsCommandBlockLocation() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = serverListener(api);
        var world = PaperBlockEventTestSupport.world();
        var block = block(world, 21, 70, -4);
        var sender = Mockito.mock(BlockCommandSender.class);
        Mockito.when(sender.getName()).thenReturn("command-block");
        Mockito.when(sender.getBlock()).thenReturn(block);

        var event = Mockito.mock(ServerCommandEvent.class);
        Mockito.when(event.getSender()).thenReturn(sender);
        Mockito.when(event.getCommand()).thenReturn("setblock ~ ~ ~ stone");
        listener.record(event);

        assertServerSubmission(
            onlySubmission(api),
            "command_block",
            "command-block",
            "setblock ~ ~ ~ stone",
            net.kyori.adventure.key.Key.key("example", "world"),
            new BlockPosition(21, 70, -4)
        );
    }

    @Test
    void testRemoteServerCommandUsesRconDescriptorWithoutBaseDuplicate() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = serverListener(api);
        var sender = Mockito.mock(RemoteConsoleCommandSender.class);
        Mockito.when(sender.getName()).thenReturn("Rcon");
        var event = Mockito.mock(RemoteServerCommandEvent.class);
        Mockito.when(event.getSender()).thenReturn(sender);
        Mockito.when(event.getCommand()).thenReturn("list");
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.record(event);
        listener.recordRemote(event);
        Mockito.verify(event, Mockito.never()).isCancelled();

        Assertions.assertEquals(1, api.submissions.size());
        assertServerSubmission(
            onlySubmission(api),
            "rcon",
            "Rcon",
            "list",
            null,
            null
        );
    }

    private static void assertRawHandler(
        Class<?> listenerClass,
        String methodName,
        Class<?> eventClass
    ) throws Exception {
        var annotation = listenerClass.getMethod(methodName, eventClass).getAnnotation(EventHandler.class);
        Assertions.assertNotNull(annotation);
        Assertions.assertEquals(EventPriority.LOWEST, annotation.priority());
        Assertions.assertFalse(annotation.ignoreCancelled());
    }

    private static PaperServerCommandListener serverListener(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        return PaperServerCommandListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
    }

    private static Clock fixedClock() {
        return Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
    }

    private static Player player(World world, double x, double y, double z) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getLocation()).thenReturn(new Location(world, x, y, z));
        return player;
    }

    private static Block block(World world, int x, int y, int z) {
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(y);
        Mockito.when(block.getZ()).thenReturn(z);
        return block;
    }

    private static EventSubmission onlySubmission(PaperBlockEventTestSupport.RecordingApi api) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.remove();
    }

    private static void assertServerSubmission(
        EventSubmission submission,
        String sourceKind,
        String sourceName,
        String command,
        net.kyori.adventure.key.Key worldKey,
        BlockPosition position
    ) throws Exception {
        Assertions.assertEquals(PaperServerCommandListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(PaperBlockEventTestSupport.SERVER_KEY, submission.serverKey());
        Assertions.assertEquals(worldKey, submission.worldKey());
        Assertions.assertEquals(position, submission.position());
        Assertions.assertNull(submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(sourceKind, string(payload, "source_kind"));
        Assertions.assertEquals(sourceName, string(payload, "source_name"));
        Assertions.assertEquals(command, string(payload, "command"));
        Assertions.assertFalse(payload.contains("cancelled"));
        Assertions.assertFalse(payload.contains("success"));
        Assertions.assertFalse(payload.contains("result"));
    }

    private static String string(CompoundTag tag, String key) {
        return tag.getString(key).orElseThrow();
    }
}
