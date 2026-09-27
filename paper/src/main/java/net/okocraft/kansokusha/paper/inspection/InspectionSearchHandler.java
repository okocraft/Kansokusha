package net.okocraft.kansokusha.paper.inspection;

import net.kyori.adventure.text.event.ClickEvent;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import org.bukkit.Server;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Shows the newest events at an inspected block coordinate.
 *
 * <p>Lookups run on the single storage thread in click order, so results are shown in the same
 * order as the clicks.</p>
 */
@NotNullByDefault
public final class InspectionSearchHandler implements InspectionTargetHandler {

    private final Server server;
    private final EventSearchBackend backend;

    public InspectionSearchHandler(Server server, EventSearchBackend backend) {
        this.server = Objects.requireNonNull(server, "server");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public void inspect(UUID playerId, SearchQuery.Position target) {
        var player = this.server.getPlayer(playerId);
        if (player == null) {
            return;
        }

        var eventDetailsPermitted = player.hasPermission(EventCommandSupport.PERMISSION);
        var fullSearchPermitted = player.hasPermission(SearchCommandSupport.PERMISSION);
        var query = new SearchQuery(
            SearchQuery.Conditions.position(target),
            SearchQuery.Conditions.empty(),
            SearchQuery.Order.NEWEST,
            OptionalInt.empty()
        );

        this.backend.searchMetadata()
            .thenCompose(metadata -> this.backend.search(new SearchRequest(
                query,
                new SearchRequest.Constraints(SearchCommandSupport.allowedEventTypes(player::hasPermission, metadata)),
                Optional.empty(),
                SearchCommandSupport.PLAYER_DEFAULT_LIMIT
            )))
            .whenComplete((page, failure) -> {
                var currentPlayer = this.server.getPlayer(playerId);
                if (currentPlayer == null) {
                    return;
                }
                if (failure != null) {
                    currentPlayer.sendMessage(SearchCommandMessages.SEARCH_FAILED.asComponent());
                    return;
                }

                var position = target.world().asString() + " " + target.x() + " " + target.y() + " " + target.z();
                currentPlayer.sendMessage(InspectionSearchMessages.HISTORY.apply(position));
                if (page.events().isEmpty()) {
                    currentPlayer.sendMessage(InspectionSearchMessages.NO_HISTORY.apply(position));
                    return;
                }

                for (var event : page.events()) {
                    currentPlayer.sendMessage(SearchCommandMessages.RESULT.apply(
                        SearchCommandSupport.formatEvent(event, eventDetailsPermitted)
                    ));
                }
                if (fullSearchPermitted && page.nextCursor().isPresent()) {
                    currentPlayer.sendMessage(InspectionSearchMessages.VIEW_FULL.asComponent().clickEvent(
                        ClickEvent.runCommand("/kansokusha search position " + position)
                    ));
                }
            });
    }
}
