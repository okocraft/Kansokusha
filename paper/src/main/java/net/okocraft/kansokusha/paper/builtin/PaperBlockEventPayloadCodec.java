package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@ApiStatus.Internal
@NotNullByDefault
final class PaperBlockEventPayloadCodec {

    private PaperBlockEventPayloadCodec() {
    }

    static EventPayload encodeIgnite(
        BlockData preState,
        String cause,
        @Nullable BlockPosition sourcePosition,
        @Nullable BlockData sourceState,
        @Nullable UUID entityId,
        @Nullable String entityType
    ) {
        var payload = new CompoundTag();
        payload.put("pre_state", PaperBlockStatePayloadCodec.blockState(preState));
        payload.putString("cause", Objects.requireNonNull(cause, "cause"));
        putSourceBlock(payload, sourcePosition, sourceState);
        if (entityId != null) {
            payload.putString("actor_entity_uuid", entityId.toString());
        }
        if (entityType != null) {
            payload.putString("actor_entity_type", entityType);
        }
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeBurn(
        BlockData preState,
        @Nullable BlockPosition sourcePosition,
        @Nullable BlockData sourceState
    ) {
        var payload = new CompoundTag();
        payload.put("pre_state", PaperBlockStatePayloadCodec.blockState(preState));
        putSourceBlock(payload, sourcePosition, sourceState);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeNaturalChange(
        BlockData preState,
        BlockData postState,
        String sourceEvent,
        @Nullable String cause,
        @Nullable BlockPosition sourcePosition
    ) {
        return encodeNaturalChange(
            PaperBlockStatePayloadCodec.blockState(preState),
            PaperBlockStatePayloadCodec.blockState(postState),
            sourceEvent,
            cause,
            sourcePosition
        );
    }

    static EventPayload encodeLeavesDecay(BlockData preState) {
        return encodeNaturalChange(
            PaperBlockStatePayloadCodec.blockState(preState),
            PaperBlockStatePayloadCodec.airBlockState(),
            "leaves_decay",
            null,
            null
        );
    }

    static EventPayload encodeFluidChange(
        String fluidKind,
        BlockPosition sourcePosition,
        BlockPosition destinationPosition
    ) {
        var payload = new CompoundTag();
        payload.putString(
            "fluid",
            Objects.requireNonNull(fluidKind, "fluidKind").toLowerCase(Locale.ROOT)
        );
        payload.put("source", position(sourcePosition));
        payload.put("destination", position(destinationPosition));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeSpongeAbsorb(
        BlockData preState,
        BlockPosition spongeOrigin
    ) {
        var payload = new CompoundTag();
        payload.put("pre_state", PaperBlockStatePayloadCodec.blockState(preState));
        payload.put("sponge_origin", position(spongeOrigin));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeFertilize(
        BlockData preState,
        BlockData postState,
        String sourceEvent,
        BlockPosition sourcePosition
    ) {
        var payload = new CompoundTag();
        payload.put("pre_state", PaperBlockStatePayloadCodec.blockState(preState));
        payload.put("post_state", PaperBlockStatePayloadCodec.blockState(postState));
        payload.putString("source_event", Objects.requireNonNull(sourceEvent, "sourceEvent"));
        payload.put("source", position(sourcePosition));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeCauldronLevelChange(
        BlockData oldState,
        BlockData newState,
        String reason,
        String actorKind,
        @Nullable UUID entityId,
        @Nullable String entityType
    ) {
        var payload = new CompoundTag();
        payload.put("old_state", PaperBlockStatePayloadCodec.blockState(oldState));
        payload.put("new_state", PaperBlockStatePayloadCodec.blockState(newState));
        payload.putString("reason", Objects.requireNonNull(reason, "reason"));
        payload.putString("actor_kind", Objects.requireNonNull(actorKind, "actorKind"));
        if (entityId != null) {
            payload.putString("actor_entity_uuid", entityId.toString());
        }
        if (entityType != null) {
            payload.putString("actor_entity_type", entityType);
        }
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static EventPayload encodeNaturalChange(
        CompoundTag preState,
        CompoundTag postState,
        String sourceEvent,
        @Nullable String cause,
        @Nullable BlockPosition sourcePosition
    ) {
        var payload = new CompoundTag();
        payload.put("pre_state", preState);
        payload.put("post_state", postState);
        payload.putString("source_event", Objects.requireNonNull(sourceEvent, "sourceEvent"));
        if (cause != null) {
            payload.putString("cause", cause);
        }
        if (sourcePosition != null) {
            payload.put("source", position(sourcePosition));
        }
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static void putSourceBlock(
        CompoundTag payload,
        @Nullable BlockPosition sourcePosition,
        @Nullable BlockData sourceState
    ) {
        if (sourcePosition == null && sourceState == null) {
            return;
        }
        if (sourcePosition == null || sourceState == null) {
            throw new IllegalArgumentException("sourcePosition and sourceState must both be present");
        }
        payload.put("source", position(sourcePosition));
        payload.put("source_state", PaperBlockStatePayloadCodec.blockState(sourceState));
    }

    private static CompoundTag position(BlockPosition position) {
        Objects.requireNonNull(position, "position");
        var result = new CompoundTag();
        result.putInt("x", position.x());
        result.putInt("y", position.y());
        result.putInt("z", position.z());
        return result;
    }
}
