package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;

@NotNullByDefault
final class SearchCommand {

    static final String PERMISSION = SearchCommandSupport.PERMISSION;

    static LiteralCommandNode<CommandSource> createSearchCommand(
        EventSearchBackend backend,
        Clock clock,
        ZoneId timezone
    ) {
        return BrigadierCommand.literalArgumentBuilder("search")
            .requires(source -> source.hasPermission(PERMISSION))
            .executes(context -> execute(backend, context.getSource(), "", clock, timezone))
            .then(BrigadierCommand.requiredArgumentBuilder("query", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggest(backend, context.getSource(), builder, clock, timezone))
                .executes(context -> execute(
                    backend,
                    context.getSource(),
                    StringArgumentType.getString(context, "query"),
                    clock,
                    timezone
                )))
            .build();
    }

    private static int execute(
        EventSearchBackend backend,
        CommandSource source,
        String rawInput,
        Clock clock,
        ZoneId timezone
    ) {
        // A proxy has no world position, so radius is never available.
        var searchSource = new SearchCommandSupport.SearchSource(
            source::hasPermission,
            source::sendMessage,
            source instanceof Player,
            null
        );
        return SearchCommandSupport.execute(backend, searchSource, rawInput, clock, timezone)
            ? Command.SINGLE_SUCCESS
            : 0;
    }

    private static CompletableFuture<Suggestions> suggest(
        EventSearchBackend backend,
        CommandSource source,
        SuggestionsBuilder builder,
        Clock clock,
        ZoneId timezone
    ) {
        return SearchCommandSupport.suggest(
            backend,
            source::hasPermission,
            false,
            builder.getRemaining(),
            clock,
            timezone
        ).thenApply(suggestions -> {
            var target = builder.createOffset(builder.getStart() + suggestions.replacementStart());
            suggestions.values().forEach(target::suggest);
            return target.build();
        });
    }

    private SearchCommand() {
        throw new UnsupportedOperationException();
    }
}
