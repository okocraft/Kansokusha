package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@NotNullByDefault
final class PaperTntTransitionTracker<T> {

    private final Map<BlockKey, ArrayDeque<T>> pending = new HashMap<>();

    void add(Key worldKey, BlockPosition position, T value) {
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(value, "value");
        synchronized (this.pending) {
            this.pending.computeIfAbsent(
                new BlockKey(worldKey, position),
                ignored -> new ArrayDeque<>()
            ).addLast(value);
        }
    }

    @Nullable T remove(Block block) {
        Objects.requireNonNull(block, "block");
        var key = new BlockKey(
            PaperKansokusha.key(block.getWorld().getKey()),
            new BlockPosition(block.getX(), block.getY(), block.getZ())
        );
        synchronized (this.pending) {
            var queue = this.pending.get(key);
            if (queue == null) {
                return null;
            }
            var value = queue.pollFirst();
            if (queue.isEmpty()) {
                this.pending.remove(key);
            }
            return value;
        }
    }

    void clear() {
        synchronized (this.pending) {
            this.pending.clear();
        }
    }

    int size() {
        synchronized (this.pending) {
            var size = 0;
            for (var queue : this.pending.values()) {
                size += queue.size();
            }
            return size;
        }
    }

    private record BlockKey(Key worldKey, BlockPosition position) {
    }
}
