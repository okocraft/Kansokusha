package net.okocraft.kansokusha.paper.inspection;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.UUID;

/**
 * Receives an immutable inspection target selected by a player.
 */
@FunctionalInterface
@NotNullByDefault
public interface InspectionTargetHandler {

    void inspect(UUID playerId, InspectionTarget target);
}
