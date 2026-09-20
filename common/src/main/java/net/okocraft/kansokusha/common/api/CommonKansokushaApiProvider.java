package net.okocraft.kansokusha.common.api;

import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.spi.KansokushaApiProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Common-module implementation of API discovery and lifecycle publication.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class CommonKansokushaApiProvider implements KansokushaApiProvider {

    private static final AtomicReference<KansokushaApi> API = new AtomicReference<>();

    @Override
    public KansokushaApi api() {
        KansokushaApi api = API.get();
        if (api == null) {
            throw new IllegalStateException("Kansokusha API is not available");
        }
        return api;
    }

    public static boolean publish(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        return API.compareAndSet(null, api);
    }

    public static boolean unpublish(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        return API.compareAndSet(api, null);
    }
}
