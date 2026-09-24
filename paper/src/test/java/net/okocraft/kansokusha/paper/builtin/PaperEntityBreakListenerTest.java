package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class PaperEntityBreakListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID ENTITY_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174110");
    private static final UUID BREAKER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174111");

    @Test
    void testGenericBreakSnapshotsTargetAndMobBreaker() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var target = entity(world, EntityType.ARMOR_STAND, ENTITY_ID, 8.25, 64, -2.75);
        var breaker = entity(world, EntityType.ZOMBIE, BREAKER_ID, 9, 64, -2);
        var event = new PaperGenericEntityBreakEventFixture(
            target,
            breaker,
            damageSource(DamageType.MOB_ATTACK, false),
            PaperGenericEntityBreakEventFixture.RemoveCause.ENTITY
        );

        listener.captureGeneric(event);
        Mockito.when(target.getLocation()).thenReturn(new Location(world, 99, 99, 99));
        Mockito.when(breaker.getType()).thenReturn(EntityType.CREEPER);
        listener.finalizeGeneric(event);

        var submission = onlySubmission(api);
        assertCommon(submission, new BlockPosition(8, 64, -3));
        Assertions.assertNull(submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        assertEntity(payload.getCompoundOrEmpty("entity"), ENTITY_ID, "minecraft:armor_stand");
        assertEntity(payload.getCompoundOrEmpty("breaker"), BREAKER_ID, "minecraft:zombie");
        Assertions.assertEquals("entity", payload.getString("cause").orElseThrow());
        Assertions.assertEquals("minecraft:mob_attack", payload.getString("damage_type").orElseThrow());
        Assertions.assertFalse(payload.getBoolean("indirect_damage").orElseThrow());
        Assertions.assertEquals(
            PaperEntityBreakListener.GENERIC_SOURCE_EVENT,
            payload.getString("source_event").orElseThrow()
        );
        Assertions.assertFalse(payload.contains("hanging"));
    }

    @Test
    void testHangingBreakUsesPlayerSubjectAndHangingContext() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var target = hanging(world, ENTITY_ID, 15.5, 70, 9.5);
        var breaker = player(world);
        var source = damageSource(DamageType.PLAYER_EXPLOSION, true);
        var event = Mockito.mock(HangingBreakByEntityEvent.class);
        Mockito.when(event.getEntity()).thenReturn(target);
        Mockito.when(event.getRemover()).thenReturn(breaker);
        Mockito.when(event.getCause()).thenReturn(HangingBreakEvent.RemoveCause.EXPLOSION);
        Mockito.when(event.getDamageSource()).thenReturn(source);

        listener.captureHanging(event);
        listener.finalizeHanging(event);

        var submission = onlySubmission(api);
        Assertions.assertEquals(new PlayerSubject(BREAKER_ID), submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("explosion", payload.getString("cause").orElseThrow());
        Assertions.assertEquals(
            "minecraft:player_explosion",
            payload.getString("damage_type").orElseThrow()
        );
        Assertions.assertTrue(payload.getBoolean("indirect_damage").orElseThrow());
        Assertions.assertEquals(
            PaperEntityBreakListener.HANGING_SOURCE_EVENT,
            payload.getString("source_event").orElseThrow()
        );
        Assertions.assertEquals(
            "explosion",
            payload.getCompoundOrEmpty("hanging").getString("remove_cause").orElseThrow()
        );
    }

    @Test
    void testCancelledBreaksAreDropped() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var generic = new PaperGenericEntityBreakEventFixture(
            entity(world, EntityType.ARMOR_STAND, ENTITY_ID, 1, 2, 3),
            entity(world, EntityType.ZOMBIE, BREAKER_ID, 2, 2, 3),
            damageSource(DamageType.MOB_ATTACK, false),
            PaperGenericEntityBreakEventFixture.RemoveCause.ENTITY
        );
        generic.setCancelled(true);

        var hangingEntity = hanging(world, ENTITY_ID, 3, 4, 5);
        var hangingRemover = player(world);
        var hangingSource = damageSource(DamageType.PLAYER_ATTACK, false);
        var hanging = Mockito.mock(HangingBreakByEntityEvent.class);
        Mockito.when(hanging.getEntity()).thenReturn(hangingEntity);
        Mockito.when(hanging.getRemover()).thenReturn(hangingRemover);
        Mockito.when(hanging.getCause()).thenReturn(HangingBreakEvent.RemoveCause.ENTITY);
        Mockito.when(hanging.getDamageSource()).thenReturn(hangingSource);
        Mockito.when(hanging.isCancelled()).thenReturn(true);

        listener.captureGeneric(generic);
        listener.finalizeGeneric(generic);
        listener.captureHanging(hanging);
        listener.finalizeHanging(hanging);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static PaperEntityBreakListener listener(PaperBlockEventTestSupport.RecordingApi api) {
        return PaperEntityBreakListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static Entity entity(
        World world,
        EntityType type,
        UUID id,
        double x,
        double y,
        double z
    ) {
        var entity = Mockito.mock(Entity.class);
        Mockito.when(entity.getUniqueId()).thenReturn(id);
        Mockito.when(entity.getType()).thenReturn(type);
        Mockito.when(entity.getWorld()).thenReturn(world);
        Mockito.when(entity.getLocation()).thenReturn(new Location(world, x, y, z));
        return entity;
    }

    private static Hanging hanging(World world, UUID id, double x, double y, double z) {
        var hanging = Mockito.mock(Hanging.class);
        Mockito.when(hanging.getUniqueId()).thenReturn(id);
        Mockito.when(hanging.getType()).thenReturn(EntityType.ITEM_FRAME);
        Mockito.when(hanging.getWorld()).thenReturn(world);
        Mockito.when(hanging.getLocation()).thenReturn(new Location(world, x, y, z));
        return hanging;
    }

    private static Player player(World world) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(BREAKER_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);
        Mockito.when(player.getWorld()).thenReturn(world);
        Mockito.when(player.getLocation()).thenReturn(new Location(world, 16, 70, 10));
        return player;
    }

    private static DamageSource damageSource(DamageType type, boolean indirect) {
        var source = Mockito.mock(DamageSource.class);
        Mockito.when(source.getDamageType()).thenReturn(type);
        Mockito.when(source.isIndirect()).thenReturn(indirect);
        return source;
    }

    private static EventSubmission onlySubmission(PaperBlockEventTestSupport.RecordingApi api) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.remove();
    }

    private static void assertCommon(EventSubmission submission, BlockPosition position) {
        Assertions.assertEquals(PaperEntityBreakListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(PaperBlockEventTestSupport.SERVER_KEY, submission.serverKey());
        Assertions.assertEquals(Key.key("example", "world"), submission.worldKey());
        Assertions.assertEquals(position, submission.position());
    }

    private static void assertEntity(CompoundTag entity, UUID id, String type) {
        Assertions.assertEquals(id.toString(), entity.getString("uuid").orElseThrow());
        Assertions.assertEquals(type, entity.getString("type").orElseThrow());
        Assertions.assertEquals("example:world", entity.getString("world").orElseThrow());
    }
}
