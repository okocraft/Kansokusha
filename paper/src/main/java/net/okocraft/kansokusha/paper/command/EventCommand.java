package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class EventCommand {

    static final String PERMISSION = EventCommandSupport.PERMISSION;

    static LiteralArgumentBuilder<CommandSourceStack> createEventCommand(EventSearchBackend backend) {
        return Commands.literal("event")
            .requires(source -> source.getSender().hasPermission(PERMISSION))
            .then(Commands.argument("event-id", StringArgumentType.word())
                .executes(context -> {
                    var sender = context.getSource().getSender();
                    return EventCommandSupport.execute(
                        backend,
                        StringArgumentType.getString(context, "event-id"),
                        sender::hasPermission,
                        sender::sendMessage
                    ) ? Command.SINGLE_SUCCESS : 0;
                }));
    }

    private EventCommand() {
        throw new UnsupportedOperationException();
    }
}
