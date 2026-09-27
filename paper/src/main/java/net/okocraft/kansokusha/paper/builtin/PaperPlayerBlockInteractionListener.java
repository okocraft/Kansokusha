package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.InventoryHolder;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerBlockInteractionListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_interaction");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperPlayerBlockInteractionListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerBlockInteractionListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerBlockInteractionListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPlayerBlockInteractionListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(PlayerInteractEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (
            event.useInteractedBlock() == Event.Result.DENY
                && event.useItemInHand() == Event.Result.DENY
        ) {
            return;
        }

        var block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        var item = event.getItem();
        var operation = operation(block, item);
        if (operation == null) {
            return;
        }

        var player = event.getPlayer();
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            PaperBuiltInSupport.position(block),
            new PlayerActor(player.getUniqueId()),
            PaperBuiltInSupport.type(block.getType()),
            PaperAuditGapPayloadCodec.encodeBlockInteraction(
                operation,
                event.getAction().name(),
                event.getHand() == null ? null : event.getHand().name(),
                event.getBlockFace().name(),
                item
            )
        ));
    }

    private static @Nullable String operation(Block block, @Nullable ItemStack item) {
        if (
            (block.getType() == Material.SUSPICIOUS_SAND
                || block.getType() == Material.SUSPICIOUS_GRAVEL)
                && item != null
                && item.getType() == Material.BRUSH
        ) {
            return "brush";
        }

        var state = block.getState();
        var data = block.getBlockData();
        if (
            state instanceof InventoryHolder
                || state instanceof Sign
                || data instanceof Openable
                || data instanceof Powerable
        ) {
            return "interact";
        }

        return switch (block.getType()) {
            case JUKEBOX, CHISELED_BOOKSHELF, DECORATED_POT, LECTERN -> "interact";
            default -> null;
        };
    }
}
