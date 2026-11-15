package net.okocraft.kansokusha.paper.notification;

import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends a Discord message when a survival or adventure player breaks the first ore of a naturally
 * generated vein.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class OreVeinNotificationListener implements Listener {

    private static final Set<GameMode> IGNORED_GAME_MODES = Set.of(GameMode.CREATIVE, GameMode.SPECTATOR);

    private final KansokushaConfig.OreVeinNotification settings;
    private final DiscordWebhook webhook;
    // Ores placed by players or already reported, per world. Kept in memory only.
    private final Map<UUID, Set<BlockPosition>> knownOres = new ConcurrentHashMap<>();

    public OreVeinNotificationListener(KansokushaConfig.OreVeinNotification settings, DiscordWebhook webhook) {
        this.settings = settings;
        this.webhook = webhook;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        var block = event.getBlockPlaced();
        if (this.isTarget(block.getType())) {
            var known = this.knownOres(block.getWorld());
            synchronized (known) {
                known.add(Position.block(block.getLocation()));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        var player = event.getPlayer();
        var block = event.getBlock();
        if (IGNORED_GAME_MODES.contains(player.getGameMode()) || !this.isTarget(block.getType())) {
            return;
        }
        var count = this.countNewVein(block);
        if (count > 0) {
            var key = block.getType().key();
            this.webhook.send(this.settings.format()
                .replace("%player_name%", player.getName())
                .replace("%block_type%", this.settings.displayNames().getOrDefault(key, key.asMinimalString()))
                .replace("%block_world%", block.getWorld().getName())
                .replace("%block_location%", block.getX() + " " + block.getY() + " " + block.getZ())
                .replace("%vein_count%", Integer.toString(count)));
        }
    }

    /**
     * Counts the connected blocks of the broken block's type, which still has its type at MONITOR,
     * and remembers them so that the vein is reported only once.
     *
     * @return the vein size, or 0 when the block was placed by a player or is already reported
     */
    private int countNewVein(Block origin) {
        var world = origin.getWorld();
        var type = origin.getType();
        var known = this.knownOres(world);
        // On Folia, regions of the same world break blocks concurrently.
        synchronized (known) {
            var start = Position.block(origin.getLocation());
            if (!known.add(start)) {
                return 0;
            }
            var count = 1;
            var inspected = 0;
            var queue = new ArrayDeque<BlockPosition>();
            queue.add(start);
            while (!queue.isEmpty()) {
                var current = queue.poll();
                for (var dx = -1; dx <= 1; dx++) {
                    for (var dy = -1; dy <= 1; dy++) {
                        for (var dz = -1; dz <= 1; dz++) {
                            if (inspected++ >= this.settings.maxSearchCount()) {
                                return count;
                            }
                            var next = current.offset(dx, dy, dz);
                            if (isReadable(world, next) && world.getType(next.blockX(), next.blockY(), next.blockZ()) == type
                                && known.add(next)) {
                                count++;
                                queue.add(next);
                            }
                        }
                    }
                }
            }
            return count;
        }
    }

    private boolean isTarget(Material type) {
        return this.settings.ores().contains(type.key());
    }

    private Set<BlockPosition> knownOres(World world) {
        return this.knownOres.computeIfAbsent(world.getUID(), ignored -> new HashSet<>());
    }

    private static boolean isReadable(World world, BlockPosition position) {
        // Never load chunks, and never read blocks owned by another Folia region.
        var chunkX = position.blockX() >> 4;
        var chunkZ = position.blockZ() >> 4;
        return world.isChunkLoaded(chunkX, chunkZ) && Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }
}
