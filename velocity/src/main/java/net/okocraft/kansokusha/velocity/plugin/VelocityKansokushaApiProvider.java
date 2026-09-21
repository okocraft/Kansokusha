package net.okocraft.kansokusha.velocity.plugin;

import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.spi.KansokushaApiProvider;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@ApiStatus.Internal
public final class VelocityKansokushaApiProvider implements KansokushaApiProvider {

    private static final AtomicReference<KansokushaApi> PUBLISHED = new AtomicReference<>();

    static void publish(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        if (!PUBLISHED.compareAndSet(null, api)) {
            throw new IllegalStateException("Kansokusha API is already published.");
        }
    }

    static void unpublish(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        PUBLISHED.compareAndSet(api, null);
    }

    @Override
    public KansokushaApi api() {
        var api = PUBLISHED.get();
        if (api == null) {
            throw new IllegalStateException("Kansokusha API is not currently published.");
        }
        return api;
    }
}
