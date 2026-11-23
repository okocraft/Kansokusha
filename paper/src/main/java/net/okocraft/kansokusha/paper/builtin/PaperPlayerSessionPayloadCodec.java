package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerSessionPayloadCodec {

    private PaperPlayerSessionPayloadCodec() {
    }

    static EventPayload encodeJoin(String username, @Nullable InetSocketAddress remoteAddress) {
        var payload = new CompoundTag();
        payload.putString("username", Objects.requireNonNull(username, "username"));
        // The address and port are optional in stored login payloads.
        if (remoteAddress != null) {
            payload.putString("remote_address", remoteAddress.getHostString());
            payload.putInt("remote_port", remoteAddress.getPort());
        }
        return PaperPayloadNbtCodec.encode(payload);
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
