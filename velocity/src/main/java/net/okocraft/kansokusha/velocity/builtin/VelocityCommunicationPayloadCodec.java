package net.okocraft.kansokusha.velocity.builtin;

import net.okocraft.kansokusha.api.event.EventPayload;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class VelocityCommunicationPayloadCodec {

    private VelocityCommunicationPayloadCodec() {
    }

    static EventPayload encodeChat(String originalMessage) {
        Objects.requireNonNull(originalMessage, "originalMessage");
        return encode(output -> writeString(output, originalMessage));
    }

    static EventPayload encodeCommand(
        String sourceKind,
        String sourceName,
        String originalCommand
    ) {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(originalCommand, "originalCommand");
        return encode(output -> {
            writeString(output, sourceKind);
            writeString(output, sourceName);
            writeString(output, originalCommand);
        });
    }

    static String decodeChat(EventPayload payload) throws IOException {
        try (var input = input(payload)) {
            var message = readString(input);
            requireEnd(input);
            return message;
        }
    }

    static CommandPayload decodeCommand(EventPayload payload) throws IOException {
        try (var input = input(payload)) {
            var result = new CommandPayload(
                readString(input),
                readString(input),
                readString(input)
            );
            requireEnd(input);
            return result;
        }
    }

    private static EventPayload encode(Encoder encoder) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            encoder.encode(output);
        } catch (IOException e) {
            throw new AssertionError("Unexpected in-memory Velocity payload encoding failure.", e);
        }
        return EventPayload.copyOf(bytes.toByteArray());
    }

    private static DataInputStream input(EventPayload payload) {
        return new DataInputStream(
            new ByteArrayInputStream(Objects.requireNonNull(payload, "payload").copyBytes())
        );
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        output.writeInt(value.length());
        for (var index = 0; index < value.length(); index++) {
            output.writeChar(value.charAt(index));
        }
    }

    private static String readString(DataInputStream input) throws IOException {
        var length = input.readInt();
        if (length < 0) {
            throw new IOException("Negative Velocity communication string length: " + length);
        }

        var value = new StringBuilder(length);
        for (var index = 0; index < length; index++) {
            value.append(input.readChar());
        }
        return value.toString();
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.read() != -1) {
            throw new IOException("Trailing bytes in Velocity communication payload.");
        }
    }

    @FunctionalInterface
    private interface Encoder {

        void encode(DataOutputStream output) throws IOException;
    }

    record CommandPayload(String sourceKind, String sourceName, String command) {
    }
}
