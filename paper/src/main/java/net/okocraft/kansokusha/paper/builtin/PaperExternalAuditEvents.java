package net.okocraft.kansokusha.paper.builtin;

import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lets other plugins record operations that bypass the Bukkit events Kansokusha listens to, such as
 * items moved into a container or picked up by plugin code, as the built-in {@code container_transaction}
 * and {@code item_pickup} events.
 *
 * <p>All methods require the Kansokusha plugin to be running and throw {@link IllegalStateException}
 * otherwise. Events that cannot be located are skipped.</p>
 */
@ApiStatus.Experimental
@NotNullByDefault
public final class PaperExternalAuditEvents {

    private PaperExternalAuditEvents() {
    }

    /**
     * Takes a detached copy of the contents of {@code inventory} to pass to
     * {@link #recordContainerTransaction(Player, Inventory, ItemStack[], String)} after the operation.
     */
    public static ItemStack[] snapshotContents(Inventory inventory) {
        return PaperPlayerContainerTransactionListener.snapshotContents(Objects.requireNonNull(inventory, "inventory"));
    }

    /**
     * Records the net change of a storage container made on behalf of {@code player}, comparing
     * {@code before} with the current contents. Records nothing if the contents did not change,
     * the inventory is not a located storage container, or the events are not registered.
     *
     * @param action an identifier of the operation, such as {@code box_stick}
     */
    public static void recordContainerTransaction(
        Player player,
        Inventory inventory,
        ItemStack[] before,
        String action
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(action, "action");

        if (!PaperPlayerContainerTransactionListener.isStorageContainer(inventory)) {
            return;
        }
        var container = PaperContainerPayloadCodec.snapshotInventory(inventory);
        if (container.worldKey() == null || container.position() == null) {
            return;
        }

        var api = Kansokusha.api();
        var serverKey = api.localServerKey().orElseThrow();
        var occurredAt = Instant.now();
        var transactionId = UUID.randomUUID();
        for (var delta : PaperPlayerContainerTransactionListener.diffContents(before, inventory.getContents())) {
            var direction = delta.amountDelta() > 0 ? "added_to_container" : "removed_from_container";
            api.submit(new EventSubmission(
                PaperPlayerContainerTransactionListener.EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                serverKey,
                container.worldKey(),
                container.position(),
                new PlayerActor(player.getUniqueId()),
                PaperBuiltInSupport.itemType(delta.item()),
                PaperAuditGapPayloadCodec.encodeContainerDelta(
                    container,
                    transactionId,
                    "external",
                    action,
                    null,
                    null,
                    -1,
                    -1,
                    -1,
                    direction,
                    delta.item(),
                    delta.amountDelta()
                )
            ));
        }
    }

    /**
     * Records that {@code player} picked up {@code item} at {@code location}.
     *
     * @param itemEntityId the item entity that was picked up, or {@code null} if the item was never an entity
     */
    public static void recordItemPickup(
        Player player,
        Location location,
        ItemStack item,
        @Nullable UUID itemEntityId
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(item, "item");

        var world = location.getWorld();
        if (world == null || item.isEmpty()) {
            return;
        }

        var api = Kansokusha.api();
        api.submit(new EventSubmission(
            PaperPlayerItemAuditListener.ITEM_PICKUP_EVENT_TYPE,
            PayloadGeneration.FIRST,
            Instant.now(),
            api.localServerKey().orElseThrow(),
            PaperKansokusha.key(world.getKey()),
            new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
            new PlayerActor(player.getUniqueId()),
            PaperBuiltInSupport.itemType(item),
            PaperPlayerItemAuditPayloadCodec.encodeItemEntityChange(
                itemEntityId != null ? itemEntityId : UUID.randomUUID(),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(item),
                location.getX(),
                location.getY(),
                location.getZ(),
                0
            )
        ));
    }
}
