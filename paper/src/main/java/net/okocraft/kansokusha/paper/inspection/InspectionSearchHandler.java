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
        this.page(playerId, target, cursor, false, java.util.Set.of(target));
    }

    @Override
    public void inspectContainer(UUID playerId, java.util.Set<SearchQuery.Position> positions) {
        var target = positions.stream().min(java.util.Comparator.comparingInt(SearchQuery.Position::x)
            .thenComparingInt(SearchQuery.Position::z)).orElseThrow();
        this.page(playerId, target, Optional.empty(), true, positions);
    }

    public void page(UUID playerId, SearchQuery.Position target, Optional<SearchRequest.Cursor> cursor,
                     boolean container, java.util.Set<SearchQuery.Position> positions) {
        var player = this.server.getPlayer(playerId);
        if (player == null) {
            return;
        }

        var eventDetailsPermitted = player.hasPermission(EventCommandSupport.PERMISSION);
        var fullSearchPermitted = player.hasPermission(SearchCommandSupport.PERMISSION);
        var query = new SearchQuery(
            new SearchQuery.Conditions(java.util.Set.of(), container ? java.util.Set.of(
                net.kyori.adventure.key.Key.key("kansokusha", "container_transaction"),
                net.kyori.adventure.key.Key.key("kansokusha", "container_pickup"),
                net.kyori.adventure.key.Key.key("kansokusha", "container_transfer")) : java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of(), positions, java.util.Set.of()),
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

                if (currentPlayer.isOnline()) net.okocraft.kansokusha.common.command.SearchNavigation.remember(playerId,
                    pageCommand(target, cursor, container, positions));
                var position = target.world().asString() + " " + target.x() + " " + target.y() + " " + target.z();
                if (page.events().stream().noneMatch(event -> currentPlayer.hasPermission(SearchCommandSupport.eventPermission(event.eventType())))) {
                    currentPlayer.sendMessage(InspectionSearchMessages.NO_HISTORY.apply(position));
                    return;
                }

                var renderer = this.renderer.withLocations((location, showWorld) ->
                    net.okocraft.kansokusha.paper.command.PaperEventLocations.format(location, showWorld,
                        currentPlayer.hasPermission("kansokusha.command.teleport")));
                currentPlayer.sendMessage((container ? InspectionSearchMessages.CONTAINER_HISTORY_AT : InspectionSearchMessages.HISTORY_AT)
                    .apply(renderer.location(net.okocraft.kansokusha.common.command.EventLocation.of(target), true)));
                for (var event : page.events()) {
                    if (!currentPlayer.hasPermission(SearchCommandSupport.eventPermission(event.eventType()))) {
                        continue;
                    }
                    currentPlayer.sendMessage(SearchCommandMessages.RESULT.apply(
                        renderer.row(event, eventDetailsPermitted && currentPlayer.hasPermission(EventCommandSupport.PERMISSION), false)
                    ));
                }
                net.kyori.adventure.text.Component navigation = net.kyori.adventure.text.Component.empty();
                if (page.previousCursor().isPresent()) {
                    navigation = navigation.append(SearchCommandMessages.PREVIOUS.asComponent().clickEvent(
                        ClickEvent.runCommand(pageCommand(target, page.previousCursor().get(), container, positions))));
                }
                if (page.nextCursor().isPresent()) {
                    navigation = navigation.append(net.kyori.adventure.text.Component.space()).append(
                        SearchCommandMessages.NEXT.asComponent().clickEvent(
                            ClickEvent.runCommand(pageCommand(target, page.nextCursor().get(), container, positions))));
                }
                if (!navigation.equals(net.kyori.adventure.text.Component.empty())) currentPlayer.sendMessage(navigation);
                if (fullSearchPermitted && currentPlayer.hasPermission(SearchCommandSupport.PERMISSION) && page.nextCursor().isPresent()) {
                    currentPlayer.sendMessage(InspectionSearchMessages.VIEW_FULL.asComponent().clickEvent(
                        ClickEvent.runCommand("/kansokusha search " + positions.stream().sorted(java.util.Comparator.comparingInt(SearchQuery.Position::x)
                            .thenComparingInt(SearchQuery.Position::z))
                            .map(value -> "position " + value.world().asString() + " " + value.x() + " " + value.y() + " " + value.z())
                            .collect(java.util.stream.Collectors.joining(" "))
                            + (container ? " action container_transaction action container_pickup action container_transfer" : ""))
                    ));
                }
            });
    }
    public static String pageCommand(SearchQuery.Position target, SearchRequest.Cursor cursor) {
        return pageCommand(target, cursor, false, java.util.Set.of(target));
    }

    public static String pageCommand(SearchQuery.Position target, SearchRequest.Cursor cursor, boolean container,
                                     java.util.Set<SearchQuery.Position> positions) {
        return pageCommand(target, Optional.of(cursor), container, positions);
    }

    public static String pageCommand(SearchQuery.Position target, Optional<SearchRequest.Cursor> cursor, boolean container,
                                     java.util.Set<SearchQuery.Position> positions) {
        var extra = container ? " container" + positions.stream().filter(position -> !position.equals(target))
            .map(position -> " " + position.x() + " " + position.y() + " " + position.z()).collect(java.util.stream.Collectors.joining()) : "";
        return "/kansokusha inspect page " + target.world().asString() + " " + target.x() + " " + target.y() + " " + target.z()
            + " " + cursor.map(SearchCommandSupport::serializeCursor).orElse("first") + extra;
    }

}
