package net.okocraft.kansokusha.velocity.builtin;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@ApiStatus.Internal
@NotNullByDefault
public final class VelocityServerKeyCodec {

    private static final String NAMESPACE = "kansokusha";
    private static final String PATH_PREFIX = "velocity-server/";

    private VelocityServerKeyCodec() {
    }

    /**
     * Returns {@code kansokusha:velocity-server/<lower-cased name>}, or empty when the lower-cased
     * name contains characters that are not allowed in an Adventure key value.
     */
    public static Optional<Key> encode(String serverName) {
        Objects.requireNonNull(serverName, "serverName");
        try {
            return Optional.of(Key.key(NAMESPACE, PATH_PREFIX + serverName.toLowerCase(Locale.ROOT)));
        } catch (InvalidKeyException e) {
            return Optional.empty();
        }
    }
}
