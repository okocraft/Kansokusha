package net.okocraft.kansokusha.api.actor;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/**
 * A block actor, such as a piston, a dispenser or a command block.
 *
 * <p>Blocks have no stable identity, so only the block type is recorded. The event payload
 * holds the block position when the provider needs it.</p>
 *
 * @param blockType the block type key, such as {@code minecraft:piston}
 */
@NotNullByDefault
public record BlockActor(Key blockType) implements EventActor {

    public BlockActor {
        Objects.requireNonNull(blockType, "blockType");
    }
}
