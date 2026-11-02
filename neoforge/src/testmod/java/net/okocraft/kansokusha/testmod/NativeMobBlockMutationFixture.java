package net.okocraft.kansokusha.testmod;

import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** A real snow golem write and the mob-griefing denial, searched after storage flush. */
final class NativeMobBlockMutationFixture {
    private static final BlockPos FORMED = new BlockPos(242, 81, 208);
    private static final BlockPos DENIED = new BlockPos(248, 81, 208);
    private static ServerLevel level;
    private static MinecraftServer server;
    private static UUID formedActor;
    private static UUID deniedActor;
    private static int ticks;

    static void start(MinecraftServer minecraftServer) {
        server = minecraftServer;
        level = server.overworld();
        level.setChunkForced(FORMED.getX() >> 4, FORMED.getZ() >> 4, true);
        for (BlockPos center : new BlockPos[]{FORMED, DENIED}) {
            level.setBlock(center.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(center, Blocks.AIR.defaultBlockState(), 3);
        }
        NeoForge.EVENT_BUS.addListener(NativeMobBlockMutationFixture::tick);
    }

    private static void tick(ServerTickEvent.Post event) {
        if (++ticks != 200) return;
        var rules = level.getGameRules();
        boolean original = rules.get(GameRules.MOB_GRIEFING);
        try {
            rules.set(GameRules.MOB_GRIEFING, false, server);
            var denied = makeGolem(DENIED);
            deniedActor = denied.getUUID();
            denied.discard();
            rules.set(GameRules.MOB_GRIEFING, true, server);
            var formed = makeGolem(FORMED);
            formedActor = formed.getUUID();
            formed.discard();
        } finally {
            rules.set(GameRules.MOB_GRIEFING, original, server);
        }
        if (!level.getBlockState(DENIED).isAir() || !level.getBlockState(FORMED).is(Blocks.SNOW))
            throw new AssertionError("Snow golem fixture did not exercise denied and committed paths");
    }

    private static SnowGolem makeGolem(BlockPos position) {
        var golem = EntityTypes.SNOW_GOLEM.create(level, EntitySpawnReason.COMMAND);
        if (golem == null) throw new AssertionError("Snow golem creation failed");
        golem.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        if (!level.addFreshEntity(golem)) throw new AssertionError("Snow golem join failed");
        golem.aiStep();
        return golem;
    }

    static void verify(EventSearchBackend backend) {
        var page = backend.search(new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
            SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
            new SearchRequest.Constraints(Set.of(Key.key("kansokusha:entity_block_change"))),
            Optional.empty(), 100)).join();
        var committed = page.events().stream().filter(row -> row.position()
            .equals(Optional.of(new BlockPosition(242, 81, 208)))
            && row.actor().filter(actor -> actor instanceof EntityActor entity
                && entity.uniqueId().equals(formedActor)).isPresent()
            && row.targetType().equals(Optional.of(Key.key("minecraft:snow")))).toList();
        if (committed.size() != 1)
            throw new AssertionError("Expected one snow-golem block formation, found " + committed.size());
        var payload = JsonParser.parseString(new String(committed.getFirst().payload().orElseThrow()
            .bytes().copyBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!payload.get("before").getAsString().contains("minecraft:air")
            || !payload.get("after").getAsString().contains("minecraft:snow")
            || !payload.get("cause").getAsString().equals("snow_golem_snow"))
            throw new AssertionError("Snow-golem mutation lost the committed before/after state");
        if (page.events().stream().anyMatch(row -> row.position()
            .equals(Optional.of(new BlockPosition(248, 81, 208)))
            && row.actor().filter(actor -> actor instanceof EntityActor entity
                && entity.uniqueId().equals(deniedActor)).isPresent()))
            throw new AssertionError("Denied snow-golem block write was recorded");
    }
}
