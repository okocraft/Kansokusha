package net.okocraft.kansokusha.paper.builtin;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerSpawnChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings({"deprecation", "removal"})
class PaperPlayerStateListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174010");
    private static final UUID KILLER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174011");

    @Test
    void testWorldChangeAndCrossWorldTeleportRemainDistinctRecords() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var teleport = PaperPlayerTeleportListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var worldChange = PaperPlayerWorldChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var fromWorld = world("from");
        var toWorld = world("to");
        var from = new Location(fromWorld, 1.25, 70, 2.5);
        var to = new Location(toWorld, 30.5, 80.25, 40.75);
        var player = player(toWorld, 30.5, 80.25, 40.75);

        var teleportEvent = teleportEvent(player, from, to, Set.of());
        teleport.capture(teleportEvent);
        teleport.finalizeEvent(teleportEvent);

        var worldEvent = Mockito.mock(PlayerChangedWorldEvent.class);
        Mockito.when(worldEvent.getPlayer()).thenReturn(player);
        Mockito.when(worldEvent.getFrom()).thenReturn(fromWorld);
        worldChange.record(worldEvent);

        Assertions.assertEquals(2, api.submissions.size());
        var teleportSubmission = api.submissions.remove();
        var worldSubmission = api.submissions.remove();
        Assertions.assertEquals(PaperPlayerTeleportListener.EVENT_TYPE, teleportSubmission.eventType());
        Assertions.assertEquals(PaperPlayerWorldChangeListener.EVENT_TYPE, worldSubmission.eventType());
        assertCommon(
            worldSubmission,
            PaperPlayerWorldChangeListener.EVENT_TYPE,
            Key.key("example", "to"),
            new BlockPosition(30, 80, 40)
        );
        var worldPayload = PaperPayloadNbtCodec.decode(worldSubmission.payload());
        Assertions.assertEquals(
            "gameplay_world_state_transition",
            worldPayload.getString("semantics").orElseThrow()
        );
        Assertions.assertEquals("example:from", worldPayload.getString("from_world").orElseThrow());
    }

    @Test
    void testTeleportSnapshotsSourceAndStoresFinalDestinationCauseAndRelativeFlags() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = teleportListener(api);
        var from = new Location(world("from"), 1.125, 64.5, 2.875, 10, 20);
        var initialTo = new Location(world("initial"), 5.25, 70.5, 6.75, 30, 40);
        var finalTo = new Location(world("final"), -10.125, 90.875, 20.5, 50, 60);
        var flag = TeleportFlag.Relative.values()[0];
        var player = player(initialTo.getWorld(), 5.25, 70.5, 6.75);
        var event = Mockito.mock(PlayerTeleportEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getFrom()).thenReturn(from);
        Mockito.when(event.getTo()).thenReturn(initialTo, finalTo);
        Mockito.when(event.getCause()).thenReturn(PlayerTeleportEvent.TeleportCause.COMMAND);
        Mockito.when(event.getRelativeTeleportationFlags()).thenReturn(Set.of(flag));
        Mockito.when(event.isCancelled()).thenReturn(false);

        listener.capture(event);
        from.setX(999);
        initialTo.setX(999);
        listener.finalizeEvent(event);

        var submission = onlySubmission(api);
        assertCommon(
            submission,
            PaperPlayerTeleportListener.EVENT_TYPE,
            Key.key("example", "final"),
            new BlockPosition(-11, 90, 20)
        );
        var expected = new CompoundTag();
        expected.putString("semantics", "successful_teleport_operation");
        expected.put("from", locationTag("example:from", 1.125, 64.5, 2.875, 10, 20));
        expected.put("to", locationTag("example:final", -10.125, 90.875, 20.5, 50, 60));
        expected.putString("cause", "command");
        var flags = new CompoundTag();
        flags.putBoolean(flag.name().toLowerCase(Locale.ROOT), true);
        expected.put("relative_flags", flags);
        Assertions.assertEquals(expected, PaperPayloadNbtCodec.decode(submission.payload()));
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testCancelledTeleportIsNotRecorded() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = teleportListener(api);
        var currentWorld = world("world");
        var event = teleportEvent(
            player(currentWorld, 1, 2, 3),
            new Location(currentWorld, 1, 2, 3),
            new Location(currentWorld, 4, 5, 6),
            Set.of()
        );
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testGameModeRecordsEstablishedOldToFinalNewState() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerGameModeChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var capturedLocation = new Location(world("world"), 2.75, 63.5, 3.25);
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getLocation()).thenReturn(capturedLocation);
        Mockito.when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        var event = Mockito.mock(PlayerGameModeChangeEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getNewGameMode()).thenReturn(GameMode.CREATIVE, GameMode.SPECTATOR);
        Mockito.when(event.getCause()).thenReturn(PlayerGameModeChangeEvent.Cause.COMMAND);
        Mockito.when(event.isCancelled()).thenReturn(false);

        listener.capture(event);
        capturedLocation.setX(99);
        listener.finalizeEvent(event);

        var submission = onlySubmission(api);
        assertCommon(
            submission,
            PaperPlayerGameModeChangeListener.EVENT_TYPE,
            Key.key("example", "world"),
            new BlockPosition(2, 63, 3)
        );
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("survival", payload.getString("old_gamemode").orElseThrow());
        Assertions.assertEquals("spectator", payload.getString("new_gamemode").orElseThrow());
        Assertions.assertEquals("command", payload.getString("cause").orElseThrow());
    }

    @Test
    void testCancelledGameModeChangeIsNotRecorded() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerGameModeChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var player = player(world("world"), 1, 2, 3);
        Mockito.when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        var event = Mockito.mock(PlayerGameModeChangeEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getNewGameMode()).thenReturn(GameMode.CREATIVE);
        Mockito.when(event.getCause()).thenReturn(PlayerGameModeChangeEvent.Cause.PLUGIN);
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testSpawnChangeRepresentsSetAndNullClearWithFinalTarget() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerSpawnChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var oldSpawn = new Location(world("old_spawn"), 10.5, 65, 20.5, 15, 0);
        var initialSpawn = new Location(world("initial_spawn"), 30.25, 70, 40.75, 45, 0);
        var finalSpawn = new Location(world("final_spawn"), 50.5, 80.25, 60.125, 90, 0);
        var player = player(world("current"), 0, 64, 0);
        Mockito.when(player.getRespawnLocation()).thenReturn(oldSpawn);
        var setEvent = Mockito.mock(PlayerSpawnChangeEvent.class);
        Mockito.when(setEvent.getPlayer()).thenReturn(player);
        Mockito.when(setEvent.getNewSpawn()).thenReturn(initialSpawn, finalSpawn);
        Mockito.when(setEvent.isForced()).thenReturn(false, true);
        Mockito.when(setEvent.getCause()).thenReturn(PlayerSpawnChangeEvent.Cause.BED);
        Mockito.when(setEvent.isCancelled()).thenReturn(false);

        listener.capture(setEvent);
        oldSpawn.setX(999);
        initialSpawn.setX(999);
        listener.finalizeEvent(setEvent);

        var setSubmission = onlySubmission(api);
        assertCommon(
            setSubmission,
            PaperPlayerSpawnChangeListener.EVENT_TYPE,
            Key.key("example", "final_spawn"),
            new BlockPosition(50, 80, 60)
        );
        var setPayload = PaperPayloadNbtCodec.decode(setSubmission.payload());
        Assertions.assertEquals(
            optionalLocationTag(locationTag("example:old_spawn", 10.5, 65, 20.5, 15, 0)),
            setPayload.getCompoundOrEmpty("before")
        );
        Assertions.assertEquals(
            optionalLocationTag(locationTag("example:final_spawn", 50.5, 80.25, 60.125, 90, 0)),
            setPayload.getCompoundOrEmpty("after")
        );
        Assertions.assertTrue(setPayload.getBooleanOr("forced", false));
        Assertions.assertEquals("bed", setPayload.getString("cause").orElseThrow());

        Mockito.when(player.getRespawnLocation()).thenReturn(finalSpawn);
        var clearEvent = Mockito.mock(PlayerSpawnChangeEvent.class);
        Mockito.when(clearEvent.getPlayer()).thenReturn(player);
        Mockito.when(clearEvent.getNewSpawn()).thenReturn(null);
        Mockito.when(clearEvent.isForced()).thenReturn(false);
        Mockito.when(clearEvent.getCause()).thenReturn(PlayerSpawnChangeEvent.Cause.PLUGIN);
        Mockito.when(clearEvent.isCancelled()).thenReturn(false);

        listener.capture(clearEvent);
        listener.finalizeEvent(clearEvent);

        var clearSubmission = onlySubmission(api);
        Assertions.assertEquals(Key.key("example", "final_spawn"), clearSubmission.worldKey());
        var clearPayload = PaperPayloadNbtCodec.decode(clearSubmission.payload());
        Assertions.assertEquals(optionalLocationTag(null), clearPayload.getCompoundOrEmpty("after"));
        Assertions.assertEquals("plugin", clearPayload.getString("cause").orElseThrow());
    }

    @Test
    void testLegacySpawnCompatibilityPathUsesSameEventTypeAndNullSchema() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperLegacyPlayerSetSpawnListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var player = player(world("current"), 0, 64, 0);
        Mockito.when(player.getRespawnLocation()).thenReturn(null);
        var event = Mockito.mock(PlayerSetSpawnEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getLocation()).thenReturn(new Location(world("spawn"), 3.5, 70, 4.5));
        Mockito.when(event.isForced()).thenReturn(true);
        Mockito.when(event.getCause()).thenReturn(PlayerSetSpawnEvent.Cause.RESPAWN_ANCHOR);
        Mockito.when(event.isCancelled()).thenReturn(false);

        listener.capture(event);
        listener.finalizeEvent(event);

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperPlayerSpawnChangeListener.EVENT_TYPE, submission.eventType());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(optionalLocationTag(null), payload.getCompoundOrEmpty("before"));
        Assertions.assertEquals("respawn_anchor", payload.getString("cause").orElseThrow());
        Assertions.assertEquals(
            "legacy_player_set_spawn",
            payload.getString("source_event").orElseThrow()
        );
    }

    @Test
    void testCancelledSpawnChangeIsNotRecorded() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerSpawnChangeListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var player = player(world("world"), 1, 2, 3);
        Mockito.when(player.getRespawnLocation()).thenReturn(null);
        var event = Mockito.mock(PlayerSpawnChangeEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getNewSpawn()).thenReturn(null);
        Mockito.when(event.isForced()).thenReturn(false);
        Mockito.when(event.getCause()).thenReturn(PlayerSpawnChangeEvent.Cause.PLUGIN);
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testDeathRecordsFinalContextWithoutFullDrops() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerDeathListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var location = new Location(world("death"), -1.25, 50.75, 8.5);
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getLocation()).thenReturn(location);
        var lastDamage = Mockito.mock(EntityDamageEvent.class);
        Mockito.when(lastDamage.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        Mockito.when(player.getLastDamageCause()).thenReturn(lastDamage);

        var killer = Mockito.mock(Entity.class);
        Mockito.when(killer.getUniqueId()).thenReturn(KILLER_ID);
        Mockito.when(killer.getType()).thenReturn(EntityType.ZOMBIE);
        var damageSource = Mockito.mock(DamageSource.class);
        Mockito.when(damageSource.getCausingEntity()).thenReturn(killer);
        var event = Mockito.mock(PlayerDeathEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getDamageSource()).thenReturn(damageSource);
        Mockito.when(event.isCancelled()).thenReturn(false);
        var finalMessage = Component.text("final death");
        Mockito.when(event.deathMessage()).thenReturn(finalMessage);
        Mockito.when(event.getDroppedExp()).thenReturn(7);
        Mockito.when(event.getNewExp()).thenReturn(3);
        Mockito.when(event.getNewTotalExp()).thenReturn(103);
        Mockito.when(event.getNewLevel()).thenReturn(5);
        Mockito.when(event.getKeepInventory()).thenReturn(true);
        Mockito.when(event.getKeepLevel()).thenReturn(false);

        listener.capture(event);
        location.setX(999);
        listener.finalizeEvent(event);

        var submission = onlySubmission(api);
        assertCommon(
            submission,
            PaperPlayerDeathListener.EVENT_TYPE,
            Key.key("example", "death"),
            new BlockPosition(-2, 50, 8)
        );
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            PaperComponentPayloadCodec.encode(finalMessage),
            payload.getString("death_message").orElseThrow()
        );
        Assertions.assertEquals(KILLER_ID.toString(), payload.getString("killer_uuid").orElseThrow());
        Assertions.assertEquals("entity", payload.getString("killer_kind").orElseThrow());
        Assertions.assertEquals(
            EntityType.ZOMBIE.key().asString(),
            payload.getString("killer_type").orElseThrow()
        );
        Assertions.assertEquals("fall", payload.getString("last_damage_cause").orElseThrow());
        Mockito.verify(event, Mockito.never()).getDrops();
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testCancelledDeathIsNotRecorded() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerDeathListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
        var player = player(world("death"), 1, 2, 3);
        var source = Mockito.mock(DamageSource.class);
        var event = Mockito.mock(PlayerDeathEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getDamageSource()).thenReturn(source);
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static PaperPlayerTeleportListener teleportListener(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        return PaperPlayerTeleportListener.register(
            api, PaperBlockEventTestSupport.SERVER_KEY, fixedClock()
        );
    }

    private static PlayerTeleportEvent teleportEvent(
        Player player,
        Location from,
        Location to,
        Set<TeleportFlag.Relative> relativeFlags
    ) {
        var event = Mockito.mock(PlayerTeleportEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getFrom()).thenReturn(from);
        Mockito.when(event.getTo()).thenReturn(to);
        Mockito.when(event.getCause()).thenReturn(PlayerTeleportEvent.TeleportCause.PLUGIN);
        Mockito.when(event.getRelativeTeleportationFlags()).thenReturn(relativeFlags);
        Mockito.when(event.isCancelled()).thenReturn(false);
        return event;
    }

    private static Clock fixedClock() {
        return Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
    }

    private static World world(String value) {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", value));
        return world;
    }

    private static Player player(World world, double x, double y, double z) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getLocation()).thenReturn(new Location(world, x, y, z));
        return player;
    }

    private static EventSubmission onlySubmission(PaperBlockEventTestSupport.RecordingApi api) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.remove();
    }

    private static void assertCommon(
        EventSubmission submission,
        Key eventType,
        Key worldKey,
        BlockPosition position
    ) {
        Assertions.assertEquals(eventType, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(PaperBlockEventTestSupport.SERVER_KEY, submission.serverKey());
        Assertions.assertEquals(worldKey, submission.worldKey());
        Assertions.assertEquals(position, submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
    }

    private static CompoundTag locationTag(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {
        var tag = new CompoundTag();
        tag.putString("world", world);
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putFloat("yaw", yaw);
        tag.putFloat("pitch", pitch);
        return tag;
    }

    private static CompoundTag optionalLocationTag(CompoundTag location) {
        var tag = new CompoundTag();
        tag.putBoolean("present", location != null);
        if (location != null) {
            tag.put("location", location);
        }
        return tag;
    }
}
