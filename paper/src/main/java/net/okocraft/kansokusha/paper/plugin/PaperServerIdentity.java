package net.okocraft.kansokusha.paper.plugin;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

final class PaperServerIdentity {

    private static final String FALLBACK_NAMESPACE = "kansokusha";

    private PaperServerIdentity() {
        throw new UnsupportedOperationException();
    }

    static Key resolve(Optional<Key> configured, Path serverDirectory) {
        Objects.requireNonNull(configured, "configured");
        Objects.requireNonNull(serverDirectory, "serverDirectory");

        if (configured.isPresent()) {
            return configured.get();
        }

        var normalized = serverDirectory.toAbsolutePath().normalize();
        var filename = normalized.getFileName();
        if (filename == null) {
            throw new IllegalStateException(
                "Cannot derive the Paper/Folia server identity from the server directory; configure server-key."
            );
        }

        var directoryName = filename.toString();
        try {
            return Key.key(FALLBACK_NAMESPACE, directoryName);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException(
                "Cannot derive the Paper/Folia server identity from directory name '"
                    + directoryName
                    + "'; configure server-key.",
                e
            );
        }
    }
}
