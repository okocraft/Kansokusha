package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@NotNullByDefault
final class PaperAdministrativePayloadCodec {

    private PaperAdministrativePayloadCodec() {
    }

    static EventPayload encodeGameRuleChange(
        String gameRule,
        String before,
        String after,
        @Nullable PaperAdministrativeSource.Snapshot source
    ) {
        var payload = new CompoundTag();
        payload.putString("game_rule", Objects.requireNonNull(gameRule, "gameRule"));
        payload.putString("before", Objects.requireNonNull(before, "before"));
        payload.putString("after", Objects.requireNonNull(after, "after"));
        payload.putString("source_event", "world_gamerule_change");
        putSource(payload, source);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeDifficultyChange(
        String before,
        String after,
        @Nullable PaperAdministrativeSource.Snapshot source
    ) {
        var payload = new CompoundTag();
        payload.putString("before", Objects.requireNonNull(before, "before"));
        payload.putString("after", Objects.requireNonNull(after, "after"));
        payload.putString("source_event", "world_difficulty_change");
        putSource(payload, source);
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeBorderCenterChange(
        CenterSnapshot before,
        CenterSnapshot after
    ) {
        var payload = new CompoundTag();
        payload.putString("action", "center");
        payload.put("before", center(Objects.requireNonNull(before, "before")));
        payload.put("after", center(Objects.requireNonNull(after, "after")));
        payload.putString("source_event", "world_border_center_change");
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeBorderBoundsChange(
        double before,
        double after,
        String transitionType,
        long durationTicks
    ) {
        var payload = new CompoundTag();
        payload.putString("action", "bounds");
        payload.putDouble("before_size", before);
        payload.putDouble("after_size", after);
        payload.putString(
            "transition_type",
            Objects.requireNonNull(transitionType, "transitionType")
        );
        payload.putLong("transition_duration_ticks", durationTicks);
        payload.putString("source_event", "world_border_bounds_change");
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeWorldSpawnChange(
        PaperPlayerStatePayloadCodec.LocationSnapshot before,
        PaperPlayerStatePayloadCodec.LocationSnapshot after
    ) {
        var payload = new CompoundTag();
        payload.putString("scope", "world");
        payload.put("before", location(Objects.requireNonNull(before, "before")));
        payload.put("after", location(Objects.requireNonNull(after, "after")));
        payload.putString("source_event", "spawn_change");
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeWhitelistToggle(boolean before, boolean after) {
        var payload = new CompoundTag();
        payload.putString("action", "global_toggle");
        payload.putBoolean("before_enabled", before);
        payload.putBoolean("after_enabled", after);
        payload.putString("source_event", "whitelist_toggle");
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeWhitelistProfileChange(
        boolean added,
        boolean before,
        @Nullable UUID profileId,
        @Nullable String profileName
    ) {
        var payload = new CompoundTag();
        payload.putString("action", added ? "profile_add" : "profile_remove");
        payload.putBoolean("before_whitelisted", before);
        payload.putBoolean("after_whitelisted", added);
        if (profileId != null) {
            payload.putString("profile_uuid", profileId.toString());
        }
        if (profileName != null) {
            payload.putString("profile_name", profileName);
        }
        payload.putString("source_event", "whitelist_state_update");
        return PaperPayloadNbtCodec.encode(payload);
    }

    static CenterSnapshot center(double x, double z) {
        return new CenterSnapshot(x, z);
    }

    static String enumName(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }

    private static CompoundTag center(CenterSnapshot center) {
        var payload = new CompoundTag();
        payload.putDouble("x", center.x());
        payload.putDouble("z", center.z());
        return payload;
    }

    private static CompoundTag location(PaperPlayerStatePayloadCodec.LocationSnapshot location) {
        var payload = new CompoundTag();
        payload.putString("world", location.worldKey().asString());
        payload.putDouble("x", location.x());
        payload.putDouble("y", location.y());
        payload.putDouble("z", location.z());
        payload.putFloat("yaw", location.yaw());
        payload.putFloat("pitch", location.pitch());
        return payload;
    }

    private static void putSource(
        CompoundTag payload,
        @Nullable PaperAdministrativeSource.Snapshot source
    ) {
        payload.putBoolean("source_present", source != null);
        if (source == null) {
            return;
        }

        var sourceTag = new CompoundTag();
        var sender = source.sender();
        sourceTag.putString("sender_kind", sender.kind());
        sourceTag.putString("sender_name", sender.name());
        if (sender.uniqueId() != null) {
            sourceTag.putString("sender_uuid", sender.uniqueId());
        }
        if (sender.entityType() != null) {
            sourceTag.putString("sender_entity_type", sender.entityType());
        }

        var executor = source.executor();
        if (executor != null) {
            sourceTag.putString("executor_kind", executor.kind());
            sourceTag.putString("executor_uuid", executor.uniqueId());
            sourceTag.putString("executor_entity_type", executor.entityType());
        }

        payload.put("source", sourceTag);
    }

    record CenterSnapshot(double x, double z) {
    }
}
