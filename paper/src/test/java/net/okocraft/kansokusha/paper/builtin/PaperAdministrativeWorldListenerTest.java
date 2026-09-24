package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.event.world.WorldDifficultyChangeEvent;
import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent;
import io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent;
import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.EventSubject;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.world.SpawnChangeEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

@SuppressWarnings("unchecked")
class PaperAdministrativeWorldListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174020");
    private static final UUID EXECUTOR_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174021");

    @Test
    void testGameRuleChangeUsesEarliestOldAndFinalValueWithExactSource() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperGameRuleChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var world = world("rules");
        GameRule<Boolean> rule = Mockito.mock(GameRule.class);
        Mockito.when(rule.getKey()).thenReturn(new NamespacedKey("minecraft", "keep_inventory"));
        Mockito.when(world.getGameRuleValue(rule)).thenReturn(false);

        var player = Mockito.mock(Player.class);
        Mockito.when(player.getName()).thenReturn("Alice");
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);

        var event = Mockito.mock(WorldGameRuleChangeEvent.class);
        Mockito.when(event.getWorld()).thenReturn(world);
        Mockito.doReturn(rule).when(event).getGameRule();
        Mockito.when(event.getCommandSender()).thenReturn(player);
        Mockito.when(event.getValue()).thenReturn("true");
        Mockito.when(event.isCancelled()).thenReturn(false);

        listener.capture(event);
        Mockito.when(event.getValue()).thenReturn("true");
        listener.finalizeEvent(event);

        var submission = onlySubmission(api);
        assertWorldCommon(
            submission,
            PaperGameRuleChangeListener.EVENT_TYPE,
            "rules",
            new PlayerSubject(PLAYER_ID)
        );
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("minecraft:keep_inventory", string(payload, "game_rule"));
        Assertions.assertEquals("false", string(payload, "before"));
        Assertions.assertEquals("true", string(payload, "after"));
        Assertions.assertEquals("world_gamerule_change", string(payload, "source_event"));
        Assertions.assertTrue(payload.getBooleanOr("source_present", false));

        var source = payload.getCompoundOrEmpty("source");
        Assertions.assertEquals("player", string(source, "sender_kind"));
        Assertions.assertEquals("Alice", string(source, "sender_name"));
        Assertions.assertEquals(PLAYER_ID.toString(), string(source, "sender_uuid"));
        Assertions.assertEquals("minecraft:player", string(source, "sender_entity_type"));
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testGameRuleCancellationAndUnavailableSourceDoNotInventActor() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperGameRuleChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var world = world("rules");
        GameRule<Integer> rule = Mockito.mock(GameRule.class);
        Mockito.when(rule.getKey()).thenReturn(new NamespacedKey("minecraft", "random_tick_speed"));
        Mockito.when(world.getGameRuleValue(rule)).thenReturn(3);

        var cancelled = Mockito.mock(WorldGameRuleChangeEvent.class);
        Mockito.when(cancelled.getWorld()).thenReturn(world);
        Mockito.doReturn(rule).when(cancelled).getGameRule();
        Mockito.when(cancelled.getValue()).thenReturn("5");
        Mockito.when(cancelled.isCancelled()).thenReturn(true);
        listener.capture(cancelled);
        listener.finalizeEvent(cancelled);
        Assertions.assertTrue(api.submissions.isEmpty());

        var accepted = Mockito.mock(WorldGameRuleChangeEvent.class);
        Mockito.when(accepted.getWorld()).thenReturn(world);
        Mockito.doReturn(rule).when(accepted).getGameRule();
        Mockito.when(accepted.getValue()).thenReturn("7");
        Mockito.when(accepted.isCancelled()).thenReturn(false);
        Mockito.when(accepted.getCommandSender()).thenReturn(null);
        listener.capture(accepted);
        listener.finalizeEvent(accepted);

        var submission = onlySubmission(api);
        Assertions.assertNull(submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertFalse(payload.getBooleanOr("source_present", true));
        Assertions.assertFalse(payload.contains("source"));
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testDifficultyChangeRecordsEffectiveStateAndCommandSourceDescriptor() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperWorldDifficultyChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var world = world("difficulty");
        Mockito.when(world.getDifficulty()).thenReturn(Difficulty.NORMAL);
        Mockito.when(world.isHardcore()).thenReturn(false);

        var sender = Mockito.mock(ConsoleCommandSender.class);
        Mockito.when(sender.getName()).thenReturn("CONSOLE");
        var executor = Mockito.mock(Entity.class);
        Mockito.when(executor.getUniqueId()).thenReturn(EXECUTOR_ID);
        Mockito.when(executor.getType()).thenReturn(EntityType.ZOMBIE);
        var source = Mockito.mock(CommandSourceStack.class);
        Mockito.when(source.getSender()).thenReturn(sender);
        Mockito.when(source.getExecutor()).thenReturn(executor);

        var event = Mockito.mock(WorldDifficultyChangeEvent.class);
        Mockito.when(event.getWorld()).thenReturn(world);
        Mockito.when(event.getCommandSource()).thenReturn(source);
        Mockito.when(event.getDifficulty()).thenReturn(Difficulty.HARD);

        listener.capture(event);
        listener.finalizeEvent(event);

        var submission = onlySubmission(api);
        assertWorldCommon(
            submission,
            PaperWorldDifficultyChangeListener.EVENT_TYPE,
            "difficulty",
            null
        );
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("normal", string(payload, "before"));
        Assertions.assertEquals("hard", string(payload, "after"));
        Assertions.assertEquals("world_difficulty_change", string(payload, "source_event"));
        var sourceTag = payload.getCompoundOrEmpty("source");
        Assertions.assertEquals("console", string(sourceTag, "sender_kind"));
        Assertions.assertEquals("CONSOLE", string(sourceTag, "sender_name"));
        Assertions.assertEquals("entity", string(sourceTag, "executor_kind"));
        Assertions.assertEquals(EXECUTOR_ID.toString(), string(sourceTag, "executor_uuid"));
        Assertions.assertEquals("minecraft:zombie", string(sourceTag, "executor_entity_type"));
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testHardcoreDifficultyUsesEffectiveHardValueAndDropsNoOp() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperWorldDifficultyChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var world = world("hardcore");
        Mockito.when(world.getDifficulty()).thenReturn(Difficulty.HARD);
        Mockito.when(world.isHardcore()).thenReturn(true);

        var event = Mockito.mock(WorldDifficultyChangeEvent.class);
        Mockito.when(event.getWorld()).thenReturn(world);
        Mockito.when(event.getDifficulty()).thenReturn(Difficulty.EASY);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testWorldBorderCenterAndBoundsUseFinalRequestedValues() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperWorldBorderChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var world = world("border");

        var centerEvent = Mockito.mock(WorldBorderCenterChangeEvent.class);
        Mockito.when(centerEvent.getWorld()).thenReturn(world);
        Mockito.when(centerEvent.getOldCenter()).thenReturn(new Location(world, 1.25, 0, -2.5));
        Mockito.when(centerEvent.getNewCenter()).thenReturn(new Location(world, 10, 0, 20));
        Mockito.when(centerEvent.isCancelled()).thenReturn(false);
        listener.captureCenter(centerEvent);
        Mockito.when(centerEvent.getNewCenter()).thenReturn(new Location(world, 30.5, 0, 40.75));
        listener.finalizeCenter(centerEvent);

        var centerSubmission = onlySubmission(api);
        assertWorldCommon(
            centerSubmission,
            PaperWorldBorderChangeListener.EVENT_TYPE,
            "border",
            null
        );
        var centerPayload = PaperPayloadNbtCodec.decode(centerSubmission.payload());
        Assertions.assertEquals("center", string(centerPayload, "action"));
        Assertions.assertEquals(centerTag(1.25, -2.5), centerPayload.getCompoundOrEmpty("before"));
        Assertions.assertEquals(centerTag(30.5, 40.75), centerPayload.getCompoundOrEmpty("after"));
        Assertions.assertEquals(
            "world_border_center_change",
            string(centerPayload, "source_event")
        );

        var boundsEvent = Mockito.mock(WorldBorderBoundsChangeEvent.class);
        Mockito.when(boundsEvent.getWorld()).thenReturn(world);
        Mockito.when(boundsEvent.getOldSize()).thenReturn(1000.0);
        Mockito.when(boundsEvent.getNewSize()).thenReturn(500.0);
        Mockito.when(boundsEvent.getType())
            .thenReturn(WorldBorderBoundsChangeEvent.Type.INSTANT_MOVE);
        Mockito.when(boundsEvent.getDurationTicks()).thenReturn(0L);
        Mockito.when(boundsEvent.isCancelled()).thenReturn(false);
        listener.captureBounds(boundsEvent);

        Mockito.when(boundsEvent.getNewSize()).thenReturn(250.0);
        Mockito.when(boundsEvent.getType())
            .thenReturn(WorldBorderBoundsChangeEvent.Type.STARTED_MOVE);
        Mockito.when(boundsEvent.getDurationTicks()).thenReturn(120L);
        listener.finalizeBounds(boundsEvent);

        var boundsSubmission = onlySubmission(api);
        var boundsPayload = PaperPayloadNbtCodec.decode(boundsSubmission.payload());
        var expectedBounds = new CompoundTag();
        expectedBounds.putString("action", "bounds");
        expectedBounds.putDouble("before_size", 1000.0);
        expectedBounds.putDouble("after_size", 250.0);
        expectedBounds.putString("transition_type", "started_move");
        expectedBounds.putLong("transition_duration_ticks", 120L);
        expectedBounds.putString("source_event", "world_border_bounds_change");
        Assertions.assertEquals(expectedBounds, boundsPayload);
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testCancelledWorldBorderChangesAreNotRecorded() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperWorldBorderChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var world = world("border");

        var centerEvent = Mockito.mock(WorldBorderCenterChangeEvent.class);
        Mockito.when(centerEvent.getWorld()).thenReturn(world);
        Mockito.when(centerEvent.getOldCenter()).thenReturn(new Location(world, 0, 0, 0));
        Mockito.when(centerEvent.isCancelled()).thenReturn(true);
        listener.captureCenter(centerEvent);
        listener.finalizeCenter(centerEvent);

        var boundsEvent = Mockito.mock(WorldBorderBoundsChangeEvent.class);
        Mockito.when(boundsEvent.getWorld()).thenReturn(world);
        Mockito.when(boundsEvent.getOldSize()).thenReturn(100.0);
        Mockito.when(boundsEvent.isCancelled()).thenReturn(true);
        listener.captureBounds(boundsEvent);
        listener.finalizeBounds(boundsEvent);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testWorldSpawnIsDistinctFromPlayerSpawnAndRecordsEstablishedPosition() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperWorldSpawnChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var world = world("spawn");
        var before = new Location(world, 10.5, 64, 20.25, 30, 5);
        var after = new Location(world, -4.75, 80.5, 9.125, 90, 0);
        Mockito.when(world.getSpawnLocation()).thenReturn(after);

        var event = Mockito.mock(SpawnChangeEvent.class);
        Mockito.when(event.getWorld()).thenReturn(world);
        Mockito.when(event.getPreviousLocation()).thenReturn(before);

        listener.record(event);

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperWorldSpawnChangeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertNotEquals(PaperPlayerSpawnChangeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(Key.key("example", "spawn"), submission.worldKey());
        Assertions.assertEquals(new BlockPosition(-5, 80, 9), submission.position());
        Assertions.assertNull(submission.subject());

        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("world", string(payload, "scope"));
        Assertions.assertEquals("spawn_change", string(payload, "source_event"));
        Assertions.assertEquals(
            locationTag("example:spawn", 10.5, 64, 20.25, 30, 5),
            payload.getCompoundOrEmpty("before")
        );
        Assertions.assertEquals(
            locationTag("example:spawn", -4.75, 80.5, 9.125, 90, 0),
            payload.getCompoundOrEmpty("after")
        );
    }

    private static Clock fixedClock() {
        return Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
    }

    private static World world(String name) {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", name));
        return world;
    }

    private static EventSubmission onlySubmission(PaperBlockEventTestSupport.RecordingApi api) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.remove();
    }

    private static void assertWorldCommon(
        EventSubmission submission,
        Key eventType,
        String world,
        EventSubject subject
    ) {
        Assertions.assertEquals(eventType, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(PaperBlockEventTestSupport.SERVER_KEY, submission.serverKey());
        Assertions.assertEquals(Key.key("example", world), submission.worldKey());
        Assertions.assertNull(submission.position());
        Assertions.assertEquals(subject, submission.subject());
    }

    private static String string(CompoundTag payload, String key) {
        return payload.getString(key).orElseThrow();
    }

    private static CompoundTag centerTag(double x, double z) {
        var result = new CompoundTag();
        result.putDouble("x", x);
        result.putDouble("z", z);
        return result;
    }

    private static CompoundTag locationTag(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {
        var result = new CompoundTag();
        result.putString("world", world);
        result.putDouble("x", x);
        result.putDouble("y", y);
        result.putDouble("z", z);
        result.putFloat("yaw", yaw);
        result.putFloat("pitch", pitch);
        return result;
    }
}
