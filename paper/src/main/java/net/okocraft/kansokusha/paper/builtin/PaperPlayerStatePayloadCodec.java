package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@NotNullByDefault
final class PaperPlayerStatePayloadCodec {

    private PaperPlayerStatePayloadCodec() {
    }

    static LocationSnapshot snapshotLocation(Location location) {
        Objects.requireNonNull(location, "location");
        var world = Objects.requireNonNull(location.getWorld(), "location.world");
        return new LocationSnapshot(
            PaperKansokusha.key(world.getKey()),
            new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
            location.getX(),
            location.getY(),
            location.getZ(),
            location.getYaw(),
            location.getPitch()
        );
    }

    static @Nullable LocationSnapshot snapshotOptionalLocation(@Nullable Location location) {
        return location == null ? null : snapshotLocation(location);
    }

    static EventPayload encodeWorldChange(Key fromWorld, LocationSnapshot to) {
        var payload = new CompoundTag();
        payload.putString("semantics", "gameplay_world_state_transition");
        payload.putString("from_world", Objects.requireNonNull(fromWorld, "fromWorld").asString());
        payload.put("to", encodeLocation(Objects.requireNonNull(to, "to")));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeTeleport(
        LocationSnapshot from,
        LocationSnapshot to,
        String cause,
        Set<String> relativeFlags
    ) {
        Objects.requireNonNull(relativeFlags, "relativeFlags");
        var payload = new CompoundTag();
        payload.putString("semantics", "successful_teleport_operation");
        payload.put("from", encodeLocation(Objects.requireNonNull(from, "from")));
        payload.put("to", encodeLocation(Objects.requireNonNull(to, "to")));
        payload.putString("cause", Objects.requireNonNull(cause, "cause"));

        var flags = new CompoundTag();
        for (var flag : relativeFlags) {
            flags.putBoolean(flag, true);
        }
        payload.put("relative_flags", flags);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeGameModeChange(String oldMode, String newMode, String cause) {
        var payload = new CompoundTag();
        payload.putString("semantics", "established_gamemode_state_transition");
        payload.putString("old_gamemode", Objects.requireNonNull(oldMode, "oldMode"));
        payload.putString("new_gamemode", Objects.requireNonNull(newMode, "newMode"));
        payload.putString("cause", Objects.requireNonNull(cause, "cause"));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeSpawnChange(
        @Nullable LocationSnapshot before,
        @Nullable LocationSnapshot after,
        boolean forced,
        String cause,
        String sourceEvent
    ) {
        var payload = new CompoundTag();
        payload.put("before", encodeOptionalLocation(before));
        payload.put("after", encodeOptionalLocation(after));
        payload.putBoolean("forced", forced);
        payload.putString("cause", Objects.requireNonNull(cause, "cause"));
        payload.putString("source_event", Objects.requireNonNull(sourceEvent, "sourceEvent"));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeDeath(
        @Nullable Component deathMessage,
        @Nullable KillerSnapshot killer,
        @Nullable String lastDamageCause,
        int droppedExp,
        int newExp,
        int newTotalExp,
        int newLevel,
        boolean keepInventory,
        boolean keepLevel
    ) {
        var payload = new CompoundTag();
        if (deathMessage != null) {
            payload.putString("death_message", PaperComponentPayloadCodec.encode(deathMessage));
        }
        if (killer != null) {
            payload.putString("killer_uuid", killer.entityId());
            payload.putString("killer_kind", killer.player() ? "player" : "entity");
            if (!killer.player()) {
                payload.putString("killer_type", killer.entityType());
            }
        }
        if (lastDamageCause != null) {
            payload.putString("last_damage_cause", lastDamageCause);
        }
        payload.putInt("dropped_exp", droppedExp);
        payload.putInt("new_exp", newExp);
        payload.putInt("new_total_exp", newTotalExp);
        payload.putInt("new_level", newLevel);
        payload.putBoolean("keep_inventory", keepInventory);
        payload.putBoolean("keep_level", keepLevel);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static @Nullable KillerSnapshot snapshotKiller(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        return new KillerSnapshot(
            entity.getUniqueId().toString(),
            entity.getType().key().asString(),
            entity instanceof Player
        );
    }

    static String enumName(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }

    private static CompoundTag encodeOptionalLocation(@Nullable LocationSnapshot location) {
        var payload = new CompoundTag();
        payload.putBoolean("present", location != null);
        if (location != null) {
            payload.put("location", encodeLocation(location));
        }
        return payload;
    }

    private static CompoundTag encodeLocation(LocationSnapshot location) {
        var payload = new CompoundTag();
        payload.putString("world", location.worldKey().asString());
        payload.putDouble("x", location.x());
        payload.putDouble("y", location.y());
        payload.putDouble("z", location.z());
        payload.putFloat("yaw", location.yaw());
        payload.putFloat("pitch", location.pitch());
        return payload;
    }

    record LocationSnapshot(
        Key worldKey,
        BlockPosition blockPosition,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {
    }

    record KillerSnapshot(String entityId, String entityType, boolean player) {
    }
}
