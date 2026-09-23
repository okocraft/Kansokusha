package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
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

    static EventPayload snapshotBlockState(BlockData blockData) {
        return PaperBlockStatePayloadCodec.encodeBlockBreak(
            Objects.requireNonNull(blockData, "blockData")
        );
    }

    static EventPayload snapshotItem(@Nullable ItemStack itemStack) {
        var value = itemStack == null ? ItemStack.empty() : itemStack;
        return PaperPayloadNbtCodec.encode(PaperItemStackPayloadCodec.encode(value));
    }

    static List<EventPayload> snapshotItems(List<ItemStack> itemStacks) {
        Objects.requireNonNull(itemStacks, "itemStacks");
        var snapshot = new ArrayList<EventPayload>(itemStacks.size());
        for (var itemStack : itemStacks) {
            snapshot.add(snapshotItem(itemStack));
        }
        return List.copyOf(snapshot);
    }

    static EventPayload snapshotFlowerPotContent(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return snapshotItem(null);
        }
        return snapshotItem(ItemStack.of(itemStack.getType(), 1));
    }

    static EventPayload expectedBucketPostState(
        String operation,
        Material bucket,
        BlockData preState,
        boolean waterEvaporates
    ) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(preState, "preState");

        BlockData expected;
        if ("fill".equals(operation)) {
            expected = expectedAfterFill(preState);
        } else if ("empty".equals(operation)) {
            expected = expectedAfterEmpty(bucket, preState, waterEvaporates);
        } else {
            throw new IllegalArgumentException("Unknown bucket operation: " + operation);
        }
        return snapshotBlockState(expected);
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
        EventPayload preState,
        EventPayload expectedPostState,
        EventPayload initialResultItem,
        EventPayload finalResultItem
    ) {
        var payload = new CompoundTag();
        payload.putString("operation", Objects.requireNonNull(operation, "operation"));
        payload.putString("bucket", Objects.requireNonNull(bucket, "bucket").key().asString());
        payload.putString("hand", enumName(hand));
        payload.putString("face", enumName(face));
        payload.putInt("clicked_x", clickedX);
        payload.putInt("clicked_y", clickedY);
        payload.putInt("clicked_z", clickedZ);
        putPayload(payload, "pre_state", preState);
        putPayload(payload, "expected_post_state", expectedPostState);
        putPayload(payload, "initial_result_item", initialResultItem);
        putPayload(payload, "final_result_item", finalResultItem);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeHarvest(
        BlockData preState,
        EquipmentSlot hand,
        List<ItemStack> harvestedItems
    ) {
        return encodeBlockHarvest(
            "harvest",
            "org.bukkit.event.player.PlayerHarvestBlockEvent",
            hand,
            snapshotBlockState(preState),
            snapshotItems(harvestedItems),
            snapshotItem(null),
            List.of()
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
            "io.papermc.paper.event.block.PlayerShearBlockEvent",
            hand,
            snapshotBlockState(preState),
            List.of(),
            snapshotItem(tool),
            snapshotItems(drops)
        );
    }

    static EventPayload encodeFlowerPot(boolean placing, ItemStack item) {
        var itemSnapshot = snapshotFlowerPotContent(item);
        var empty = snapshotItem(null);
        var payload = new CompoundTag();
        payload.putString("action", placing ? "insert" : "remove");
        putPayload(payload, "before", placing ? empty : itemSnapshot);
        putPayload(payload, "after", placing ? itemSnapshot : empty);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static CompoundTag decode(EventPayload payload) throws IOException {
        return PaperPayloadNbtCodec.decode(payload);
    }

    static CompoundTag decodeNestedItem(CompoundTag payload, String key) throws IOException {
        return PaperPayloadNbtCodec.decode(nestedPayload(payload, key));
    }

    static CompoundTag decodeNestedBlockState(CompoundTag payload, String key) throws IOException {
        return PaperBlockStatePayloadCodec.decode(nestedPayload(payload, key));
    }

    static List<CompoundTag> decodeNestedItems(CompoundTag payload, String key) throws IOException {
        var list = payload.getListOrEmpty(key);
        var decoded = new ArrayList<CompoundTag>(list.size());
        for (var tag : list) {
            if (!(tag instanceof ByteArrayTag bytes)) {
                throw new IllegalArgumentException("Expected byte-array payload in '" + key + "'.");
            }
            decoded.add(
                PaperPayloadNbtCodec.decode(
                    EventPayload.copyOf(bytes.getAsByteArray().clone())
                )
            );
        }
        return List.copyOf(decoded);
    }

    private static BlockData expectedAfterFill(BlockData preState) {
        var material = preState.getMaterial();
        if (
            material == Material.WATER_CAULDRON
                || material == Material.LAVA_CAULDRON
                || material == Material.POWDER_SNOW_CAULDRON
        ) {
            return Blocks.CAULDRON.defaultBlockState().asBlockData();
        }
        if (preState instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            var expected = preState.clone();
            ((Waterlogged) expected).setWaterlogged(false);
            return expected;
        }
        return Blocks.AIR.defaultBlockState().asBlockData();
    }

    private static BlockData expectedAfterEmpty(
        Material bucket,
        BlockData preState,
        boolean waterEvaporates
    ) {
        if (isCauldron(preState.getMaterial())) {
            return switch (bucket) {
                case LAVA_BUCKET -> Blocks.LAVA_CAULDRON.defaultBlockState().asBlockData();
                case POWDER_SNOW_BUCKET -> fullPowderSnowCauldron();
                default -> fullWaterCauldron();
            };
        }
        if (isWaterBucket(bucket) && waterEvaporates) {
            return preState;
        }
        if (isWaterBucket(bucket) && preState instanceof Waterlogged waterlogged) {
            var expected = preState.clone();
            ((Waterlogged) expected).setWaterlogged(true);
            return expected;
        }
        return switch (bucket) {
            case LAVA_BUCKET -> Blocks.LAVA.defaultBlockState().asBlockData();
            case POWDER_SNOW_BUCKET -> Blocks.POWDER_SNOW.defaultBlockState().asBlockData();
            default -> Blocks.WATER.defaultBlockState().asBlockData();
        };
    }

    private static BlockData fullWaterCauldron() {
        return Blocks.WATER_CAULDRON.defaultBlockState()
            .setValue(LayeredCauldronBlock.LEVEL, 3)
            .asBlockData();
    }

    private static BlockData fullPowderSnowCauldron() {
        return Blocks.POWDER_SNOW_CAULDRON.defaultBlockState()
            .setValue(LayeredCauldronBlock.LEVEL, 3)
            .asBlockData();
    }

    private static boolean isCauldron(Material material) {
        return material == Material.CAULDRON
            || material == Material.WATER_CAULDRON
            || material == Material.LAVA_CAULDRON
            || material == Material.POWDER_SNOW_CAULDRON;
    }

    private static boolean isWaterBucket(Material bucket) {
        return bucket != Material.LAVA_BUCKET && bucket != Material.POWDER_SNOW_BUCKET;
    }

    private static EventPayload encodeBlockHarvest(
        String operation,
        String sourceEvent,
        EquipmentSlot hand,
        EventPayload preState,
        List<EventPayload> harvestedItems,
        EventPayload shearTool,
        List<EventPayload> shearDrops
    ) {
        var payload = new CompoundTag();
        payload.putString("operation", operation);
        payload.putString("source_event", sourceEvent);
        payload.putString("hand", enumName(hand));
        putPayload(payload, "pre_state", preState);
        payload.put("harvest_items", encodePayloadList(harvestedItems));
        putPayload(payload, "shear_tool", shearTool);
        payload.put("shear_drops", encodePayloadList(shearDrops));
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

    private static ListTag encodePayloadList(List<EventPayload> payloads) {
        var encoded = new ListTag();
        for (var payload : payloads) {
            encoded.add(new ByteArrayTag(payload.copyBytes()));
        }
        return encoded;
    }

    private static void putPayload(CompoundTag target, String key, EventPayload payload) {
        target.put(
            key,
            new ByteArrayTag(Objects.requireNonNull(payload, "payload").copyBytes())
        );
    }

    private static EventPayload nestedPayload(CompoundTag payload, String key) {
        var value = Objects.requireNonNull(payload, "payload").get(key);
        if (!(value instanceof ByteArrayTag bytes)) {
            throw new IllegalArgumentException("Expected byte-array payload in '" + key + "'.");
        }
        return EventPayload.copyOf(bytes.getAsByteArray().clone());
    }

    private static String enumName(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }
}
