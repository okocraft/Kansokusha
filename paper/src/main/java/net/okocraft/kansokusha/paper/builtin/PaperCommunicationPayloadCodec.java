package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperCommunicationPayloadCodec {

    private PaperCommunicationPayloadCodec() {
    }

    static EventPayload encodeChat(Component originalMessage) {
        var payload = new CompoundTag();
        payload.putString(
            "message",
            PaperComponentPayloadCodec.encode(
                Objects.requireNonNull(originalMessage, "originalMessage")
            )
        );
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodePlayerCommand(String originalCommand) {
        var payload = new CompoundTag();
        payload.putString("command", Objects.requireNonNull(originalCommand, "originalCommand"));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeServerCommand(
        String sourceKind,
        String sourceName,
        String originalCommand
    ) {
        return encodeServerCommand(sourceKind, sourceName, originalCommand, null);
    }

    /**
     * @param attempts the number of runs summed into the event; {@code null} for a single recorded input
     */
    static EventPayload encodeServerCommand(
        String sourceKind,
        String sourceName,
        String originalCommand,
        @Nullable Integer attempts
    ) {
        var payload = new CompoundTag();
        payload.putString("source_kind", Objects.requireNonNull(sourceKind, "sourceKind"));
        payload.putString("source_name", Objects.requireNonNull(sourceName, "sourceName"));
        payload.putString("command", Objects.requireNonNull(originalCommand, "originalCommand"));
        // Added after the first events were recorded; events without it describe a single input.
        if (attempts != null) {
            payload.putInt("attempt_count", attempts);
        }
        return PaperPayloadNbtCodec.encode(payload);
    }
}
