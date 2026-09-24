package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.bukkit.Rotation;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
final class PaperEntityStateChangePayloadCodec {

    private static final String COMPONENT_KEY = "component";

    private PaperEntityStateChangePayloadCodec() {
    }

    static EventPayload encodeArmorStandManipulate(
        PaperEntityEventPayloadCodec.EntitySnapshot target,
        EquipmentSlot equipmentSlot,
        EquipmentSlot hand,
        CompoundTag playerItemBefore,
        CompoundTag armorStandItemBefore
    ) {
        var payload = new CompoundTag();
        putEntity(payload, "target", target);
        payload.putString("equipment_slot", enumName(equipmentSlot));
        payload.putString("hand", enumName(hand));
        payload.put("player_item_before", playerItemBefore.copy());
        payload.put("armor_stand_item_before", armorStandItemBefore.copy());
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeLeashChange(
        String action,
        PaperEntityEventPayloadCodec.EntitySnapshot target,
        PaperEntityEventPayloadCodec.EntitySnapshot holder,
        String reason,
        EquipmentSlot hand,
        boolean dropLeash
    ) {
        var payload = new CompoundTag();
        payload.putString("action", Objects.requireNonNull(action, "action"));
        putEntity(payload, "target", target);
        putEntity(payload, "holder", holder);
        payload.putString("reason", Objects.requireNonNull(reason, "reason"));
        payload.putString("hand", enumName(hand));
        payload.putBoolean("drop_leash", dropLeash);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeItemFrameChange(
        PaperEntityEventPayloadCodec.EntitySnapshot target,
        String action,
        CompoundTag itemBefore,
        CompoundTag itemAfter,
        Rotation rotationBefore,
        Rotation rotationAfter,
        boolean fixedBefore,
        boolean fixedAfter
    ) {
        var payload = new CompoundTag();
        putEntity(payload, "target", target);
        payload.putString("action", Objects.requireNonNull(action, "action"));
        payload.put("item_before", itemBefore.copy());
        payload.put("item_after", itemAfter.copy());
        payload.putString("rotation_before", enumName(rotationBefore));
        payload.putString("rotation_after", enumName(rotationAfter));
        payload.putBoolean("fixed_before", fixedBefore);
        payload.putBoolean("fixed_after", fixedAfter);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeTame(
        PaperEntityEventPayloadCodec.EntitySnapshot target,
        AnimalTamer owner
    ) {
        Objects.requireNonNull(owner, "owner");

        var payload = new CompoundTag();
        putEntity(payload, "target", target);

        var newOwner = new CompoundTag();
        newOwner.putString("uuid", owner.getUniqueId().toString());
        if (owner instanceof Entity ownerEntity) {
            putEntityFields(
                newOwner,
                PaperEntityEventPayloadCodec.snapshotEntity(ownerEntity)
            );
        }
        payload.put("new_owner", newOwner);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeNameChange(
        PaperEntityEventPayloadCodec.EntitySnapshot target,
        @Nullable Component previousCustomName,
        @Nullable Component newCustomName,
        boolean persistent
    ) {
        var payload = new CompoundTag();
        putEntity(payload, "target", target);
        payload.put("previous_custom_name", encodeComponent(previousCustomName));
        payload.put("new_custom_name", encodeComponent(newCustomName));
        payload.putBoolean("persistent", persistent);
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static CompoundTag encodeComponent(@Nullable Component component) {
        var encoded = new CompoundTag();
        if (component != null) {
            encoded.putString(COMPONENT_KEY, PaperComponentPayloadCodec.encode(component));
        }
        return encoded;
    }

    private static void putEntity(
        CompoundTag payload,
        String key,
        PaperEntityEventPayloadCodec.EntitySnapshot snapshot
    ) {
        var entity = new CompoundTag();
        putEntityFields(entity, snapshot);
        payload.put(key, entity);
    }

    private static void putEntityFields(
        CompoundTag entity,
        PaperEntityEventPayloadCodec.EntitySnapshot snapshot
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(snapshot, "snapshot");

        entity.putString("uuid", snapshot.uuid().toString());
        entity.putString("type", snapshot.type());
        entity.putString("world", snapshot.worldKey().asString());

        var position = new CompoundTag();
        position.putDouble("x", snapshot.x());
        position.putDouble("y", snapshot.y());
        position.putDouble("z", snapshot.z());
        entity.put("position", position);
    }

    private static String enumName(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }
}
