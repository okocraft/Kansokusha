package net.okocraft.kansokusha.common.builtin;

import net.okocraft.kansokusha.api.event.EventPayload;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class BuiltInEventPayloadCodec {

    private BuiltInEventPayloadCodec() {
    }

    public static EventPayload encodeBlockBreak(String blockData) {
        return encode(writer -> writer.writeString(Objects.requireNonNull(blockData, "blockData")));
    }

    public static EventPayload encodeBlockPlace(
        String replacedBlockData,
        String placedBlockData
    ) {
        return encode(writer -> {
            writer.writeString(Objects.requireNonNull(replacedBlockData, "replacedBlockData"));
            writer.writeString(Objects.requireNonNull(placedBlockData, "placedBlockData"));
        });
    }

    public static EventPayload encodeServerConnected(@Nullable String previousServerKey) {
        return encode(writer -> writer.writeNullableString(previousServerKey));
    }

    static BlockBreakPayload decodeBlockBreak(EventPayload payload) throws IOException {
        var reader = new Reader(payload);
        var result = new BlockBreakPayload(reader.readString());
        reader.requireEnd();
        return result;
    }

    static BlockPlacePayload decodeBlockPlace(EventPayload payload) throws IOException {
        var reader = new Reader(payload);
        var result = new BlockPlacePayload(reader.readString(), reader.readString());
        reader.requireEnd();
        return result;
    }

    static ServerConnectedPayload decodeServerConnected(EventPayload payload) throws IOException {
        var reader = new Reader(payload);
        var result = new ServerConnectedPayload(reader.readNullableString());
        reader.requireEnd();
        return result;
    }

    private static EventPayload encode(Encoder encoder) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            encoder.encode(new Writer(output));
        } catch (IOException e) {
            throw new AssertionError("Unexpected in-memory payload encoding failure.", e);
        }
        return EventPayload.copyOf(bytes.toByteArray());
    }

    @FunctionalInterface
    private interface Encoder {

        void encode(Writer writer) throws IOException;
    }

    record BlockBreakPayload(String blockData) {
    }

    record BlockPlacePayload(
        String replacedBlockData,
        String placedBlockData
    ) {
    }

    record ServerConnectedPayload(@Nullable String previousServerKey) {
    }

    private record Writer(DataOutputStream output) {

        private void writeString(String value) throws IOException {
            var bytes = value.getBytes(StandardCharsets.UTF_8);
            this.output.writeInt(bytes.length);
            this.output.write(bytes);
        }

        private void writeNullableString(@Nullable String value) throws IOException {
            if (value == null) {
                this.output.writeInt(-1);
                return;
            }
            this.writeString(value);
        }
    }

    private static final class Reader {

        private final DataInputStream input;

        private Reader(EventPayload payload) {
            this.input = new DataInputStream(
                new ByteArrayInputStream(Objects.requireNonNull(payload, "payload").copyBytes())
            );
        }

        private String readString() throws IOException {
            var value = this.readNullableString();
            if (value == null) {
                throw new IOException("Expected non-null payload string.");
            }
            return value;
        }

        private @Nullable String readNullableString() throws IOException {
            var length = this.input.readInt();
            if (length == -1) {
                return null;
            }
            if (length < -1) {
                throw new IOException("Invalid payload string length: " + length);
            }

            var bytes = this.input.readNBytes(length);
            if (bytes.length != length) {
                throw new EOFException(
                    "Payload ended before string bytes were complete: expected "
                        + length + ", got " + bytes.length
                );
            }

            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new IOException("Payload contains invalid UTF-8.", e);
            }
        }

        private void requireEnd() throws IOException {
            if (this.input.available() != 0) {
                throw new IOException("Payload contains trailing bytes.");
            }
        }
    }
}
