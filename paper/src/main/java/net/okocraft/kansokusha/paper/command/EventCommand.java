package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class EventCommand {

    static final String PERMISSION = EventCommandSupport.PERMISSION;

    static LiteralArgumentBuilder<CommandSourceStack> createEventCommand() {
        return Commands.literal("event")
            .requires(source -> source.getSender().hasPermission(PERMISSION))
            .then(Commands.argument("event-id", StringArgumentType.word())
                .executes(context -> execute(
                    context.getSource(),
                    StringArgumentType.getString(context, "event-id")
                )));
    }

    private static int execute(CommandSourceStack source, String eventId) {
        var sender = source.getSender();
        try {
            return EventCommandSupport.execute(
                Kansokusha.api(),
                eventId,
                sender::hasPermission,
                sender::sendMessage
            ) ? Command.SINGLE_SUCCESS : 0;
        } catch (IllegalStateException e) {
            sender.sendMessage(EventCommandMessages.LOOKUP_FAILED.asComponent());
            return 0;
        }
    }

    private EventCommand() {
        throw new UnsupportedOperationException();
    }
}
