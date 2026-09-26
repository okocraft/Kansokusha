package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@ApiStatus.Internal
@NotNullByDefault
final class PaperEntityEventPayloadCodec {

    private PaperEntityEventPayloadCodec() {
    }

    static EventPayload encodePlacement(
        EntitySnapshot entity,
        @Nullable EntitySnapshot actor,
        @Nullable EquipmentSlot hand,
        CompoundTag usedItem,
        String sourceEvent,
        @Nullable HangingPlacementSnapshot hanging
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(usedItem, "usedItem");
        Objects.requireNonNull(sourceEvent, "sourceEvent");

        var payload = new CompoundTag();
        putEntity(payload, "entity", entity);
        if (actor != null) {
            putEntity(payload, "actor", actor);
        }
        if (hand != null) {
            payload.putString("hand", enumName(hand));
        }
        payload.put("used_item", usedItem.copy());
        payload.putString("source_event", sourceEvent);

        if (hanging != null) {
            var context = new CompoundTag();
            var attachedBlock = new CompoundTag();
            attachedBlock.putInt("x", hanging.attachedX());
            attachedBlock.putInt("y", hanging.attachedY());
            attachedBlock.putInt("z", hanging.attachedZ());
            context.put("attached_block", attachedBlock);
            context.putString("face", hanging.face());
            payload.put("hanging", context);
        }

        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeBreak(
        EntitySnapshot brokenEntity,
        EntitySnapshot breaker,
        String cause,
        String damageType,
        boolean indirectDamage,
        String sourceEvent
    ) {
        Objects.requireNonNull(brokenEntity, "brokenEntity");
        Objects.requireNonNull(breaker, "breaker");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(damageType, "damageType");
        Objects.requireNonNull(sourceEvent, "sourceEvent");

        var payload = new CompoundTag();
        putEntity(payload, "entity", brokenEntity);
        putEntity(payload, "breaker", breaker);
        payload.putString("cause", cause);
        payload.putString("damage_type", damageType);
        payload.putBoolean("indirect_damage", indirectDamage);
        payload.putString("source_event", sourceEvent);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EntitySnapshot snapshotEntity(org.bukkit.entity.Entity entity) {
        Objects.requireNonNull(entity, "entity");
        var location = entity.getLocation();
        return new EntitySnapshot(
            entity.getUniqueId(),
            entity.getType().getKey().toString(),
            PaperKansokusha.key(entity.getWorld().getKey()),
            location.getX(),
            location.getY(),
            location.getZ()
        );
    }

    private static void putEntity(CompoundTag payload, String key, EntitySnapshot snapshot) {
        var entity = new CompoundTag();
        entity.putString("uuid", snapshot.uuid().toString());
        entity.putString("type", snapshot.type());
        entity.putString("world", snapshot.worldKey().asString());

        var position = new CompoundTag();
        position.putDouble("x", snapshot.x());
        position.putDouble("y", snapshot.y());
        position.putDouble("z", snapshot.z());
        entity.put("position", position);

        payload.put(key, entity);
    }

    private static String enumName(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }

    record EntitySnapshot(
        UUID uuid,
        String type,
        Key worldKey,
        double x,
        double y,
        double z
    ) {

        EntitySnapshot {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(worldKey, "worldKey");
        }
    }

    record HangingPlacementSnapshot(
        int attachedX,
        int attachedY,
        int attachedZ,
        String face
    ) {

        HangingPlacementSnapshot {
            Objects.requireNonNull(face, "face");
        }
    }
}
