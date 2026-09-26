package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class VelocityBackendRegistryChangePayloadCodec {

    private VelocityBackendRegistryChangePayloadCodec() {
    }

    static EventPayload encode(Action action, Key serverKey, ServerInfo serverInfo) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(serverKey, "serverKey");
        Objects.requireNonNull(serverInfo, "serverInfo");

        var address = serverInfo.getAddress();
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            writeString(output, action.serializedName());
            writeString(output, serverKey.asString());
            writeString(output, serverInfo.getName());
            writeString(output, address.getHostString());
            output.writeInt(address.getPort());
            output.writeBoolean(address.isUnresolved());
            if (address.isUnresolved()) {
                output.writeInt(-1);
            } else {
                var addressBytes = address.getAddress().getAddress();
                output.writeInt(addressBytes.length);
                output.write(addressBytes);
            }
        } catch (IOException e) {
            throw new AssertionError("Unexpected in-memory Velocity payload encoding failure.", e);
        }
        return EventPayload.takeOwnership(bytes.toByteArray());
    }

    static Decoded decode(EventPayload payload) throws IOException {
        Objects.requireNonNull(payload, "payload");

        try (
            var input = new DataInputStream(
                payload.openStream()
            )
        ) {
            var action = Action.fromSerializedName(readString(input));
            var serverKey = Key.key(readString(input));
            var name = readString(input);
            var host = readString(input);
            var port = input.readInt();
            var unresolved = input.readBoolean();
            var addressLength = input.readInt();

            final InetSocketAddress address;
            if (unresolved) {
                if (addressLength != -1) {
                    throw new IOException(
                        "Unresolved backend address has resolved bytes length: " + addressLength
                    );
                }
                address = InetSocketAddress.createUnresolved(host, port);
            } else {
                if (addressLength != 4 && addressLength != 16) {
                    throw new IOException(
                        "Resolved backend address length must be 4 or 16, got " + addressLength
                    );
                }
                var addressBytes = input.readNBytes(addressLength);
                if (addressBytes.length != addressLength) {
                    throw new IOException("Truncated resolved backend address.");
                }
                address = new InetSocketAddress(InetAddress.getByAddress(host, addressBytes), port);
            }

            if (input.read() != -1) {
                throw new IOException("Trailing bytes after backend registry change payload.");
            }

            return new Decoded(action, serverKey, new ServerInfo(name, address));
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        var encoded = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input) throws IOException {
        var length = input.readInt();
        if (length < 0) {
            throw new IOException("Negative UTF-8 field length: " + length);
        }
        var encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new IOException("Truncated UTF-8 field.");
        }
        return new String(encoded, StandardCharsets.UTF_8);
    }

    enum Action {
        REGISTER,
        UNREGISTER;

        String serializedName() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        static Action fromSerializedName(String value) throws IOException {
            for (var action : values()) {
                if (action.serializedName().equals(value)) {
                    return action;
                }
            }
            throw new IOException("Unknown backend registry action: " + value);
        }
    }

    record Decoded(Action action, Key serverKey, ServerInfo serverInfo) {
    }
}
