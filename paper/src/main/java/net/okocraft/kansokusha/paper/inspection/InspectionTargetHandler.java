package net.okocraft.kansokusha.paper.inspection;

import net.okocraft.kansokusha.common.search.query.SearchQuery;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.UUID;

/**
 * Receives the block coordinate selected by a player in inspection mode.
 */
@FunctionalInterface
@NotNullByDefault
public interface InspectionTargetHandler {

    void inspect(UUID playerId, SearchQuery.Position target);

    default void inspectContainer(UUID playerId, java.util.Set<SearchQuery.Position> positions) {
        inspect(playerId, positions.iterator().next());
    }
}
