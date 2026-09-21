package net.okocraft.kansokusha.velocity.builtin;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class VelocityServerKeyCodec {

    private static final String NAMESPACE = "kansokusha";
    private static final String PATH_PREFIX = "velocity-server/";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private VelocityServerKeyCodec() {
    }

    public static Key encode(String serverName) {
        Objects.requireNonNull(serverName, "serverName");

        var encoded = new StringBuilder(PATH_PREFIX.length() + serverName.length() * 4);
        encoded.append(PATH_PREFIX);
        for (int index = 0; index < serverName.length(); index++) {
            var value = serverName.charAt(index);
            encoded.append(HEX[(value >>> 12) & 0x0f]);
            encoded.append(HEX[(value >>> 8) & 0x0f]);
            encoded.append(HEX[(value >>> 4) & 0x0f]);
            encoded.append(HEX[value & 0x0f]);
        }
        return Key.key(NAMESPACE, encoded.toString());
    }

    static String decode(Key key) {
        Objects.requireNonNull(key, "key");
        if (!NAMESPACE.equals(key.namespace()) || !key.value().startsWith(PATH_PREFIX)) {
            throw new IllegalArgumentException("Not a Kansokusha Velocity server key: " + key);
        }

        var encoded = key.value().substring(PATH_PREFIX.length());
        if ((encoded.length() & 3) != 0) {
            throw new IllegalArgumentException("Malformed Velocity server key: " + key);
        }

        var decoded = new StringBuilder(encoded.length() / 4);
        for (int offset = 0; offset < encoded.length(); offset += 4) {
            int value = 0;
            for (int digit = 0; digit < 4; digit++) {
                var hex = Character.digit(encoded.charAt(offset + digit), 16);
                if (hex < 0) {
                    throw new IllegalArgumentException("Malformed Velocity server key: " + key);
                }
                value = (value << 4) | hex;
            }
            decoded.append((char) value);
        }
        return decoded.toString();
    }
}
