package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@NotNullByDefault
final class PaperAuditGapPayloadCodec {

    private PaperAuditGapPayloadCodec() {
    }

    static EventPayload encodeContainerClick(
        PaperContainerPayloadCodec.InventorySnapshot container,
        String action,
        String click,
        String clickedScope,
        int slot,
        int rawSlot,
        String direction,
        @Nullable ItemStack currentItem,
        @Nullable ItemStack cursor
    ) {
        var payload = new CompoundTag();
        payload.put("container", container.payload());
        payload.putString("operation", "click");
        payload.putString("action", normalized(action));
        payload.putString("click", normalized(click));
        payload.putString("clicked_scope", clickedScope);
        payload.putString("transfer_direction", direction);
        payload.putInt("slot", slot);
        payload.putInt("raw_slot", rawSlot);
        payload.put("item_before", PaperContainerPayloadCodec.snapshotItem(currentItem));
        payload.put("cursor", PaperContainerPayloadCodec.snapshotItem(cursor));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeContainerDrag(
        PaperContainerPayloadCodec.InventorySnapshot container,
        String dragType,
        ItemStack oldCursor,
        Map<Integer, ItemStack> newItems,
        int topInventorySize
    ) {
        var payload = new CompoundTag();
        payload.put("container", container.payload());
        payload.putString("operation", "drag");
        payload.putString("action", normalized(dragType));
        payload.putString("transfer_direction", "player_to_container");
        payload.put("cursor", PaperContainerPayloadCodec.snapshotItem(oldCursor));

        var slots = new ListTag();
        for (var entry : new TreeMap<>(newItems).entrySet()) {
            if (entry.getKey() < 0 || entry.getKey() >= topInventorySize) {
                continue;
            }
            var slot = new CompoundTag();
            slot.putInt("raw_slot", entry.getKey());
            slot.put("item_after", PaperContainerPayloadCodec.snapshotItem(entry.getValue()));
            slots.add(slot);
        }
        payload.put("dragged_slots", slots);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeBlockInteraction(
        String operation,
        String action,
        @Nullable String hand,
        String face,
        @Nullable ItemStack item
    ) {
        var payload = new CompoundTag();
        payload.putString("operation", operation);
        payload.putString("action", normalized(action));
        if (hand != null) {
            payload.putString("hand", normalized(hand));
        }
        payload.putString("face", normalized(face));
        payload.put("used_item", PaperContainerPayloadCodec.snapshotItem(item));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeEntityDeath(
        PaperEntityEventPayloadCodec.EntitySnapshot entity,
        @Nullable PaperEntityEventPayloadCodec.EntitySnapshot killer,
        String damageType,
        boolean indirect,
        int droppedExp,
        List<ItemStack> drops
    ) {
        var payload = new CompoundTag();
        payload.put("entity", entity(entity));
        if (killer != null) {
            payload.put("killer", entity(killer));
        }
        payload.putString("damage_type", damageType);
        payload.putBoolean("indirect_damage", indirect);
        payload.putInt("dropped_exp", droppedExp);
        payload.put("event_result_items", PaperContainerPayloadCodec.snapshotItems(drops));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeEntitySpawn(
        PaperEntityEventPayloadCodec.EntitySnapshot entity,
        String spawnReason
    ) {
        var payload = new CompoundTag();
        payload.put("entity", entity(entity));
        payload.putString("spawn_reason", normalized(spawnReason));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeDispense(ItemStack item, Vector velocity) {
        var payload = new CompoundTag();
        payload.put("item", PaperContainerPayloadCodec.snapshotItem(item));
        var vector = new CompoundTag();
        vector.putDouble("x", velocity.getX());
        vector.putDouble("y", velocity.getY());
        vector.putDouble("z", velocity.getZ());
        payload.put("velocity", vector);
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static CompoundTag entity(PaperEntityEventPayloadCodec.EntitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        var result = new CompoundTag();
        result.putString("uuid", snapshot.uuid().toString());
        result.putString("type", snapshot.type());
        result.putString("world", snapshot.worldKey().asString());
        var position = new CompoundTag();
        position.putDouble("x", snapshot.x());
        position.putDouble("y", snapshot.y());
        position.putDouble("z", snapshot.z());
        result.put("position", position);
        return result;
    }

    private static String normalized(String value) {
        return Objects.requireNonNull(value, "value").toLowerCase(Locale.ROOT);
    }
}
