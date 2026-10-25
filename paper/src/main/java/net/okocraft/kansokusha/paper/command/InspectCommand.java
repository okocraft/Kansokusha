package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.paper.inspection.InspectionSessionManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class InspectCommand {

    static LiteralArgumentBuilder<CommandSourceStack> createInspectCommand(
        String literal,
        InspectionSessionManager sessions
    ) {
        return Commands.literal(literal)
            .requires(source -> source.getSender().hasPermission(InspectionSessionManager.PERMISSION))
            .executes(context -> execute(context.getSource(), sessions, RequestedState.TOGGLE))
            .then(Commands.literal("on")
                .executes(context -> execute(context.getSource(), sessions, RequestedState.ON)))
            .then(Commands.literal("off")
                .executes(context -> execute(context.getSource(), sessions, RequestedState.OFF)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> createInspectCommand(String literal, InspectionSessionManager sessions,
        net.okocraft.kansokusha.common.search.EventSearchBackend backend, java.time.Clock clock, java.time.ZoneId timezone) {
        var builder = createInspectCommand(literal, sessions);
        var page = Commands.literal("page")
            .then(Commands.argument("target-and-cursor", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                .executes(context -> {
                    var sender = context.getSource().getSender();
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(InspectionCommandMessages.PLAYER_ONLY.asComponent());
                        return 0;
                    }
                    try {
                        var parts = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "target-and-cursor").split(" ");
                        if (parts.length != 5) throw new IllegalArgumentException("Expected one exact block coordinate and cursor");
                        var target = new net.okocraft.kansokusha.common.search.query.SearchQuery.Position(
                            net.kyori.adventure.key.Key.key(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                        var cursor = parts[4].equals("first") ? java.util.Optional.<net.okocraft.kansokusha.common.search.SearchRequest.Cursor>empty()
                            : java.util.Optional.of(net.okocraft.kansokusha.common.command.SearchCommandSupport.parseCursor(parts[4]));
                        new net.okocraft.kansokusha.paper.inspection.InspectionSearchHandler(player.getServer(), backend, clock, timezone)
                            .page(player.getUniqueId(), target, cursor);
                        return Command.SINGLE_SUCCESS;
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(net.okocraft.kansokusha.common.command.SearchCommandMessages.PARSE_ERROR.apply(
                            net.okocraft.kansokusha.common.search.query.SearchQueryMessages.INVALID_CURSOR.asComponent()));
                        return 0;
                    }
                })).build();
        var hidden = new com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack>("page", page.getCommand(),
            page.getRequirement(), null, null, false) {
            @Override
            public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> listSuggestions(
                com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                com.mojang.brigadier.suggestion.SuggestionsBuilder suggestions) {
                return com.mojang.brigadier.suggestion.Suggestions.empty();
            }
        };
        page.getChildren().forEach(hidden::addChild);
        return builder.then(hidden);
    }

    private static int execute(
        CommandSourceStack source,
        InspectionSessionManager sessions,
        RequestedState requestedState
    ) {
        var sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(InspectionCommandMessages.PLAYER_ONLY.asComponent());
            return 0;
        }

        boolean enabled = switch (requestedState) {
            case TOGGLE -> sessions.toggle(player.getUniqueId());
            case ON -> {
                sessions.enable(player.getUniqueId());
                yield true;
            }
            case OFF -> {
                sessions.disable(player.getUniqueId());
                yield false;
            }
        };

        player.sendMessage(
            enabled
                ? InspectionCommandMessages.ENABLED.asComponent()
                : InspectionCommandMessages.DISABLED.asComponent()
        );
        return Command.SINGLE_SUCCESS;
    }

    private enum RequestedState {
        TOGGLE,
        ON,
        OFF
    }

    private InspectCommand() {
        throw new UnsupportedOperationException();
    }
}
