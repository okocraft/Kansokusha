package net.okocraft.kansokusha.api;

import net.okocraft.kansokusha.api.spi.KansokushaApiProvider;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ServiceLoader;

/**
 * Provides the Kansokusha API published by the running platform plugin.
 */
@NotNullByDefault
public final class Kansokusha {

    private Kansokusha() {
    }

    /**
     * Returns the API published by the running Kansokusha instance.
     *
     * @throws IllegalStateException if the API has not been published or has already shut down
     */
    public static KansokushaApi api() {
        KansokushaApiProvider provider = ServiceLoader.load(KansokushaApiProvider.class).findFirst()
            .orElseThrow(() -> new IllegalStateException("Kansokusha API provider is not available"));
        return provider.api();
    }
}
