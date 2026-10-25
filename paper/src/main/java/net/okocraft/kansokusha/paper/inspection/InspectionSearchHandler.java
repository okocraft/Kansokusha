package net.okocraft.kansokusha.paper.inspection;

import net.kyori.adventure.text.event.ClickEvent;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.command.EventRenderer;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.paper.builtin.PaperEventPresenter;
import org.bukkit.Server;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.ZoneId;
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

    private final EventRenderer renderer;

    public InspectionSearchHandler(Server server, EventSearchBackend backend) {
        this(server, backend, Clock.systemUTC(), java.time.ZoneOffset.UTC);
    }

    public InspectionSearchHandler(Server server, EventSearchBackend backend, Clock clock, ZoneId timezone) {
        this.renderer = PaperEventPresenter.renderer(clock, timezone);
        this.server = Objects.requireNonNull(server, "server");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public void inspect(UUID playerId, SearchQuery.Position target) {
        this.page(playerId, target, Optional.empty());
    }

    public void page(UUID playerId, SearchQuery.Position target, Optional<SearchRequest.Cursor> cursor) {
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
                cursor,
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
                if (page.events().stream().noneMatch(event -> currentPlayer.hasPermission(SearchCommandSupport.eventPermission(event.eventType())))) {
                    currentPlayer.sendMessage(InspectionSearchMessages.NO_HISTORY.apply(position));
                    return;
                }

                currentPlayer.sendMessage(InspectionSearchMessages.HISTORY.apply(position));
                for (var event : page.events()) {
                    if (!currentPlayer.hasPermission(SearchCommandSupport.eventPermission(event.eventType()))) {
                        continue;
                    }
                    currentPlayer.sendMessage(SearchCommandMessages.RESULT.apply(
                        this.renderer.withDebug(currentPlayer.hasPermission(EventCommandSupport.DEBUG_PERMISSION)).row(event, eventDetailsPermitted && currentPlayer.hasPermission(EventCommandSupport.PERMISSION), false)
                    ));
                }
                net.kyori.adventure.text.Component navigation = net.kyori.adventure.text.Component.empty();
                if (page.previousCursor().isPresent()) {
                    navigation = navigation.append(SearchCommandMessages.PREVIOUS.asComponent().clickEvent(
                        ClickEvent.runCommand(pageCommand(target, page.previousCursor().get()))));
                }
                if (page.nextCursor().isPresent()) {
                    navigation = navigation.append(net.kyori.adventure.text.Component.space()).append(
                        SearchCommandMessages.NEXT.asComponent().clickEvent(
                            ClickEvent.runCommand(pageCommand(target, page.nextCursor().get()))));
                }
                if (!navigation.equals(net.kyori.adventure.text.Component.empty())) currentPlayer.sendMessage(navigation);
                if (fullSearchPermitted && currentPlayer.hasPermission(SearchCommandSupport.PERMISSION) && page.nextCursor().isPresent()) {
                    currentPlayer.sendMessage(InspectionSearchMessages.VIEW_FULL.asComponent().clickEvent(
                        ClickEvent.runCommand("/kansokusha search position " + position)
                    ));
                }
            });
    }
    public static String pageCommand(SearchQuery.Position target, SearchRequest.Cursor cursor) {
        return "/kansokusha inspect page " + target.world().asString() + " " + target.x() + " " + target.y() + " " + target.z()
            + " " + SearchCommandSupport.serializeCursor(cursor);
    }

}
