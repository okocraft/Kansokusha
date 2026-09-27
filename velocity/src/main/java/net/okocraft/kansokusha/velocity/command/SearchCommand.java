package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.player.PlayerNameDirectory;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@NotNullByDefault
final class SearchCommand {

    static final String PERMISSION = SearchCommandSupport.PERMISSION;
    static final String EVENT_PERMISSION_PREFIX = SearchCommandSupport.EVENT_PERMISSION_PREFIX;

    static LiteralCommandNode<CommandSource> createSearchCommand() {
        return createSearchCommand(Clock.systemDefaultZone(), ZoneId.systemDefault());
    }

    static LiteralCommandNode<CommandSource> createSearchCommand(
        Clock clock,
        ZoneId timezone
    ) {
        return BrigadierCommand.literalArgumentBuilder("search")
            .requires(source -> source.hasPermission(PERMISSION))
            .executes(context -> execute(context.getSource(), "", clock, timezone))
            .then(BrigadierCommand.requiredArgumentBuilder("query", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggest(context, builder, clock, timezone))
                .executes(context -> execute(
                    context.getSource(),
                    StringArgumentType.getString(context, "query"),
                    clock,
                    timezone
                )))
            .build();
    }

    private static int execute(
        CommandSource source,
        String rawInput,
        Clock clock,
        ZoneId timezone
    ) {
        final SearchCommandSupport.Invocation invocation;
        try {
            invocation = SearchCommandSupport.parseInvocation(rawInput, clock, timezone);
        } catch (IllegalArgumentException e) {
            source.sendMessage(SearchCommandMessages.PARSE_ERROR.asComponent());
            return 0;
        }

        var query = invocation.query();
        var playerSource = source instanceof Player;
        var maxLimit = SearchCommandSupport.maxLimit(playerSource);
        var defaultLimit = SearchCommandSupport.defaultLimit(playerSource);

        if (query.limit().isPresent() && query.limit().getAsInt() > maxLimit) {
            source.sendMessage(SearchCommandMessages.LIMIT_RANGE.apply(Integer.toString(maxLimit)));
            return 0;
        }

        for (var eventType : SearchCommandSupport.explicitActions(query)) {
            if (!source.hasPermission(SearchCommandSupport.eventPermission(eventType))) {
                source.sendMessage(
                    SearchCommandMessages.EVENT_PERMISSION.apply(
                        SearchCommandSupport.displayEventType(eventType)
                    )
                );
                return 0;
            }
        }

        if (SearchCommandSupport.hasRadius(query)) {
            source.sendMessage(SearchCommandMessages.RADIUS_UNAVAILABLE.asComponent());
            return 0;
        }

        final EventSearchBackend backend;
        try {
            backend = EventSearchBackend.require(Kansokusha.api());
        } catch (IllegalStateException | IllegalArgumentException e) {
            source.sendMessage(SearchCommandMessages.SEARCH_FAILED.asComponent());
            return 0;
        }

        backend.searchMetadata()
            .thenCompose(metadata -> backend.search(new SearchRequest(
                query,
                new SearchRequest.Constraints(
                    SearchCommandSupport.allowedEventTypes(source::hasPermission, metadata)
                ),
                Optional.empty(),
                invocation.cursor(),
                defaultLimit
            )))
            .whenComplete((page, failure) -> {
                if (failure != null) {
                    source.sendMessage(SearchCommandMessages.SEARCH_FAILED.asComponent());
                    return;
                }
                renderPage(
                    source,
                    invocation.queryText(),
                    page,
                    source.hasPermission(EventCommandSupport.PERMISSION)
                );
            });

        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggest(
        CommandContext<CommandSource> context,
        SuggestionsBuilder builder,
        Clock clock,
        ZoneId timezone
    ) {
        var completion = SearchQueryParser.completion(builder.getRemaining(), clock, timezone);
        if (completion.kind() == SearchQueryParser.CompletionKind.NONE) {
            return builder.buildFuture();
        }

        var target = builder.createOffset(builder.getStart() + completion.replacementStart());

        if (!completion.staticSuggestions().isEmpty()) {
            completion.staticSuggestions().stream()
                .filter(value -> !"radius".equals(value))
                .filter(value ->
                    SearchCommandSupport.startsWithIgnoreCase(value, completion.prefix())
                )
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

        var source = context.getSource();
        return backend.searchMetadata().handle((metadata, failure) -> {
            if (failure == null) {
                var visibleMetadata = SearchCommandSupport.visibleMetadata(
                    source::hasPermission,
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
        CommandSource source,
        String query,
        SearchPage page,
        boolean eventDetailsPermitted
    ) {
        if (page.events().isEmpty()) {
            source.sendMessage(SearchCommandMessages.NO_RESULTS.asComponent());
        } else {
            for (var event : page.events()) {
                source.sendMessage(
                    SearchCommandMessages.RESULT.apply(
                        SearchCommandSupport.formatEvent(event, eventDetailsPermitted)
                    )
                );
            }
        }

        var pagination = SearchCommandSupport.paginationComponent(query, page);
        if (pagination != null) {
            source.sendMessage(pagination);
        }
    }

    private SearchCommand() {
        throw new UnsupportedOperationException();
    }
}
