package net.okocraft.kansokusha.paper.inspection;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@NotNullByDefault
public final class InspectionInteractionListener implements Listener {

    private final InspectionSessionManager sessions;
    private final InspectionTargetHandler targetHandler;

    public InspectionInteractionListener(
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
            new InspectionInteractionListener(sessions, targetHandler),
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
            ? clickedBlock.getRelative(event.getBlockFace())
            : clickedBlock;

        this.targetHandler.inspect(player.getUniqueId(), InspectionTarget.from(targetBlock));
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
}
