package net.okocraft.kansokusha.api.event;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@NotNullByDefault
public record EventTypeDefinition(Key key, PayloadGeneration payloadGeneration) {

    public EventTypeDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payloadGeneration, "payloadGeneration");
    }
}
