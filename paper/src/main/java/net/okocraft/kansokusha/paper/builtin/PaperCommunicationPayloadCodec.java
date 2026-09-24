package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

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
        var payload = new CompoundTag();
        payload.putString("source_kind", Objects.requireNonNull(sourceKind, "sourceKind"));
        payload.putString("source_name", Objects.requireNonNull(sourceName, "sourceName"));
        payload.putString("command", Objects.requireNonNull(originalCommand, "originalCommand"));
        return PaperPayloadNbtCodec.encode(payload);
    }
}
