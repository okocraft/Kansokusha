package net.okocraft.kansokusha.paper.inspection;

import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.bukkit.Server;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@NotNullByDefault
public final class InspectionSearchHandler implements InspectionTargetHandler {

    private final Server server;
    private final EventSearchBackend backend;
    private final ConcurrentMap<UUID, Object> latestRequests = new ConcurrentHashMap<>();

    public InspectionSearchHandler(Server server, EventSearchBackend backend) {
        this.server = Objects.requireNonNull(server, "server");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public void inspect(UUID playerId, InspectionTarget target) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(target, "target");

        var player = this.server.getPlayer(playerId);
        if (player == null) {
            return;
        }

        var requestToken = new Object();
        this.latestRequests.put(playerId, requestToken);
        var eventDetailsPermitted = player.hasPermission(EventCommandSupport.PERMISSION);
        var fullSearchPermitted = player.hasPermission(SearchCommandSupport.PERMISSION);

        this.backend.searchMetadata()
            .thenCompose(metadata -> {
                var currentPlayer = this.server.getPlayer(playerId);
                var allowedEventTypes = currentPlayer == null
                    ? Set.<net.kyori.adventure.key.Key>of()
                    : SearchCommandSupport.allowedEventTypes(
                        currentPlayer::hasPermission,
                        metadata
                    );
                return this.backend.search(
                    InspectionSearchRequestFactory.create(target, allowedEventTypes)
                );
            })
            .whenComplete((page, failure) -> {
                if (!this.latestRequests.remove(playerId, requestToken)) {
                    return;
                }

                var currentPlayer = this.server.getPlayer(playerId);
                if (currentPlayer == null) {
                    return;
                }

                if (failure != null) {
                    currentPlayer.sendMessage(SearchCommandMessages.SEARCH_FAILED.asComponent());
                    return;
                }

                var position = InspectionSearchOutput.positionText(target);
                currentPlayer.sendMessage(InspectionSearchMessages.HISTORY.apply(position));

                if (page.events().isEmpty()) {
                    currentPlayer.sendMessage(InspectionSearchMessages.NO_HISTORY.apply(position));
                    return;
                }

                for (var event : page.events()) {
                    currentPlayer.sendMessage(
                        SearchCommandMessages.RESULT.apply(
                            SearchCommandSupport.formatEvent(event, eventDetailsPermitted)
                        )
                    );
                }

                if (fullSearchPermitted && page.nextCursor().isPresent()) {
                    currentPlayer.sendMessage(InspectionSearchOutput.fullHistory(target));
                }
            });
    }
}
