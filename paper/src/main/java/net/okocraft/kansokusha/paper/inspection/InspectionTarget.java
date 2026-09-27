package net.okocraft.kansokusha.paper.inspection;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/**
 * Immutable block coordinate selected by inspection mode.
 */
@NotNullByDefault
public record InspectionTarget(Key worldKey, int x, int y, int z) {

    public InspectionTarget {
        Objects.requireNonNull(worldKey, "worldKey");
    }

    public static InspectionTarget from(Block block) {
        Objects.requireNonNull(block, "block");
        return new InspectionTarget(
            PaperKansokusha.key(block.getWorld().getKey()),
            block.getX(),
            block.getY(),
            block.getZ()
        );
    }
}
