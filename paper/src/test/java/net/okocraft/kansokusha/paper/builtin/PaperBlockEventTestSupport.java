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
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

final class PaperBlockEventTestSupport {

    static final Key SERVER_KEY = Key.key("example", "paper");

    private static final ConcurrentLinkedQueue<World> RETAINED_WORLDS =
        new ConcurrentLinkedQueue<>();

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
        // Bukkit Location keeps only a weak reference to its World. Test fixtures often retain
        // the Location but not the World, so keep mock worlds strongly reachable for the test JVM.
        RETAINED_WORLDS.add(world);
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

    static class RecordingApi implements KansokushaApi, EventSearchBackend {

        final ConcurrentLinkedQueue<EventSubmission> submissions = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> playerLoginNames = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> searchTexts = new ConcurrentLinkedQueue<>();

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

        @Override
        public boolean submitSearchable(EventSubmission submission, String searchText) {
            this.searchTexts.add(searchText);
            return this.submit(submission);
        }

        @Override
        public CompletableFuture<SearchPage> search(SearchRequest request) {
            return CompletableFuture.completedFuture(
                new SearchPage(List.of(), Optional.empty(), Optional.empty())
            );
        }

        @Override
        public boolean submitPlayerLogin(EventSubmission submission, String username) {
            this.playerLoginNames.add(username);
            return this.submit(submission);
        }

        @Override
        public CompletableFuture<Optional<EventDetail>> findEvent(UUID eventId) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<SearchMetadata> searchMetadata() {
            return CompletableFuture.completedFuture(SearchMetadata.empty());
        }

        @Override
        public CompletableFuture<List<String>> offlinePlayerNames() {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
