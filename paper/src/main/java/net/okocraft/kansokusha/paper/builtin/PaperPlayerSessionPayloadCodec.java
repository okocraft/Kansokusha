package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerSessionPayloadCodec {

    private PaperPlayerSessionPayloadCodec() {
    }

    static EventPayload encodeJoin(String username) {
        var payload = new CompoundTag();
        payload.putString("username", Objects.requireNonNull(username, "username"));
        return PaperPayloadNbtCodec.encode(payload);
    }

    /**
     * Reads the generation-1 join username.
     *
     * <p>The field is optional for backward compatibility: generation-1 rows written before
     * player-name observation used an empty compound.</p>
     */
    static @Nullable String decodeJoinUsername(EventPayload payload) throws IOException {
        return PaperPayloadNbtCodec.decode(payload).getString("username").orElse(null);
    }

    static EventPayload encodeQuit(PlayerQuitEvent.QuitReason reason) {
        var payload = new CompoundTag();
        payload.putString("reason", enumName(reason));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeKick(PlayerKickEvent.Cause cause, Component reason) {
        var payload = new CompoundTag();
        payload.putString("cause", enumName(cause));
        payload.putString(
            "reason",
            PaperComponentPayloadCodec.encode(Objects.requireNonNull(reason, "reason"))
        );
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static String enumName(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }
}
