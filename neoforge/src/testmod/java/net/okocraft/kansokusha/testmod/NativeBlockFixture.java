package net.okocraft.kansokusha.testmod;

import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.mojang.authlib.GameProfile;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

final class NativeBlockFixture {
    private static final BlockPos TNT = new BlockPos(300, 80, 208);
    private static final BlockPos SPONGE = new BlockPos(310, 80, 208);
    private static final BlockPos WATER = SPONGE.east();
    private static final BlockPos PISTON = new BlockPos(324, 80, 208);
    private static final BlockPos POWER = PISTON.west();
    private static final BlockPos CROP = new BlockPos(330, 80, 208);
    private static final BlockPos LEAF = new BlockPos(332, 80, 208);
    private static final BlockPos FLOW = new BlockPos(340, 80, 208);
    private static final BlockPos TREE = new BlockPos(360, 80, 208);
    private static final BlockPos CANCEL_TREE = new BlockPos(370, 80, 208);
    private static final BlockPos SUPPORT_LOSS = new BlockPos(404, 80, 208);
    private static final BlockPos DIRECT_BREAK = new BlockPos(406, 80, 208);
    private static final BlockPos WEATHER_SNOW = new BlockPos(420, 80, 216);
    private static final BlockPos WEATHER_ICE = new BlockPos(422, 80, 216);
    private static final java.util.UUID TREE_ACTOR = java.util.UUID.fromString("e62c0823-8fe7-4b3c-aaf5-1e53f254d823");
    private static final java.util.UUID CANCEL_ACTOR = java.util.UUID.fromString("2a416eb3-151d-423b-9d84-579ef7ea443b");
    private static MinecraftServer server;
    private static int cancelledTreeAttempts;

    private NativeBlockFixture() { }

    static void start(MinecraftServer server) {
        NativeBlockFixture.server = server;
        var level = server.overworld();
        level.getChunkAt(TNT);
        level.getChunkAt(SPONGE);
        level.setBlock(TNT, Blocks.TNT.defaultBlockState(), 3);
        if (!TntBlock.prime(level, TNT))
            throw new AssertionError("Native TNT operation was not accepted");
        level.setBlock(WATER, Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(SPONGE, Blocks.SPONGE.defaultBlockState(), 3);
        if (level.getBlockState(WATER).getBlock() != Blocks.AIR
            || level.getBlockState(SPONGE).getBlock() != Blocks.WET_SPONGE)
            throw new AssertionError("Native sponge operation did not absorb adjacent water");
        level.setChunkForced(PISTON.getX() >> 4, PISTON.getZ() >> 4, true);
        level.setBlock(POWER, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(PISTON.east(2), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(PISTON.east(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(PISTON, Blocks.STICKY_PISTON.defaultBlockState()
            .setValue(PistonBaseBlock.FACING, Direction.EAST), 3);
        level.getChunkAt(CROP);
        level.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState(), 3);
        level.setBlock(CROP, Blocks.WHEAT.defaultBlockState(), 3);
        if (!BoneMealItem.applyBonemeal(new ItemStack(Items.BONE_MEAL), level, CROP, null)
            || level.getBlockState(CROP).getValue(CropBlock.AGE) == 0)
            throw new AssertionError("Native bone meal did not advance wheat");
        level.setBlock(LEAF, Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.DISTANCE, 7).setValue(LeavesBlock.PERSISTENT, false), 2);
        level.getBlockState(LEAF).randomTick(level, LEAF, net.minecraft.util.RandomSource.create(77L));
        if (!level.getBlockState(LEAF).isAir())
            throw new AssertionError("Native leaf random tick did not decay unsupported leaves");
        level.setChunkForced(FLOW.getX() >> 4, FLOW.getZ() >> 4, true);
        for (var base : new BlockPos[] { FLOW, FLOW.east() }) {
            level.setBlock(base.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(base.north(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(base.south(), Blocks.STONE.defaultBlockState(), 3);
        }
        level.setBlock(FLOW.west(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(FLOW.east(2), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(FLOW, Blocks.WATER.defaultBlockState(), 3);
        level.getChunkAt(TREE);
        level.setBlock(TREE.below(), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(TREE, Blocks.OAK_SAPLING.defaultBlockState(), 3);
        var treePlayer = new PacketProbePlayer(level, new GameProfile(TREE_ACTOR, "KansoTree"));
        treePlayer.setPos(TREE.getX() + 0.5, TREE.getY(), TREE.getZ() + 0.5);
        treePlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE_MEAL, 64));
        var context = new UseOnContext(treePlayer, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(TREE), Direction.UP, TREE, false));
        for (int attempt = 0; attempt < 64 && level.getBlockState(TREE).is(Blocks.OAK_SAPLING); attempt++)
            treePlayer.getItemInHand(InteractionHand.MAIN_HAND).useOn(context);
        if (level.getBlockState(TREE).is(Blocks.OAK_SAPLING)
            || level.getBlockState(TREE.above()).isAir())
            throw new AssertionError("Native oak sapling did not grow into a multi-block tree");
        level.getChunkAt(CANCEL_TREE);
        level.setBlock(CANCEL_TREE.below(), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(CANCEL_TREE, Blocks.OAK_SAPLING.defaultBlockState()
            .setValue(SaplingBlock.STAGE, 1), 3);
        cancelledTreeAttempts = 0;
        NeoForge.EVENT_BUS.addListener((BlockEvent.EntityMultiPlaceEvent placement) -> {
            if (placement.getEntity() != null && placement.getEntity().getUUID().equals(CANCEL_ACTOR)) {
                placement.setCanceled(true);
                cancelledTreeAttempts++;
            }
        });
        var deniedPlayer = new PacketProbePlayer(level, new GameProfile(CANCEL_ACTOR, "KansoCancel"));
        deniedPlayer.setPos(CANCEL_TREE.getX() + 0.5, CANCEL_TREE.getY(), CANCEL_TREE.getZ() + 0.5);
        deniedPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE_MEAL, 64));
        var deniedContext = new UseOnContext(deniedPlayer, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(CANCEL_TREE), Direction.UP, CANCEL_TREE, false));
        for (int attempt = 0; attempt < 64 && cancelledTreeAttempts == 0; attempt++)
            deniedPlayer.getItemInHand(InteractionHand.MAIN_HAND).useOn(deniedContext);
        if (cancelledTreeAttempts == 0 || !level.getBlockState(CANCEL_TREE).is(Blocks.OAK_SAPLING))
            throw new AssertionError("Native multi-block placement cancellation did not restore the sapling");
        level.getChunkAt(SUPPORT_LOSS);
        level.setBlock(SUPPORT_LOSS.west(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(SUPPORT_LOSS, Blocks.WALL_TORCH.defaultBlockState()
            .setValue(WallTorchBlock.FACING, Direction.EAST), 3);
        if (!level.getBlockState(SUPPORT_LOSS).is(Blocks.WALL_TORCH))
            throw new AssertionError("Supported wall torch setup failed");
        level.destroyBlock(SUPPORT_LOSS.west(), false);
        if (!level.getBlockState(SUPPORT_LOSS).isAir())
            throw new AssertionError("Wall torch survived removal of its support");
        level.setBlock(DIRECT_BREAK.west(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(DIRECT_BREAK, Blocks.WALL_TORCH.defaultBlockState()
            .setValue(WallTorchBlock.FACING, Direction.EAST), 3);
        if (!level.getBlockState(DIRECT_BREAK).is(Blocks.WALL_TORCH)
            || !level.destroyBlock(DIRECT_BREAK, false))
            throw new AssertionError("Direct wall torch break setup failed");
        level.setChunkForced(WEATHER_SNOW.getX() >> 4, WEATHER_SNOW.getZ() >> 4, true);
    }

    private static void precipitation(MinecraftServer server) {
        var level = server.overworld();
        var snowChunk = level.getChunkAt(WEATHER_SNOW);
        var cold = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.SNOWY_PLAINS);
        snowChunk.fillBiomesFromNoise((x, y, z, sampler) -> cold, null);
        if (!level.getBiome(WEATHER_SNOW.above()).is(Biomes.SNOWY_PLAINS))
            throw new AssertionError("Fixture cold biome was not available after chunk setup");
        level.setBlock(WEATHER_SNOW, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(WEATHER_ICE, Blocks.WATER.defaultBlockState(), 3);
        var rain = level.getRainLevel(1.0F);
        level.setRainLevel(1.0F);
        try {
            level.tickPrecipitation(WEATHER_SNOW);
            level.tickPrecipitation(WEATHER_ICE);
        } finally {
            level.setRainLevel(rain);
        }
        if (!level.getBlockState(WEATHER_SNOW.above()).is(Blocks.SNOW)
            || !level.getBlockState(WEATHER_ICE).is(Blocks.ICE))
            throw new AssertionError("Native precipitation did not form snow and ice: snow="
                + level.getBlockState(WEATHER_SNOW.above()) + " ice=" + level.getBlockState(WEATHER_ICE)
                + " snowTop=" + level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    WEATHER_SNOW) + " iceTop=" + level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, WEATHER_ICE)
                + " biome=" + level.getBiome(WEATHER_SNOW));
    }

    static void advance(MinecraftServer server, int tick) {
        var level = server.overworld();
        if (tick == 80) {
            precipitation(server);
            level.setBlock(POWER, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        }
        if (tick == 105 && !level.getBlockState(PISTON.east(2)).is(Blocks.STONE))
            throw new AssertionError("First piston extension did not move the stone: piston="
                + level.getBlockState(PISTON) + " east=" + level.getBlockState(PISTON.east())
                + " east2=" + level.getBlockState(PISTON.east(2))
                + " power=" + level.getBlockState(POWER));
        if (tick == 110) level.setBlock(POWER, Blocks.AIR.defaultBlockState(), 3);
        if (tick == 135 && !level.getBlockState(PISTON.east()).is(Blocks.STONE))
            throw new AssertionError("Sticky piston did not return the stone");
        if (tick == 140) level.setBlock(POWER, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        if (tick == 165 && !level.getBlockState(PISTON.east(2)).is(Blocks.STONE))
            throw new AssertionError("Second piston extension did not move the stone");
    }

    static void verify(EventSearchBackend backend) {
        assertRecord(backend, "tnt_prime", TNT, "minecraft:tnt");
        assertRecord(backend, "sponge_absorb", WATER, "minecraft:water");
        assertRecord(backend, "block_fertilize", CROP, "minecraft:wheat");
        assertRecord(backend, "natural_block_change", LEAF, "minecraft:oak_leaves");
        if (!server.overworld().getBlockState(FLOW.east()).getFluidState()
            .is(net.minecraft.tags.FluidTags.WATER))
            throw new AssertionError("Native fixture water did not flow: source="
                + server.overworld().getBlockState(FLOW) + " destination="
                + server.overworld().getBlockState(FLOW.east()));
        assertRecord(backend, "fluid_change", FLOW.east(), "minecraft:water");
        assertRecord(backend, "natural_block_change", SUPPORT_LOSS, "minecraft:wall_torch");
        var directBreakRows = backend.search(new SearchRequest(
            new SearchQuery(SearchQuery.Conditions.empty(), SearchQuery.Conditions.empty(),
                SearchQuery.Order.NEWEST, OptionalInt.empty()),
            new SearchRequest.Constraints(Set.of(Key.key("kansokusha:natural_block_change"))),
            Optional.empty(), 1000)).join().events();
        if (directBreakRows.stream().anyMatch(row -> row.position().equals(Optional.of(
            new BlockPosition(DIRECT_BREAK.getX(), DIRECT_BREAK.getY(), DIRECT_BREAK.getZ())))))
            throw new AssertionError("Direct block destruction was misclassified as natural support loss");
        assertRecord(backend, "natural_block_change", WEATHER_SNOW.above(), "minecraft:snow");
        assertRecord(backend, "natural_block_change", WEATHER_ICE, "minecraft:water");
        var fertilizer = backend.search(new SearchRequest(
            new SearchQuery(SearchQuery.Conditions.empty(), SearchQuery.Conditions.empty(),
                SearchQuery.Order.NEWEST, OptionalInt.empty()),
            new SearchRequest.Constraints(Set.of(Key.key("kansokusha:block_fertilize"))),
            Optional.empty(), 1000)).join().events().stream().filter(row -> row.actor()
                .filter(actor -> actor instanceof net.okocraft.kansokusha.api.actor.PlayerActor player
                    && player.uniqueId().equals(TREE_ACTOR)).isPresent()).toList();
        if (fertilizer.size() < 2 || fertilizer.stream().map(row -> row.position().orElseThrow()).distinct().count() < 2)
            throw new AssertionError("Bone meal tree changes were not individually attributed to the player");
        for (var eventType : new String[] { "block_fertilize", "block_place" }) {
            var deniedPage = backend.search(new SearchRequest(
                new SearchQuery(SearchQuery.Conditions.empty(), SearchQuery.Conditions.empty(),
                    SearchQuery.Order.NEWEST, OptionalInt.empty()),
                new SearchRequest.Constraints(Set.of(Key.key("kansokusha:" + eventType))),
                Optional.empty(), 1000)).join();
            if (deniedPage.events().stream().anyMatch(row -> row.actor()
                .filter(actor -> actor instanceof net.okocraft.kansokusha.api.actor.PlayerActor player
                    && player.uniqueId().equals(CANCEL_ACTOR)).isPresent()))
                throw new AssertionError("Canceled bone meal tree was recorded as " + eventType);
        }
    }

    static void verifyPiston(EventSearchBackend backend) {
        var type = Key.key("kansokusha:piston_move");
        var page = backend.search(new SearchRequest(
            new SearchQuery(SearchQuery.Conditions.empty(), SearchQuery.Conditions.empty(),
                SearchQuery.Order.NEWEST, OptionalInt.empty()),
            new SearchRequest.Constraints(Set.of(type)), Optional.empty(), 1000)).join();
        var expected = Optional.of(new BlockPosition(PISTON.getX() + 2, PISTON.getY(), PISTON.getZ()));
        if (page.events().stream().noneMatch(row -> row.position().equals(expected)
            && row.targetType().equals(Optional.of(Key.key("minecraft:stone")))
            && new String(row.payload().orElseThrow().bytes().copyBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"attempt_count\":2")))
            throw new AssertionError("Two real sticky piston moves were not aggregated at the destination; "
                + "east=" + server.overworld().getBlockState(PISTON.east())
                + " east2=" + server.overworld().getBlockState(PISTON.east(2))
                + " rows=" + page.events().stream().map(row -> row.position() + "/"
                    + row.targetType() + "/" + new String(row.payload().orElseThrow().bytes().copyBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)).toList());
    }

    private static void assertRecord(EventSearchBackend backend, String name, BlockPos position, String target) {
        var type = Key.key("kansokusha:" + name);
        var page = backend.search(new SearchRequest(
            new SearchQuery(SearchQuery.Conditions.empty(), SearchQuery.Conditions.empty(),
                SearchQuery.Order.NEWEST, OptionalInt.empty()),
            new SearchRequest.Constraints(Set.of(type)), Optional.empty(), 100)).join();
        if (page.events().stream().noneMatch(row -> row.position().equals(Optional.of(
                new BlockPosition(position.getX(), position.getY(), position.getZ())))
            && row.targetType().equals(Optional.of(Key.key(target)))))
            throw new AssertionError("Native " + name + " was not saved at " + position
                + "; rows=" + page.events().stream().map(row -> row.position() + "/" + row.targetType()).toList());
    }
}
