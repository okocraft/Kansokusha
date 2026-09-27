package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.player.PlayerNameDirectory;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@NotNullByDefault
final class SearchCommand {

    static final String PERMISSION = SearchCommandSupport.PERMISSION;
    static final String EVENT_PERMISSION_PREFIX = SearchCommandSupport.EVENT_PERMISSION_PREFIX;

    static LiteralArgumentBuilder<CommandSourceStack> createSearchCommand() {
        return createSearchCommand(Clock.systemDefaultZone(), ZoneId.systemDefault());
    }

    static LiteralArgumentBuilder<CommandSourceStack> createSearchCommand(
        Clock clock,
        ZoneId timezone
    ) {
        return Commands.literal("search")
            .requires(source -> source.getSender().hasPermission(PERMISSION))
            .executes(context -> execute(context.getSource(), "", clock, timezone))
            .then(Commands.argument("query", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggest(context, builder, clock, timezone))
                .executes(context -> execute(
                    context.getSource(),
                    StringArgumentType.getString(context, "query"),
                    clock,
                    timezone
                )));
    }

    private static int execute(
        CommandSourceStack source,
        String rawInput,
        Clock clock,
        ZoneId timezone
    ) {
        var sender = source.getSender();
        final SearchCommandSupport.Invocation invocation;
        try {
            invocation = SearchCommandSupport.parseInvocation(rawInput, clock, timezone);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(SearchCommandMessages.PARSE_ERROR.asComponent());
            return 0;
        }

        var query = invocation.query();
        var player = source.getExecutor() instanceof Player executor ? executor : null;
        var playerSource = player != null;
        var maxLimit = SearchCommandSupport.maxLimit(playerSource);
        var defaultLimit = SearchCommandSupport.defaultLimit(playerSource);

        if (query.limit().isPresent() && query.limit().getAsInt() > maxLimit) {
            sender.sendMessage(SearchCommandMessages.LIMIT_RANGE.apply(Integer.toString(maxLimit)));
            return 0;
        }

        for (var eventType : SearchCommandSupport.explicitActions(query)) {
            if (!sender.hasPermission(SearchCommandSupport.eventPermission(eventType))) {
                sender.sendMessage(
                    SearchCommandMessages.EVENT_PERMISSION.apply(
                        SearchCommandSupport.displayEventType(eventType)
                    )
                );
                return 0;
            }
        }

        Optional<SearchRequest.RadiusCenter> radiusCenter = Optional.empty();
        if (SearchCommandSupport.hasRadius(query)) {
            if (player == null) {
                sender.sendMessage(SearchCommandMessages.RADIUS_PLAYER_ONLY.asComponent());
                return 0;
            }
            var location = player.getLocation();
            radiusCenter = Optional.of(new SearchRequest.RadiusCenter(
                PaperKansokusha.key(location.getWorld().getKey()),
                location.getBlockX(),
                location.getBlockZ()
            ));
        }

        final EventSearchBackend backend;
        try {
            backend = EventSearchBackend.require(Kansokusha.api());
        } catch (IllegalStateException | IllegalArgumentException e) {
            sender.sendMessage(SearchCommandMessages.SEARCH_FAILED.asComponent());
            return 0;
        }

        var center = radiusCenter;
        backend.searchMetadata()
            .thenCompose(metadata -> backend.search(new SearchRequest(
                query,
                new SearchRequest.Constraints(
                    SearchCommandSupport.allowedEventTypes(sender::hasPermission, metadata)
                ),
                center,
                invocation.cursor(),
                defaultLimit
            )))
            .whenComplete((page, failure) -> {
                if (failure != null) {
                    sender.sendMessage(SearchCommandMessages.SEARCH_FAILED.asComponent());
                    return;
                }
                renderPage(sender::sendMessage, invocation.queryText(), page);
            });

        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggest(
        CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder,
        Clock clock,
        ZoneId timezone
    ) {
        var completion = SearchQueryParser.completion(builder.getRemaining(), clock, timezone);
        if (completion.kind() == SearchQueryParser.CompletionKind.NONE) {
            return builder.buildFuture();
        }

        var target = builder.createOffset(builder.getStart() + completion.replacementStart());
        var source = context.getSource();
        var playerSource = source.getExecutor() instanceof Player;

        if (!completion.staticSuggestions().isEmpty()) {
            completion.staticSuggestions().stream()
                .filter(value -> playerSource || !"radius".equals(value))
                .filter(value -> SearchCommandSupport.startsWithIgnoreCase(value, completion.prefix()))
                .forEach(target::suggest);
            return target.buildFuture();
        }

        final var api = Kansokusha.api();
        if (
            completion.kind() == SearchQueryParser.CompletionKind.USER
                && api instanceof PlayerNameDirectory playerNames
        ) {
            return playerNames.offlinePlayerNames().handle((names, failure) -> {
                if (failure == null) {
                    names.stream()
                        .filter(value ->
                            SearchCommandSupport.startsWithIgnoreCase(value, completion.prefix())
                        )
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .forEach(target::suggest);
                }
                return target.build();
            });
        }

        if (!(api instanceof EventSearchBackend backend)) {
            return target.buildFuture();
        }

        return backend.searchMetadata().handle((metadata, failure) -> {
            if (failure == null) {
                var visibleMetadata = SearchCommandSupport.visibleMetadata(
                    source.getSender()::hasPermission,
                    metadata
                );
                SearchCommandSupport.dynamicSuggestions(
                    completion.kind(),
                    completion.prefix(),
                    visibleMetadata
                ).forEach(target::suggest);
            }
            return target.build();
        });
    }

    private static void renderPage(
        java.util.function.Consumer<Component> sendMessage,
        String query,
        SearchPage page
    ) {
        if (page.events().isEmpty()) {
            sendMessage.accept(SearchCommandMessages.NO_RESULTS.asComponent());
        } else {
            for (var event : page.events()) {
                sendMessage.accept(SearchCommandMessages.RESULT.apply(formatEvent(event)));
            }
        }

        var pagination = paginationComponent(query, page);
        if (pagination != null) {
            sendMessage.accept(pagination);
        }
    }

    static Component formatEvent(SearchPage.Event event) {
        return SearchCommandSupport.formatEvent(event);
    }

    static @Nullable Component paginationComponent(String query, SearchPage page) {
        return SearchCommandSupport.paginationComponent(query, page);
    }

    private SearchCommand() {
        throw new UnsupportedOperationException();
    }
}
