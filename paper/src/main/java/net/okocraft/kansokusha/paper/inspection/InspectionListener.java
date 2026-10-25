package net.okocraft.kansokusha.paper.inspection;

import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@NotNullByDefault
public final class InspectionListener implements Listener {

    private final InspectionSessionManager sessions;
    private final InspectionTargetHandler targetHandler;

    public InspectionListener(
        InspectionSessionManager sessions,
        InspectionTargetHandler targetHandler
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.targetHandler = Objects.requireNonNull(targetHandler, "targetHandler");
    }

    public static void register(
        Plugin plugin,
        InspectionSessionManager sessions,
        InspectionTargetHandler targetHandler
    ) {
        Objects.requireNonNull(plugin, "plugin").getServer().getPluginManager().registerEvents(
            new InspectionListener(sessions, targetHandler),
            plugin
        );
    }

    /**
     * Suppresses both block use and held-item use for inspector block clicks.
     *
     * <p>The handler intentionally does not ignore already-cancelled events: inspection is a
     * read-only UI and can still resolve a target when another plugin has denied the vanilla
     * interaction. Off-hand events are suppressed as well, but only the main-hand event produces
     * a lookup target.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void inspect(PlayerInteractEvent event) {
        Objects.requireNonNull(event, "event");

        var action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        var player = event.getPlayer();
        if (!this.sessions.isEnabled(player)) {
            return;
        }

        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        var clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        var targetBlock = action == Action.RIGHT_CLICK_BLOCK
            && !(clickedBlock.getState() instanceof org.bukkit.block.Container)
            && !clickedBlock.getType().isInteractable()
            ? clickedBlock.getRelative(event.getBlockFace())
            : clickedBlock;

        if (action == Action.RIGHT_CLICK_BLOCK && clickedBlock.getState() instanceof org.bukkit.block.Container container) {
            var positions = new java.util.LinkedHashSet<SearchQuery.Position>();
            positions.add(new SearchQuery.Position(PaperKansokusha.key(clickedBlock.getWorld().getKey()),
                clickedBlock.getX(), clickedBlock.getY(), clickedBlock.getZ()));
            if (container.getInventory() instanceof org.bukkit.inventory.DoubleChestInventory chest) {
                for (var inventory : java.util.List.of(chest.getLeftSide(), chest.getRightSide())) {
                    var location = inventory.getLocation();
                    if (location != null && location.getWorld() != null) positions.add(position(location));
                }
            }
            this.targetHandler.inspectContainer(player.getUniqueId(), java.util.Set.copyOf(positions));
            return;
        }

        this.targetHandler.inspect(player.getUniqueId(), new SearchQuery.Position(
            PaperKansokusha.key(targetBlock.getWorld().getKey()),
            targetBlock.getX(),
            targetBlock.getY(),
            targetBlock.getZ()
        ));
    }

    private static SearchQuery.Position position(org.bukkit.Location location) {
        return new SearchQuery.Position(PaperKansokusha.key(location.getWorld().getKey()),
            location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void suppressDamage(BlockDamageEvent event) {
        Objects.requireNonNull(event, "event");
        if (this.sessions.isEnabled(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void suppressBreak(BlockBreakEvent event) {
        Objects.requireNonNull(event, "event");
        if (this.sessions.isEnabled(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void cleanup(PlayerQuitEvent event) {
        this.sessions.disable(event.getPlayer().getUniqueId());
    }
}
