package net.okocraft.kansokusha.paper.notification;

import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.paper.builtin.PaperOreVeinEvents;
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
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records {@code kansokusha:ore_vein_found}, and sends a Discord message when a webhook is set, when
 * a survival or adventure player breaks the first ore of a naturally generated vein.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class OreVeinListener implements Listener {

    private static final Set<GameMode> IGNORED_GAME_MODES = Set.of(GameMode.CREATIVE, GameMode.SPECTATOR);

    private final KansokushaConfig.OreVein settings;
    private final KansokushaApi api;
    private final Key serverKey;
    private final @Nullable DiscordWebhook webhook;
    // Existing ores placed by players or belonging to a reported vein, per world. Kept in memory only.
    private final Map<UUID, Set<BlockPosition>> knownOres = new ConcurrentHashMap<>();

    public OreVeinListener(KansokushaConfig.OreVein settings, KansokushaApi api, Key serverKey,
                           @Nullable DiscordWebhook webhook) {
        this.settings = settings;
        this.api = api;
        this.serverKey = serverKey;
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
        if (!this.isTarget(block.getType())) {
            return;
        }
        var known = this.knownOres(block.getWorld());
        int count;
        // On Folia, regions of the same world break blocks concurrently.
        synchronized (known) {
            // The broken ore no longer exists, so forget it; the set holds only ores that still exist.
            if (known.remove(Position.block(block.getLocation())) || IGNORED_GAME_MODES.contains(player.getGameMode())) {
                return;
            }
            count = this.countVein(block, known);
        }
        this.api.submit(PaperOreVeinEvents.found(Instant.now(), this.serverKey, player, block, count));
        if (this.webhook == null) {
            return;
        }
        var key = block.getType().key();
        this.webhook.send(this.settings.format()
            .replace("%player_name%", player.getName())
            .replace("%block_type%", this.settings.displayNames().getOrDefault(key, key.asMinimalString()))
            .replace("%block_world%", block.getWorld().getName())
            .replace("%block_location%", block.getX() + " " + block.getY() + " " + block.getZ())
            .replace("%vein_count%", Integer.toString(count)));
    }

    /**
     * Counts the connected blocks of the broken block's type, which still has its type at MONITOR,
     * and adds them to {@code known} so that the vein is reported only once. The search stops
     * after inspecting {@code max-search-count} distinct blocks, so the rest of a larger vein may
     * be reported again later.
     */
    private int countVein(Block origin, Set<BlockPosition> known) {
        var world = origin.getWorld();
        var type = origin.getType();
        var start = Position.block(origin.getLocation());
        var inspected = new HashSet<BlockPosition>(Set.of(start));
        var queue = new ArrayDeque<BlockPosition>(List.of(start));
        var count = 1;
        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var dx = -1; dx <= 1; dx++) {
                for (var dy = -1; dy <= 1; dy++) {
                    for (var dz = -1; dz <= 1; dz++) {
                        var next = current.offset(dx, dy, dz);
                        if (!inspected.add(next) || known.contains(next)) {
                            continue;
                        }
                        if (inspected.size() > this.settings.maxSearchCount()) {
                            return count;
                        }
                        if (isReadable(world, next) && world.getType(next.blockX(), next.blockY(), next.blockZ()) == type) {
                            known.add(next);
                            count++;
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return count;
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
