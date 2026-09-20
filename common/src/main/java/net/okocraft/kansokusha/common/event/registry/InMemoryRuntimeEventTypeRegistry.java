package net.okocraft.kansokusha.common.event.registry;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@NotNullByDefault
public final class InMemoryRuntimeEventTypeRegistry implements RuntimeEventTypeRegistry {

    private final ConcurrentMap<Key, EventTypeDefinition> definitions = new ConcurrentHashMap<>();

    @Override
    public RegistrationStatus register(EventTypeDefinition definition) {
        Objects.requireNonNull(definition, "definition");

        EventTypeDefinition existing = this.definitions.putIfAbsent(definition.key(), definition);
        if (existing == null) {
            return RegistrationStatus.REGISTERED;
        }
        if (existing.equals(definition)) {
            return RegistrationStatus.ALREADY_REGISTERED;
        }
        return RegistrationStatus.CONFLICT;
    }

    @Override
    public Optional<EventTypeDefinition> find(Key key) {
        return Optional.ofNullable(this.definitions.get(Objects.requireNonNull(key, "key")));
    }

    @Override
    public boolean unregister(EventTypeDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return this.definitions.remove(definition.key(), definition);
    }
}
