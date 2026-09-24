package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

@NotNullByDefault
final class PaperEntityLifecyclePayloadCodec {

    private PaperEntityLifecyclePayloadCodec() {
    }

    static EventPayload encodePlacement(
        Entity entity,
        Location location,
        @Nullable Entity actor,
        @Nullable EquipmentSlot hand,
        @Nullable ItemStack usedItem,
        String sourceEvent,
        @Nullable Block attachedBlock,
        @Nullable BlockFace attachedFace
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(sourceEvent, "sourceEvent");

        var world = Objects.requireNonNull(location.getWorld(), "location.world");
        var payload = new CompoundTag();
        payload.put("entity", snapshotEntity(entity));
        payload.putString("world", world.getKey().asString());
        payload.put("position", snapshotPosition(location));
        payload.put("actor", snapshotEntity(actor));
        payload.putString("hand", enumName(hand));
        payload.put(
            "used_item",
            PaperItemStackPayloadCodec.encode(
                usedItem == null ? ItemStack.empty() : usedItem
            )
        );
        payload.putString("source_event", sourceEvent);

        if (attachedBlock != null) {
            var block = new CompoundTag();
            block.putInt("x", attachedBlock.getX());
            block.putInt("y", attachedBlock.getY());
            block.putInt("z", attachedBlock.getZ());
            payload.put("attached_block", block);
        }
        if (attachedFace != null) {
            payload.putString("attached_face", enumName(attachedFace));
        }

        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeBreak(
        Entity brokenEntity,
        Location location,
        Entity breaker,
        String cause,
        DamageSource damageSource,
        String sourceEvent
    ) {
        Objects.requireNonNull(brokenEntity, "brokenEntity");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(breaker, "breaker");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(damageSource, "damageSource");
        Objects.requireNonNull(sourceEvent, "sourceEvent");

        var world = Objects.requireNonNull(location.getWorld(), "location.world");
        var payload = new CompoundTag();
        payload.put("broken_entity", snapshotEntity(brokenEntity));
        payload.putString("world", world.getKey().asString());
        payload.put("position", snapshotPosition(location));
        payload.put("breaker", snapshotEntity(breaker));
        payload.putString("cause", cause.toLowerCase(Locale.ROOT));
        payload.put("source", snapshotDamageSource(damageSource));
        payload.putString("source_event", sourceEvent);
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static CompoundTag snapshotEntity(@Nullable Entity entity) {
        var snapshot = new CompoundTag();
        if (entity != null) {
            snapshot.putString("uuid", entity.getUniqueId().toString());
            snapshot.putString(
                "type",
                entity.getType().name().toLowerCase(Locale.ROOT)
            );
        }
        return snapshot;
    }

    private static CompoundTag snapshotPosition(Location location) {
        var position = new CompoundTag();
        position.putDouble("x", location.getX());
        position.putDouble("y", location.getY());
        position.putDouble("z", location.getZ());
        return position;
    }

    private static CompoundTag snapshotDamageSource(DamageSource damageSource) {
        var source = new CompoundTag();
        source.putString(
            "damage_type",
            damageSource.getDamageType().getKey().asString()
        );
        source.putBoolean("indirect", damageSource.isIndirect());
        source.put("direct_entity", snapshotEntity(damageSource.getDirectEntity()));
        source.put("causing_entity", snapshotEntity(damageSource.getCausingEntity()));
        return source;
    }

    private static String enumName(@Nullable Enum<?> value) {
        return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT);
    }
}
