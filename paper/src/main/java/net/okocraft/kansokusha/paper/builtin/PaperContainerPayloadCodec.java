package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@NotNullByDefault
final class PaperContainerPayloadCodec {

    private PaperContainerPayloadCodec() {
    }

    static InventorySnapshot snapshotInventory(Inventory inventory) {
        Objects.requireNonNull(inventory, "inventory");

        var payload = new CompoundTag();
        payload.putString("type", inventory.getType().name().toLowerCase(Locale.ROOT));
        payload.putInt("size", inventory.getSize());

        var holder = inventory.getHolder();
        if (holder instanceof Entity entity) {
            payload.putString("holder_kind", "entity");
            payload.putString("holder_uuid", entity.getUniqueId().toString());
            payload.putString("holder_type", entity.getType().name().toLowerCase(Locale.ROOT));
        } else if (holder instanceof BlockState state) {
            payload.putString("holder_kind", "block");
            payload.putString("holder_block_type", state.getType().key().asString());
        } else if (holder == null) {
            payload.putString("holder_kind", "none");
        } else {
            payload.putString("holder_kind", "other");
            payload.putString("holder_class", holder.getClass().getName());
        }

        var location = snapshotLocation(inventory.getLocation());
        if (location != null) {
            payload.put("location", location.payload());
            return new InventorySnapshot(payload, location.worldKey(), location.position());
        }
        return new InventorySnapshot(payload, null, null);
    }

    static InventorySnapshot snapshotBlockContainer(Block block, @Nullable Inventory inventory) {
        Objects.requireNonNull(block, "block");

        CompoundTag payload;
        if (inventory == null) {
            payload = new CompoundTag();
            payload.putString("type", "none");
            payload.putInt("size", 0);
            payload.putString("holder_kind", "block");
        } else {
            payload = snapshotInventory(inventory).payload();
        }

        payload.putString("processing_block_type", block.getType().key().asString());
        var location = new CompoundTag();
        location.putString("world", block.getWorld().getKey().toString());
        location.putDouble("x", block.getX());
        location.putDouble("y", block.getY());
        location.putDouble("z", block.getZ());
        payload.put("location", location);

        return new InventorySnapshot(
            payload,
            PaperKansokusha.key(block.getWorld().getKey()),
            PaperBuiltInSupport.position(block)
        );
    }

    static @Nullable LocationSnapshot snapshotLocation(@Nullable Location location) {
        if (location == null) {
            return null;
        }

        var payload = new CompoundTag();
        payload.putDouble("x", location.getX());
        payload.putDouble("y", location.getY());
        payload.putDouble("z", location.getZ());

        var world = location.getWorld();
        if (world == null) {
            return new LocationSnapshot(payload, null, null);
        }

        payload.putString("world", world.getKey().toString());
        return new LocationSnapshot(
            payload,
            PaperKansokusha.key(world.getKey()),
            new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ())
        );
    }

    static CompoundTag snapshotItem(@Nullable ItemStack item) {
        return PaperItemStackPayloadCodec.encode(item == null ? ItemStack.empty() : item);
    }

    static ListTag snapshotItems(List<ItemStack> items) {
        Objects.requireNonNull(items, "items");
        var result = new ListTag();
        for (var item : items) {
            result.add(snapshotItem(item));
        }
        return result;
    }

    static ListTag snapshotItems(ItemStack[] items) {
        Objects.requireNonNull(items, "items");
        var result = new ListTag();
        for (var item : items) {
            result.add(snapshotItem(item));
        }
        return result;
    }

    static EventPayload encodeTransfer(
        InventorySnapshot source,
        InventorySnapshot destination,
        InventorySnapshot initiator,
        String initiatorRole,
        CompoundTag initialItem,
        CompoundTag finalItem
    ) {
        var payload = new CompoundTag();
        payload.putString("semantics", "non_cancelled_automated_transfer_attempt");
        payload.put("source_inventory", source.payload());
        payload.put("destination_inventory", destination.payload());
        payload.put("initiator_inventory", initiator.payload());
        payload.putString("initiator_role", initiatorRole);
        payload.putString(
            "transfer_direction",
            switch (initiatorRole) {
                case "source" -> "push";
                case "destination" -> "pull";
                default -> "unknown";
            }
        );
        payload.put("initial_item", initialItem);
        payload.put("item", finalItem);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodePickup(
        InventorySnapshot inventory,
        String itemEntityId,
        CompoundTag item,
        LocationSnapshot itemOrigin
    ) {
        var payload = new CompoundTag();
        payload.putString("source_kind", "world_item");
        payload.put("inventory", inventory.payload());
        payload.putString("item_entity_uuid", itemEntityId);
        payload.put("item", item);
        payload.put("item_origin", itemOrigin.payload());
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeProcess(
        String processKind,
        String sourceEvent,
        InventorySnapshot container,
        ListTag inputItems,
        CompoundTag ingredient,
        CompoundTag fuel,
        ListTag initialResults,
        ListTag finalResults
    ) {
        var payload = new CompoundTag();
        payload.putString("process_kind", processKind);
        payload.putString("source_event", sourceEvent);
        payload.put("container", container.payload());
        payload.put("input_items", inputItems);
        payload.put("ingredient", ingredient);
        payload.put("fuel", fuel);
        payload.put("initial_result_items", initialResults);
        payload.put("final_result_items", finalResults);
        return PaperPayloadNbtCodec.encode(payload);
    }

    record InventorySnapshot(
        CompoundTag payload,
        @Nullable Key worldKey,
        @Nullable BlockPosition position
    ) {
    }

    record LocationSnapshot(
        CompoundTag payload,
        @Nullable Key worldKey,
        @Nullable BlockPosition position
    ) {
    }
}
