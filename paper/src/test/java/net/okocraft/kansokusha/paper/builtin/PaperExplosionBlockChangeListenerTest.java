package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.ExplosionResult;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

class PaperExplosionBlockChangeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testBlockExplosionRecordsEachListedBlockOnceWithOneTimestamp() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var clock = Mockito.mock(Clock.class);
        Mockito.when(clock.instant()).thenReturn(OCCURRED_AT, OCCURRED_AT.plusSeconds(1));
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            clock
        );
        var world = world(true);
        var sourceBlock = PaperBlockEventTestSupport.block(
            world, 5, 64, 5, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var sourceState = PaperBlockEventTestSupport.state(
            world,
            sourceBlock,
            5,
            64,
            5,
            Blocks.RESPAWN_ANCHOR.defaultBlockState()
        );
        var stone = PaperBlockEventTestSupport.block(
            world, 10, 64, 10, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        var ore = PaperBlockEventTestSupport.block(
            world, 12, 64, 10, Blocks.DIAMOND_ORE.defaultBlockState(), Material.DIAMOND_ORE
        );
        var air = PaperBlockEventTestSupport.block(
            world, 13, 64, 10, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var event = Mockito.mock(BlockExplodeEvent.class);
        Mockito.when(event.getExplodedBlockState()).thenReturn(sourceState);
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(stone, ore, stone, air)));
        Mockito.when(event.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(2, api.submissions.size());
        var byX = submissionsByX(api);
        Assertions.assertEquals(OCCURRED_AT, byX.get(10).occurredAt());
        Assertions.assertEquals(OCCURRED_AT, byX.get(12).occurredAt());
        Assertions.assertEquals(new BlockActor(Key.key("minecraft", "respawn_anchor")), byX.get(10).actor());
        Assertions.assertEquals(Key.key("minecraft", "stone"), byX.get(10).targetType());
        Assertions.assertEquals(Key.key("minecraft", "diamond_ore"), byX.get(12).targetType());

        var stonePayload = PaperPayloadNbtCodec.decode(byX.get(10).payload());
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(Blocks.STONE.defaultBlockState().asBlockData()),
            stonePayload.get("pre_state")
        );
        Assertions.assertEquals("block", stonePayload.getString("source_kind").orElseThrow());
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(
                Blocks.RESPAWN_ANCHOR.defaultBlockState().asBlockData()
            ),
            stonePayload.get("source_block_state")
        );
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(
                Blocks.DIAMOND_ORE.defaultBlockState().asBlockData()
            ),
            PaperPayloadNbtCodec.decode(byX.get(12).payload()).get("pre_state")
        );
    }

    @Test
    void testExplodedTntIsLeftToTntPrimeWhenTntExplodes() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );

        PaperListenerTestSupport.fire(listener, tntExplosion(world(true), ghast()));

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(new BlockPosition(1, 64, 0), api.submissions.remove().position());
    }

    @Test
    void testExplodedTntIsRecordedWhenTntDoesNotExplode() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );

        PaperListenerTestSupport.fire(listener, tntExplosion(world(false), ghast()));

        Assertions.assertEquals(2, api.submissions.size());
    }

    @Test
    void testTntDestroyedByEnderDragonIsRecorded() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);

        PaperListenerTestSupport.fire(listener, tntExplosion(world(true), dragon));

        Assertions.assertEquals(2, api.submissions.size());
    }

    @Test
    void testTriggerBlockExplosionIsNotRecorded() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var event = tntExplosion(world(true), ghast());
        Mockito.when(event.getExplosionResult()).thenReturn(ExplosionResult.TRIGGER_BLOCK);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testEntityExplosionKeepsProjectileShooterAndOwner() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var destroyed = PaperBlockEventTestSupport.block(
            world, 20, 70, 20, Blocks.OAK_PLANKS.defaultBlockState(), Material.OAK_PLANKS
        );
        var shooterId = UUID.fromString("123e4567-e89b-12d3-a456-426614174010");
        var ownerId = UUID.fromString("123e4567-e89b-12d3-a456-426614174011");
        var projectileId = UUID.fromString("123e4567-e89b-12d3-a456-426614174012");
        var shooter = Mockito.mock(Player.class);
        Mockito.when(shooter.getUniqueId()).thenReturn(shooterId);
        Mockito.when(shooter.getType()).thenReturn(EntityType.PLAYER);
        var projectile = Mockito.mock(Projectile.class);
        Mockito.when(projectile.getUniqueId()).thenReturn(projectileId);
        Mockito.when(projectile.getType()).thenReturn(EntityType.ARROW);
        Mockito.when(projectile.getShooter()).thenReturn(shooter);
        Mockito.when(projectile.getOwnerUniqueId()).thenReturn(ownerId);
        var event = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(event.getEntity()).thenReturn(projectile);
        Mockito.when(event.getLocation()).thenReturn(new Location(world, 20.5, 70.5, 20.5));
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(destroyed)));

        PaperListenerTestSupport.fire(listener, event);

        var submission = api.submissions.remove();
        Assertions.assertEquals(new EntityActor(projectileId, Key.key("minecraft", "arrow")), submission.actor());
        Assertions.assertEquals(Key.key("minecraft", "oak_planks"), submission.targetType());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            projectileId.toString(),
            payload.getString("actor_entity_uuid").orElseThrow()
        );
        Assertions.assertEquals(
            shooterId.toString(),
            payload.getString("shooter_entity_uuid").orElseThrow()
        );
        Assertions.assertEquals(
            ownerId.toString(),
            payload.getString("owner_uuid").orElseThrow()
        );
        Assertions.assertEquals("entity", payload.getString("source_kind").orElseThrow());
    }

    @Test
    void testNonPlayerProjectileIsRecordedAsDirectActor() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var destroyed = PaperBlockEventTestSupport.block(
            world, 30, 70, 30, Blocks.NETHERRACK.defaultBlockState(), Material.NETHERRACK
        );
        var ghastId = UUID.fromString("123e4567-e89b-12d3-a456-426614174013");
        var projectileId = UUID.fromString("123e4567-e89b-12d3-a456-426614174014");
        var ghast = Mockito.mock(Ghast.class);
        Mockito.when(ghast.getUniqueId()).thenReturn(ghastId);
        Mockito.when(ghast.getType()).thenReturn(EntityType.GHAST);
        var projectile = Mockito.mock(Projectile.class);
        Mockito.when(projectile.getUniqueId()).thenReturn(projectileId);
        Mockito.when(projectile.getType()).thenReturn(EntityType.FIREBALL);
        Mockito.when(projectile.getShooter()).thenReturn(ghast);
        Mockito.when(projectile.getOwnerUniqueId()).thenReturn(ghastId);
        var event = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(event.getEntity()).thenReturn(projectile);
        Mockito.when(event.getLocation()).thenReturn(new Location(world, 30.5, 70.5, 30.5));
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(destroyed)));

        PaperListenerTestSupport.fire(listener, event);

        var submission = api.submissions.remove();
        Assertions.assertEquals(new EntityActor(projectileId, Key.key("minecraft", "fireball")), submission.actor());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            ghastId.toString(),
            payload.getString("shooter_entity_uuid").orElseThrow()
        );
        Assertions.assertEquals(
            ghastId.toString(),
            payload.getString("owner_uuid").orElseThrow()
        );
    }

    @Test
    void testCancelledExplosionIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var event = tntExplosion(world(false), ghast());
        Mockito.when(event.isCancelled()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static World world(boolean tntExplodes) {
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(tntExplodes);
        return world;
    }

    private static Ghast ghast() {
        var entity = Mockito.mock(Ghast.class);
        Mockito.when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(entity.getType()).thenReturn(EntityType.GHAST);
        return entity;
    }

    private static EntityExplodeEvent tntExplosion(World world, org.bukkit.entity.Entity entity) {
        var tnt = PaperBlockEventTestSupport.block(
            world, 0, 64, 0, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var stone = PaperBlockEventTestSupport.block(
            world, 1, 64, 0, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        var event = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(event.getEntity()).thenReturn(entity);
        Mockito.when(event.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.<Block>of(tnt, stone)));
        return event;
    }

    private static HashMap<Integer, EventSubmission> submissionsByX(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        var result = new HashMap<Integer, EventSubmission>();
        for (var submission : api.submissions) {
            Assertions.assertNull(result.put(submission.position().x(), submission));
        }
        return result;
    }
}
