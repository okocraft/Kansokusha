package net.okocraft.kansokusha.api.subject;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.UUID;

@NotNullByDefault
public record PlayerSubject(UUID uniqueId) implements EventSubject {

    public PlayerSubject {
        Objects.requireNonNull(uniqueId, "uniqueId");
    }
}
