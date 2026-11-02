package net.okocraft.kansokusha.testmod;

import com.mojang.authlib.GameProfile;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.neoforge.NativeEntityRecorder;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Native server fixture for committed placement and frame changes, and canceled interaction exclusion. */
final class NativeEntityFixture {
    private static final UUID PLAYER = UUID.fromString("ec6b9802-b03e-45cc-b52c-d352dd02b9aa");
    private static final UUID SECOND_PLAYER = UUID.fromString("17449321-c214-45e2-b32f-c7339c540074");
    private static final BlockPos PLACEMENT = new BlockPos(230, 80, 208);
    private static final BlockPos FRAME = new BlockPos(232, 80, 208);
    private static final BlockPos CANCELLED = new BlockPos(234, 80, 208);
    private static final BlockPos BROKEN = new BlockPos(236, 80, 208);
    private static ServerLevel fixtureLevel;
    private static ItemFrame breakFrame;
    private static PacketProbePlayer breakPlayer;
    private static int ticks;
    private static boolean broken;
    private static boolean damageAccepted;
    private static boolean removedBeforeDamage;

    static void start(MinecraftServer server) {
        ServerLevel level = server.overworld();
        fixtureLevel = level;
        NeoForge.EVENT_BUS.addListener(NativeEntityFixture::tick);
        var player = new PacketProbePlayer(level, new GameProfile(PLAYER, "KansokushaEntity"));
        player.setPos(228.5, 81, 208.5);
        level.getChunkAt(PLACEMENT);
        level.setChunkForced(PLACEMENT.getX() >> 4, PLACEMENT.getZ() >> 4, true);
        level.setBlock(PLACEMENT, Blocks.STONE.defaultBlockState(), 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ARMOR_STAND));
        var result = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(PLACEMENT), Direction.UP, PLACEMENT, false));
        if (!result.consumesAction()) throw new AssertionError("Armor stand placement did not commit");

        level.setBlock(FRAME.north(), Blocks.STONE.defaultBlockState(), 3);
        var frame = new ItemFrame(level, FRAME, Direction.SOUTH);
        if (!level.addFreshEntity(frame)) throw new AssertionError("Frame setup failed");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND));
        var frameEvent = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, frame, Vec3.ZERO);
        NeoForge.EVENT_BUS.post(frameEvent);
        NativeEntityRecorder.beginEntityInteraction(player, frame, InteractionHand.MAIN_HAND);
        var frameResult = frameEvent.isCanceled() ? net.minecraft.world.InteractionResult.PASS
            : frame.interact(player, InteractionHand.MAIN_HAND, Vec3.ZERO);
        NativeEntityRecorder.endEntityInteraction();
        if (!frameResult.consumesAction())
            throw new AssertionError("Frame item change did not commit");

        var second = new PacketProbePlayer(level, new GameProfile(SECOND_PLAYER, "KansokushaRotate"));
        second.setPos(232.5, 80, 208.5);
        NativeEntityRecorder.beginEntityInteraction(second, frame, InteractionHand.MAIN_HAND);
        var rotationResult = frame.interact(second, InteractionHand.MAIN_HAND, Vec3.ZERO);
        NativeEntityRecorder.endEntityInteraction();
        if (!rotationResult.consumesAction() || frame.getRotation() != 1)
            throw new AssertionError("Second player did not rotate frame");

        level.setBlock(CANCELLED.north(), Blocks.STONE.defaultBlockState(), 3);
        var deniedFrame = new ItemFrame(level, CANCELLED, Direction.SOUTH);
        if (!level.addFreshEntity(deniedFrame)) throw new AssertionError("Canceled frame setup failed");
        var canceled = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, deniedFrame, Vec3.ZERO);
        canceled.setCanceled(true);
        NeoForge.EVENT_BUS.post(canceled);
        NativeEntityRecorder.beginEntityInteraction(player, deniedFrame, InteractionHand.MAIN_HAND);
        NativeEntityRecorder.endEntityInteraction();
        if (!deniedFrame.getItem().isEmpty()) throw new AssertionError("Canceled frame changed state");

        level.setBlock(BROKEN.north(), Blocks.STONE.defaultBlockState(), 3);
        breakPlayer = player;
    }

    private static void tick(ServerTickEvent.Post event) {
        if (++ticks != 200) return;
        breakFrame = new ItemFrame(fixtureLevel, BROKEN, Direction.SOUTH);
        if (!fixtureLevel.addFreshEntity(breakFrame))
            throw new AssertionError("Break frame setup failed at action time");
        removedBeforeDamage = breakFrame.isRemoved();
        damageAccepted = breakFrame.hurtServer(fixtureLevel,
            fixtureLevel.damageSources().playerAttack(breakPlayer), 1);
        broken = damageAccepted && breakFrame.isRemoved();
    }

    static void verify(EventSearchBackend backend) {
        if (!broken) throw new AssertionError("Player damage did not remove frame; damageAccepted="
            + damageAccepted + "; removedBeforeDamage=" + removedBeforeDamage
            + "; removalReason=" + breakFrame.getRemovalReason());
        var stands = fixtureLevel.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
            net.minecraft.world.phys.AABB.ofSize(Vec3.atCenterOf(PLACEMENT), 64, 64, 64))
            .stream().map(entity -> entity.blockPosition().toString()).toList();
        var placement = rows(backend, "entity_place");
        if (placement.events().stream().noneMatch(row -> row.actor()
            .filter(actor -> actor instanceof PlayerActor player && player.uniqueId().equals(PLAYER)).isPresent()
            && row.position().equals(Optional.of(new BlockPosition(230, 81, 208)))
            && row.targetType().equals(Optional.of(Key.key("minecraft:armor_stand")))))
            throw new AssertionError("Committed player armor stand placement was not recorded; stands=" + stands + "; place="
                + placement.events().stream().map(row -> row.actor() + "|" + row.targetType() + "|" + row.position()).toList()
                + "; spawn=" + rows(backend, "entity_spawn").events().stream()
                .map(row -> row.actor() + "|" + row.targetType() + "|" + row.position()).toList());

        var changes = rows(backend, "item_frame_change").events();
        var committed = changes.stream().filter(row -> row.actor()
            .filter(actor -> actor instanceof PlayerActor player
                && (player.uniqueId().equals(PLAYER) || player.uniqueId().equals(SECOND_PLAYER))).isPresent()
            && row.position().equals(Optional.of(new BlockPosition(232, 80, 208)))
            && row.targetType().equals(Optional.of(Key.key("minecraft:item_frame")))).toList();
        if (committed.size() != 2 || committed.stream().noneMatch(row -> row.actor()
            .filter(actor -> actor instanceof PlayerActor player && player.uniqueId().equals(PLAYER)).isPresent())
            || committed.stream().noneMatch(row -> row.actor()
            .filter(actor -> actor instanceof PlayerActor player && player.uniqueId().equals(SECOND_PLAYER)).isPresent()))
            throw new AssertionError("Two same-tick frame changes lost their actors: " + committed.size());
        if (changes.stream().anyMatch(row -> row.position().equals(Optional.of(new BlockPosition(234, 80, 208)))))
            throw new AssertionError("Canceled frame interaction was recorded");
        var breaks = rows(backend, "entity_break").events();
        if (breaks.stream().noneMatch(row -> row.position()
            .equals(Optional.of(new BlockPosition(236, 80, 208)))
            && row.actor().filter(actor -> actor instanceof PlayerActor player
                && player.uniqueId().equals(PLAYER)).isPresent()
            && row.targetType().equals(Optional.of(Key.key("minecraft:item_frame")))))
            throw new AssertionError("Committed hanging break lost player attribution; rows="
                + breaks.stream().map(row -> row.actor() + "|" + row.targetType() + "|" + row.position()).toList()
                + "; removed=" + breakFrame.getRemovalReason());
    }

    private static net.okocraft.kansokusha.common.search.SearchPage rows(EventSearchBackend backend, String type) {
        return backend.search(new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
            SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
            new SearchRequest.Constraints(Set.of(Key.key("kansokusha", type))), Optional.empty(), 100)).join();
    }
}
