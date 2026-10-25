package net.okocraft.kansokusha.common.search;

import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/** Opaque persisted data; only the owning platform knows how to interpret it. */
@ApiStatus.Internal
@NotNullByDefault
public record StoredPayload(PayloadGeneration generation, EventPayload bytes) {

    public StoredPayload {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(bytes, "bytes");
    }
}
