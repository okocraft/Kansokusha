package net.okocraft.kansokusha.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Provides the Kansokusha API published by the running platform plugin.
 */
@NotNullByDefault
public final class Kansokusha {

    private static volatile @Nullable KansokushaApi api;

    private Kansokusha() {
    }

    /**
     * Returns the API published by the running Kansokusha instance.
     *
     * @throws IllegalStateException if Kansokusha is not running
     */
    public static KansokushaApi api() {
        var current = api;
        if (current == null) {
            throw new IllegalStateException("Kansokusha API is not available");
        }
        return current;
    }

    @ApiStatus.Internal
    public static void setApi(@Nullable KansokushaApi api) {
        Kansokusha.api = api;
    }
}
