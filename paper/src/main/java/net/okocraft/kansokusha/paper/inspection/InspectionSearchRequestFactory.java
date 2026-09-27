package net.okocraft.kansokusha.paper.inspection;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

@NotNullByDefault
final class InspectionSearchRequestFactory {

    static SearchRequest create(InspectionTarget target, Set<Key> allowedEventTypes) {
        var query = new SearchQuery(
            new SearchQuery.Conditions(
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(target.worldKey()),
                Set.of(new SearchQuery.Position(
                    target.worldKey(),
                    target.x(),
                    target.y(),
                    target.z()
                )),
                Set.of()
            ),
            SearchQuery.Conditions.empty(),
            SearchQuery.Order.NEWEST,
            OptionalInt.empty()
        );
        return new SearchRequest(
            query,
            new SearchRequest.Constraints(allowedEventTypes),
            Optional.empty(),
            Optional.empty(),
            SearchCommandSupport.PLAYER_DEFAULT_LIMIT
        );
    }

    private InspectionSearchRequestFactory() {
        throw new UnsupportedOperationException();
    }
}
