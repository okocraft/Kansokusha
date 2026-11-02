package net.okocraft.kansokusha.testmod;

import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Two vanilla hopper pulls, followed by an empty source that must not create another move. */
final class NativeContainerMoveFixture {
    private static final BlockPos HOPPER = new BlockPos(262, 80, 208);
    private static final BlockPos SOURCE = HOPPER.above();
    private static final BlockPos CHAIN_SOURCE = new BlockPos(266, 80, 208);
    private static final BlockPos CHAIN_DESTINATION = CHAIN_SOURCE.east();
    private static MinecraftServer server;

    static void start(MinecraftServer minecraftServer) {
        server = minecraftServer;
        var level = server.overworld();
        level.setChunkForced(HOPPER.getX() >> 4, HOPPER.getZ() >> 4, true);
        level.setBlock(HOPPER.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(HOPPER, Blocks.HOPPER.defaultBlockState(), 3);
        level.setBlock(SOURCE, Blocks.CHEST.defaultBlockState(), 3);
        ((ChestBlockEntity) level.getBlockEntity(SOURCE)).setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
        level.setBlock(CHAIN_SOURCE, Blocks.HOPPER.defaultBlockState()
            .setValue(HopperBlock.FACING, Direction.EAST), 3);
        level.setBlock(CHAIN_DESTINATION, Blocks.HOPPER.defaultBlockState(), 3);
        ((HopperBlockEntity) level.getBlockEntity(CHAIN_SOURCE)).setItem(0, new ItemStack(Items.EMERALD));
    }

    static void verifyMovement() {
        var level = server.overworld();
        var source = (ChestBlockEntity) level.getBlockEntity(SOURCE);
        var hopper = (HopperBlockEntity) level.getBlockEntity(HOPPER);
        int transferred = 0;
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            var item = hopper.getItem(slot);
            if (item.is(Items.GOLD_INGOT)) transferred += item.getCount();
        }
        if (!source.getItem(0).isEmpty() || transferred != 2)
            throw new AssertionError("Vanilla hopper did not commit both fixture pulls");
        var chainSource = (HopperBlockEntity) level.getBlockEntity(CHAIN_SOURCE);
        var chainDestination = (HopperBlockEntity) level.getBlockEntity(CHAIN_DESTINATION);
        if (!chainSource.getItem(0).isEmpty() || !chainDestination.getItem(0).is(Items.EMERALD))
            throw new AssertionError("Vanilla hopper chain did not commit its fixture move");
    }

    static void verifyStored(EventSearchBackend backend) {
        var page = backend.search(new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
            SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
            new SearchRequest.Constraints(Set.of(Key.key("kansokusha:container_transfer"))),
            Optional.empty(), 100)).join();
        var fixtureRows = page.events().stream().filter(row -> row.position()
            .equals(Optional.of(new BlockPosition(262, 80, 208)))
            && row.targetType().equals(Optional.of(Key.key("minecraft:gold_ingot")))).toList();
        if (fixtureRows.size() != 1)
            throw new AssertionError("Expected one aggregated route and no empty-source move, found " + fixtureRows.size());
        if (page.events().stream().anyMatch(row -> row.position()
            .equals(Optional.of(new BlockPosition(266, 80, 208)))
            && row.targetType().equals(Optional.of(Key.key("minecraft:emerald")))))
            throw new AssertionError("Hopper-chain move was flushed before its five-minute window");
        var payload = JsonParser.parseString(new String(fixtureRows.getFirst().payload().orElseThrow()
            .bytes().copyBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        var item = payload.getAsJsonObject("item");
        if (!payload.get("from_world").getAsString().equals("minecraft:overworld")
            || !payload.get("to_world").getAsString().equals("minecraft:overworld")
            || !payload.get("from").getAsString().equals("262, 81, 208")
            || !payload.get("to").getAsString().equals("262, 80, 208")
            || !payload.get("initiator_role").getAsString().equals("destination")
            || payload.get("attempt_count").getAsInt() != 2
            || payload.get("item_amount").getAsLong() != 2
            || !item.get("item").getAsString().equals("minecraft:gold_ingot")
            || item.get("amount").getAsLong() != 2
            || !item.get("components").getAsString().equals(new ItemStack(Items.GOLD_INGOT).getComponents().toString()))
            throw new AssertionError("Committed hopper aggregate differs: " + payload);
    }
}
