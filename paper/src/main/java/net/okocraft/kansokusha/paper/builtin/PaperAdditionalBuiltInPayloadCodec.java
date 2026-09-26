package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperAdditionalBuiltInPayloadCodec {

    private static final String COMPONENT_KEY = "component";

    private PaperAdditionalBuiltInPayloadCodec() {
    }

    static List<Optional<String>> snapshotLines(List<Component> lines) {
        Objects.requireNonNull(lines, "lines");
        var snapshot = new ArrayList<Optional<String>>(lines.size());
        for (var line : lines) {
            snapshot.add(
                line == null
                    ? Optional.empty()
                    : Optional.of(PaperComponentPayloadCodec.encode(line))
            );
        }
        return List.copyOf(snapshot);
    }

    static CompoundTag snapshotBlockState(BlockData blockData) {
        return PaperBlockStatePayloadCodec.blockState(Objects.requireNonNull(blockData, "blockData"));
    }

    static CompoundTag snapshotItem(@Nullable ItemStack itemStack) {
        return PaperItemStackPayloadCodec.encode(itemStack == null ? ItemStack.empty() : itemStack);
    }

    static ListTag snapshotItems(List<ItemStack> itemStacks) {
        Objects.requireNonNull(itemStacks, "itemStacks");
        var snapshot = new ListTag();
        for (var itemStack : itemStacks) {
            snapshot.add(snapshotItem(itemStack));
        }
        return snapshot;
    }

    static CompoundTag snapshotFlowerPotContent(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return snapshotItem(null);
        }
        return snapshotItem(ItemStack.of(itemStack.getType(), 1));
    }

    static EventPayload encodeSignChange(
        Side side,
        List<Optional<String>> before,
        List<Optional<String>> after
    ) {
        var payload = new CompoundTag();
        payload.putString("side", enumName(side));
        payload.put("before", encodeLines(before));
        payload.put("after", encodeLines(after));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeBucket(
        String operation,
        Material bucket,
        EquipmentSlot hand,
        BlockFace face,
        int clickedX,
        int clickedY,
        int clickedZ,
        CompoundTag preState,
        CompoundTag resultItem
    ) {
        var payload = new CompoundTag();
        payload.putString("operation", Objects.requireNonNull(operation, "operation"));
        payload.putString("bucket", Objects.requireNonNull(bucket, "bucket").key().asString());
        payload.putString("hand", enumName(hand));
        payload.putString("face", enumName(face));
        payload.putInt("clicked_x", clickedX);
        payload.putInt("clicked_y", clickedY);
        payload.putInt("clicked_z", clickedZ);
        payload.put("pre_state", preState);
        payload.put("result_item", resultItem);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeHarvest(
        BlockData preState,
        EquipmentSlot hand,
        List<ItemStack> harvestedItems
    ) {
        return encodeBlockHarvest(
            "harvest",
            hand,
            snapshotBlockState(preState),
            snapshotItems(harvestedItems),
            snapshotItem(null),
            new ListTag()
        );
    }

    static EventPayload encodeShear(
        BlockData preState,
        ItemStack tool,
        EquipmentSlot hand,
        List<ItemStack> drops
    ) {
        return encodeBlockHarvest(
            "shear",
            hand,
            snapshotBlockState(preState),
            new ListTag(),
            snapshotItem(tool),
            snapshotItems(drops)
        );
    }

    static EventPayload encodeFlowerPot(boolean placing, ItemStack item) {
        var itemSnapshot = snapshotFlowerPotContent(item);
        var empty = snapshotItem(null);
        var payload = new CompoundTag();
        payload.putString("action", placing ? "insert" : "remove");
        payload.put("before", placing ? empty : itemSnapshot);
        payload.put("after", placing ? itemSnapshot : empty);
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static EventPayload encodeBlockHarvest(
        String operation,
        EquipmentSlot hand,
        CompoundTag preState,
        ListTag harvestedItems,
        CompoundTag shearTool,
        ListTag shearDrops
    ) {
        var payload = new CompoundTag();
        payload.putString("operation", operation);
        payload.putString("hand", enumName(hand));
        payload.put("pre_state", preState);
        payload.put("harvest_items", harvestedItems);
        payload.put("shear_tool", shearTool);
        payload.put("shear_drops", shearDrops);
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static ListTag encodeLines(List<Optional<String>> lines) {
        Objects.requireNonNull(lines, "lines");
        var encoded = new ListTag();
        for (var line : lines) {
            var entry = new CompoundTag();
            line.ifPresent(serialized -> entry.putString(COMPONENT_KEY, serialized));
            encoded.add(entry);
        }
        return encoded;
    }

    private static String enumName(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }
}
