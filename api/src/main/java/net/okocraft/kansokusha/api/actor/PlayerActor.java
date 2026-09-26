package net.okocraft.kansokusha.api.actor;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.UUID;

@NotNullByDefault
public record PlayerActor(UUID uniqueId) implements EventActor {

    public PlayerActor {
        Objects.requireNonNull(uniqueId, "uniqueId");
    }
}
