package net.okocraft.kansokusha.paper.api;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@NotNullByDefault
public final class PaperKansokusha {

    private PaperKansokusha() {
    }

    public static Key key(NamespacedKey key) {
        Objects.requireNonNull(key, "key");
        return Key.key(key.getNamespace(), key.getKey());
    }

    public static NamespacedKey namespacedKey(Key key) {
        Objects.requireNonNull(key, "key");
        return new NamespacedKey(key.namespace(), key.value());
    }

    public static EventTypeDefinition eventType(
        NamespacedKey key,
        PayloadGeneration payloadGeneration
    ) {
        return new EventTypeDefinition(key(key), payloadGeneration);
    }
}
