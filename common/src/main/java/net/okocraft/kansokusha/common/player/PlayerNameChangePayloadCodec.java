package net.okocraft.kansokusha.common.player;

import net.okocraft.kansokusha.api.event.EventPayload;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Generation-1 payload codec for {@code kansokusha:player_name_change}.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PlayerNameChangePayloadCodec {

    public static EventPayload encode(String previousName, String newName) {
        Objects.requireNonNull(previousName, "previousName");
        Objects.requireNonNull(newName, "newName");

        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            writeString(output, previousName);
            writeString(output, newName);
        } catch (IOException e) {
            throw new AssertionError("Unexpected in-memory player-name payload encoding failure.", e);
        }
        return EventPayload.takeOwnership(bytes.toByteArray());
    }

    public static NameChangePayload decode(EventPayload payload) throws IOException {
        try (var input = new DataInputStream(
            Objects.requireNonNull(payload, "payload").openStream()
        )) {
            var previousName = readString(input);
            var newName = readString(input);
            if (input.read() != -1) {
                throw new IOException("Trailing bytes in player-name-change payload.");
            }
            return new NameChangePayload(previousName, newName);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        var encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input) throws IOException {
        var length = input.readInt();
        if (length < 0) {
            throw new IOException("Negative player-name string length: " + length);
        }
        var encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new IOException("Truncated player-name string payload.");
        }
        return new String(encoded, StandardCharsets.UTF_8);
    }

    public record NameChangePayload(String previousName, String newName) {

        public NameChangePayload {
            Objects.requireNonNull(previousName, "previousName");
            Objects.requireNonNull(newName, "newName");
        }
    }

    private PlayerNameChangePayloadCodec() {
        throw new UnsupportedOperationException();
    }
}
