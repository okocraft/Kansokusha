package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

final class PaperBlockEventTestSupport {

    static final Key SERVER_KEY = Key.key("example", "paper");

    private PaperBlockEventTestSupport() {
    }

    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
    }

    static World world() {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));
        return world;
    }

    static Block block(
        World world,
        int x,
        int y,
        int z,
        BlockState state,
        Material material
    ) {
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(y);
        Mockito.when(block.getZ()).thenReturn(z);
        Mockito.when(block.getBlockData()).thenReturn(state.asBlockData());
        Mockito.when(block.getType()).thenReturn(material);
        return block;
    }

    static org.bukkit.block.BlockState state(
        World world,
        Block block,
        int x,
        int y,
        int z,
        BlockState state
    ) {
        var result = Mockito.mock(org.bukkit.block.BlockState.class);
        Mockito.when(result.getWorld()).thenReturn(world);
        Mockito.when(result.getBlock()).thenReturn(block);
        Mockito.when(result.getX()).thenReturn(x);
        Mockito.when(result.getY()).thenReturn(y);
        Mockito.when(result.getZ()).thenReturn(z);
        Mockito.when(result.getBlockData()).thenReturn(state.asBlockData());
        return result;
    }

    static CompoundTag position(BlockPosition position) {
        var result = new CompoundTag();
        result.putInt("x", position.x());
        result.putInt("y", position.y());
        result.putInt("z", position.z());
        return result;
    }

    static final class RecordingApi implements KansokushaApi {

        final ConcurrentLinkedQueue<EventSubmission> submissions = new ConcurrentLinkedQueue<>();

        @Override
        public Optional<Key> localServerKey() {
            return Optional.of(SERVER_KEY);
        }

        @Override
        public void registerEventType(EventTypeDefinition definition) {
        }

        @Override
        public boolean submit(EventSubmission submission) {
            this.submissions.add(submission);
            return true;
        }
    }
}
