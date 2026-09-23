package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockStatePayloadCodec {

    private static final String REPLACED_STATE_KEY = "replaced";
    private static final String PLACED_STATE_KEY = "placed";

    private PaperBlockStatePayloadCodec() {
    }

    public static EventPayload encodeBlockBreak(BlockData blockData) {
        return PaperPayloadNbtCodec.encode(
            NbtUtils.writeBlockState(toMinecraftState(blockData))
        );
    }

    public static EventPayload encodeBlockPlace(
        BlockData replacedBlockData,
        BlockData placedBlockData
    ) {
        var payload = new CompoundTag();
        payload.put(
            REPLACED_STATE_KEY,
            NbtUtils.writeBlockState(toMinecraftState(replacedBlockData))
        );
        payload.put(
            PLACED_STATE_KEY,
            NbtUtils.writeBlockState(toMinecraftState(placedBlockData))
        );
        return PaperPayloadNbtCodec.encode(payload);
    }

    static CompoundTag decode(EventPayload payload) throws IOException {
        return PaperPayloadNbtCodec.decode(payload);
    }

    private static BlockState toMinecraftState(BlockData blockData) {
        if (!(Objects.requireNonNull(blockData, "blockData") instanceof CraftBlockData craftBlockData)) {
            throw new IllegalArgumentException(
                "Paper block data is not backed by CraftBlockData: " + blockData.getClass().getName()
            );
        }
        return craftBlockData.getState();
    }
}
