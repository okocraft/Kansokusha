package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class VelocityPlayerSessionPayloadCodec {

    private static final GsonComponentSerializer COMPONENT_SERIALIZER =
        GsonComponentSerializer.gson();

    private VelocityPlayerSessionPayloadCodec() {
    }

    static EventPayload encodePostLogin(
        String username,
        InetSocketAddress remoteAddress,
        @Nullable InetSocketAddress virtualHost,
        @Nullable String rawVirtualHost
    ) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(remoteAddress, "remoteAddress");

        return encode(output -> {
            writeString(output, username);
            writeAddress(output, remoteAddress);
            if (virtualHost == null) {
                output.writeBoolean(false);
            } else {
                output.writeBoolean(true);
                writeAddress(output, virtualHost);
            }
            writeNullableString(output, rawVirtualHost);
        });
    }

    static EventPayload encodeDisconnect(
        String username,
        DisconnectEvent.LoginStatus loginStatus,
        @Nullable Key currentBackendKey
    ) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(loginStatus, "loginStatus");

        return encode(output -> {
            writeString(output, username);
            writeString(output, enumName(loginStatus));
            writeNullableString(
                output,
                currentBackendKey == null ? null : currentBackendKey.asString()
            );
        });
    }

    static EventPayload encodeBackendKick(
        @Nullable Component originalReason,
        boolean duringServerConnect,
        ProxyAction action,
        @Nullable Key redirectTarget,
        @Nullable Component message
    ) {
        Objects.requireNonNull(action, "action");

        return encode(output -> {
            writeNullableString(output, serializeComponent(originalReason));
            output.writeBoolean(duringServerConnect);
            writeString(output, enumName(action));
            writeNullableString(
                output,
                redirectTarget == null ? null : redirectTarget.asString()
            );
            writeNullableString(output, serializeComponent(message));
        });
    }

    static PostLoginPayload decodePostLogin(EventPayload payload) throws IOException {
        try (var input = input(payload)) {
            var username = readString(input);
            var remoteAddress = readAddress(input);
            Address virtualHost = null;
            if (input.readBoolean()) {
                virtualHost = readAddress(input);
            }
            var rawVirtualHost = readNullableString(input);
            requireEnd(input);
            return new PostLoginPayload(
                username,
                remoteAddress,
                virtualHost,
                rawVirtualHost
            );
        }
    }

    static DisconnectPayload decodeDisconnect(EventPayload payload) throws IOException {
        try (var input = input(payload)) {
            var username = readString(input);
            var loginStatus = DisconnectEvent.LoginStatus.valueOf(
                readString(input).toUpperCase(Locale.ROOT)
            );
            var currentBackend = readNullableString(input);
            requireEnd(input);
            return new DisconnectPayload(
                username,
                loginStatus,
                currentBackend == null ? null : Key.key(currentBackend)
            );
        }
    }

    static BackendKickPayload decodeBackendKick(EventPayload payload) throws IOException {
        try (var input = input(payload)) {
            var originalReason = deserializeComponent(readNullableString(input));
            var duringServerConnect = input.readBoolean();
            var action = ProxyAction.valueOf(
                readString(input).toUpperCase(Locale.ROOT)
            );
            var redirectTarget = readNullableString(input);
            var message = deserializeComponent(readNullableString(input));
            requireEnd(input);
            return new BackendKickPayload(
                originalReason,
                duringServerConnect,
                action,
                redirectTarget == null ? null : Key.key(redirectTarget),
                message
            );
        }
    }

    private static EventPayload encode(IoConsumer<DataOutputStream> encoder) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            encoder.accept(output);
        } catch (IOException e) {
            throw new AssertionError(
                "Unexpected in-memory Velocity payload encoding failure.",
                e
            );
        }
        return EventPayload.takeOwnership(bytes.toByteArray());
    }

    private static DataInputStream input(EventPayload payload) {
        return new DataInputStream(
            Objects.requireNonNull(payload, "payload").openStream()
        );
    }

    private static void writeAddress(
        DataOutputStream output,
        InetSocketAddress address
    ) throws IOException {
        writeString(output, address.getHostString());
        output.writeInt(address.getPort());
    }

    private static Address readAddress(DataInputStream input) throws IOException {
        return new Address(readString(input), input.readInt());
    }

    private static void writeString(DataOutputStream output, String value)
        throws IOException {
        var encoded = Objects.requireNonNull(value, "value")
            .getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static void writeNullableString(
        DataOutputStream output,
        @Nullable String value
    ) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        writeString(output, value);
    }

    private static String readString(DataInputStream input) throws IOException {
        var length = input.readInt();
        if (length < 0) {
            throw new IOException("Negative string length: " + length);
        }
        var encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new IOException("Truncated string payload.");
        }
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static @Nullable String readNullableString(DataInputStream input)
        throws IOException {
        var length = input.readInt();
        if (length == -1) {
            return null;
        }
        if (length < 0) {
            throw new IOException("Negative nullable string length: " + length);
        }
        var encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new IOException("Truncated nullable string payload.");
        }
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static @Nullable String serializeComponent(
        @Nullable Component component
    ) {
        return component == null ? null : COMPONENT_SERIALIZER.serialize(component);
    }

    private static @Nullable Component deserializeComponent(
        @Nullable String serialized
    ) {
        return serialized == null ? null : COMPONENT_SERIALIZER.deserialize(serialized);
    }

    private static String enumName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.read() != -1) {
            throw new IOException("Trailing bytes in Velocity player-session payload.");
        }
    }

    enum ProxyAction {
        DISCONNECT,
        REDIRECT,
        NOTIFY
    }

    record Address(String host, int port) {

        Address {
            Objects.requireNonNull(host, "host");
        }
    }

    record PostLoginPayload(
        String username,
        Address remoteAddress,
        @Nullable Address virtualHost,
        @Nullable String rawVirtualHost
    ) {

        PostLoginPayload {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(remoteAddress, "remoteAddress");
        }
    }

    record DisconnectPayload(
        String username,
        DisconnectEvent.LoginStatus loginStatus,
        @Nullable Key currentBackendKey
    ) {

        DisconnectPayload {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(loginStatus, "loginStatus");
        }
    }

    record BackendKickPayload(
        @Nullable Component originalReason,
        boolean duringServerConnect,
        ProxyAction action,
        @Nullable Key redirectTarget,
        @Nullable Component message
    ) {

        BackendKickPayload {
            Objects.requireNonNull(action, "action");
        }
    }

    @FunctionalInterface
    private interface IoConsumer<T> {

        void accept(T value) throws IOException;
    }
}
