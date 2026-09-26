package net.okocraft.kansokusha.velocity.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@ApiStatus.Internal
@NotNullByDefault
public final class VelocityServerConnectedPayloadCodec {

    private VelocityServerConnectedPayloadCodec() {
    }

    public static EventPayload encode(@Nullable Key previousServerKey) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            if (previousServerKey == null) {
                output.writeInt(-1);
            } else {
                var encoded = previousServerKey.asString().getBytes(StandardCharsets.UTF_8);
                output.writeInt(encoded.length);
                output.write(encoded);
            }
        } catch (IOException e) {
            throw new AssertionError("Unexpected in-memory Velocity payload encoding failure.", e);
        }
        return EventPayload.takeOwnership(bytes.toByteArray());
    }

    static Optional<Key> decode(EventPayload payload) throws IOException {
        try (
            var input = new DataInputStream(
                payload.openStream()
            )
        ) {
            var length = input.readInt();
            if (length == -1) {
                if (input.read() != -1) {
                    throw new IOException("Trailing bytes after null previous server key.");
                }
                return Optional.empty();
            }
            if (length < 0) {
                throw new IOException("Negative previous server key length: " + length);
            }

            var encoded = input.readNBytes(length);
            if (encoded.length != length || input.read() != -1) {
                throw new IOException("Malformed previous server key payload.");
            }
            return Optional.of(Key.key(new String(encoded, StandardCharsets.UTF_8)));
        }
    }
}
