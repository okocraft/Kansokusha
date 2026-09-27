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
