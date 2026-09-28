package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;

@NotNullByDefault
final class SearchCommand {

    static final String PERMISSION = SearchCommandSupport.PERMISSION;

    static LiteralArgumentBuilder<CommandSourceStack> createSearchCommand(
        EventSearchBackend backend,
        Clock clock,
        ZoneId timezone
    ) {
        return Commands.literal("search")
            .requires(source -> source.getSender().hasPermission(PERMISSION))
            .executes(context -> execute(backend, context.getSource(), "", clock, timezone))
            .then(Commands.argument("query", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggest(backend, context.getSource(), builder, clock, timezone))
                .executes(context -> execute(
                    backend,
                    context.getSource(),
                    StringArgumentType.getString(context, "query"),
                    clock,
                    timezone
                )));
    }

    private static int execute(
        EventSearchBackend backend,
        CommandSourceStack source,
        String rawInput,
        Clock clock,
        ZoneId timezone
    ) {
        var sender = source.getSender();
        var player = source.getExecutor() instanceof Player executor ? executor : null;
        SearchQuery.Position origin = null;
        if (player != null) {
            var location = player.getLocation();
            origin = new SearchQuery.Position(
                PaperKansokusha.key(location.getWorld().getKey()),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
            );
        }

        var searchSource = new SearchCommandSupport.SearchSource(
            sender::hasPermission,
            sender::sendMessage,
            player != null,
            origin
        );
        return SearchCommandSupport.execute(backend, searchSource, rawInput, clock, timezone)
            ? Command.SINGLE_SUCCESS
            : 0;
    }

    private static CompletableFuture<Suggestions> suggest(
        EventSearchBackend backend,
        CommandSourceStack source,
        SuggestionsBuilder builder,
        Clock clock,
        ZoneId timezone
    ) {
        return SearchCommandSupport.suggest(
            backend,
            source.getSender()::hasPermission,
            source.getExecutor() instanceof Player,
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
