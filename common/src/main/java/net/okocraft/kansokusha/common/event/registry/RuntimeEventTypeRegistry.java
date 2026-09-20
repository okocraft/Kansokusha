package net.okocraft.kansokusha.common.event.registry;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Optional;

@NotNullByDefault
public interface RuntimeEventTypeRegistry {

    RegistrationStatus register(EventTypeDefinition definition);

    Optional<EventTypeDefinition> find(Key key);

    boolean unregister(EventTypeDefinition definition);
}
