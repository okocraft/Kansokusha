package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPayloadNbtCodec {

    private PaperPayloadNbtCodec() {
    }

    public static EventPayload encode(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");

        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
        } catch (IOException e) {
            throw new AssertionError("Unexpected in-memory NBT encoding failure.", e);
        }
        return EventPayload.takeOwnership(bytes.toByteArray());
    }

    static CompoundTag position(BlockPosition position) {
        Objects.requireNonNull(position, "position");
        var result = new CompoundTag();
        result.putInt("x", position.x());
        result.putInt("y", position.y());
        result.putInt("z", position.z());
        return result;
    }

    public static CompoundTag decode(EventPayload payload) throws IOException {
        try (
            var input = new DataInputStream(
                Objects.requireNonNull(payload, "payload").openStream()
            )
        ) {
            return NbtIo.read(input);
        }
    }
}
