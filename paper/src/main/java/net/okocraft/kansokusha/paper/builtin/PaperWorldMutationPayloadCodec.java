package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@NotNullByDefault
final class PaperWorldMutationPayloadCodec {

    private PaperWorldMutationPayloadCodec() {
    }

    static EventPayload encodeTntPrime(
        String cause,
        PaperEntityAttribution actor,
        @Nullable Key primingBlockWorldKey,
        @Nullable BlockPosition primingBlockPosition,
        @Nullable BlockData primingBlockState
    ) {
        var payload = new CompoundTag();
        payload.putString("cause", normalized(cause));
        putAttribution(payload, actor);
        putBlock(
            payload,
            "priming_block",
            "priming_block_world",
            "priming_block_state",
            primingBlockWorldKey,
            primingBlockPosition,
            primingBlockState
        );
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeExplosionBlockChange(
        BlockData preState,
        String sourceKind,
        double originX,
        double originY,
        double originZ,
        @Nullable Key sourceBlockWorldKey,
        @Nullable BlockPosition sourceBlockPosition,
        @Nullable BlockData sourceBlockState,
        PaperEntityAttribution actor
    ) {
        var payload = new CompoundTag();
        payload.put("pre_state", PaperBlockStatePayloadCodec.blockState(preState));
        payload.putString("source_kind", normalized(sourceKind));
        var origin = new CompoundTag();
        origin.putDouble("x", originX);
        origin.putDouble("y", originY);
        origin.putDouble("z", originZ);
        payload.put("origin", origin);
        putBlock(
            payload,
            "source_block",
            "source_block_world",
            "source_block_state",
            sourceBlockWorldKey,
            sourceBlockPosition,
            sourceBlockState
        );
        putAttribution(payload, actor);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodePistonMove(
        BlockPosition from,
        BlockPosition to,
        BlockData state,
        Key pistonWorldKey,
        BlockPosition pistonOrigin,
        String direction,
        String action
    ) {
        var payload = new CompoundTag();
        payload.put("from", PaperPayloadNbtCodec.position(from));
        payload.put("to", PaperPayloadNbtCodec.position(to));
        payload.put("state", PaperBlockStatePayloadCodec.blockState(state));
        payload.putString("piston_world", pistonWorldKey.asString());
        payload.put("piston_origin", PaperPayloadNbtCodec.position(pistonOrigin));
        payload.putString("direction", normalized(direction));
        payload.putString("action", normalized(action));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeEntityBlockChange(
        BlockData before,
        BlockData to,
        UUID actorId,
        String actorType
    ) {
        var payload = new CompoundTag();
        payload.put("before", PaperBlockStatePayloadCodec.blockState(before));
        payload.put("to", PaperBlockStatePayloadCodec.blockState(to));
        payload.putString("actor_entity_uuid", actorId.toString());
        payload.putString("actor_entity_type", actorType);
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static void putAttribution(
        CompoundTag payload,
        PaperEntityAttribution attribution
    ) {
        putUuid(payload, "actor_entity_uuid", attribution.entityId());
        putString(payload, "actor_entity_type", attribution.entityType());
        putUuid(payload, "source_entity_uuid", attribution.sourceEntityId());
        putString(payload, "source_entity_type", attribution.sourceEntityType());
        putUuid(payload, "shooter_entity_uuid", attribution.shooterEntityId());
        putString(payload, "shooter_entity_type", attribution.shooterEntityType());
        putUuid(payload, "owner_uuid", attribution.ownerId());

        var shooterBlock = attribution.shooterBlock();
        if (shooterBlock != null) {
            payload.putString("shooter_block_world", shooterBlock.worldKey().asString());
            payload.put("shooter_block", PaperPayloadNbtCodec.position(shooterBlock.position()));
        }
    }

    private static void putBlock(
        CompoundTag payload,
        String positionKey,
        String worldKey,
        String stateKey,
        @Nullable Key world,
        @Nullable BlockPosition blockPosition,
        @Nullable BlockData blockState
    ) {
        if (world == null && blockPosition == null && blockState == null) {
            return;
        }
        if (world == null || blockPosition == null || blockState == null) {
            throw new IllegalArgumentException("Block world, position, and state must all be present");
        }
        payload.putString(worldKey, world.asString());
        payload.put(positionKey, PaperPayloadNbtCodec.position(blockPosition));
        payload.put(stateKey, PaperBlockStatePayloadCodec.blockState(blockState));
    }

    private static void putUuid(CompoundTag payload, String key, @Nullable UUID value) {
        if (value != null) {
            payload.putString(key, value.toString());
        }
    }

    private static void putString(CompoundTag payload, String key, @Nullable String value) {
        if (value != null) {
            payload.putString(key, value);
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNull(value, "value").toLowerCase(Locale.ROOT);
    }
}
