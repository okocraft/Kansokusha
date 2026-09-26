package net.okocraft.kansokusha.api.actor;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.UUID;

/**
 * A non-player entity actor, such as a creeper, a primed TNT or a hopper minecart.
 *
 * @param uniqueId   the entity's unique id
 * @param entityType the entity type key, such as {@code minecraft:creeper}
 */
@NotNullByDefault
public record EntityActor(UUID uniqueId, Key entityType) implements EventActor {

    public EntityActor {
        Objects.requireNonNull(uniqueId, "uniqueId");
        Objects.requireNonNull(entityType, "entityType");
    }
}
