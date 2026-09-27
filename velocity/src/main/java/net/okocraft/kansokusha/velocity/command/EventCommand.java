package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class EventCommand {

    static final String PERMISSION = EventCommandSupport.PERMISSION;

    static LiteralCommandNode<CommandSource> createEventCommand() {
        return BrigadierCommand.literalArgumentBuilder("event")
            .requires(source -> source.hasPermission(PERMISSION))
            .then(BrigadierCommand.requiredArgumentBuilder("event-id", StringArgumentType.word())
                .executes(context -> execute(
                    context.getSource(),
                    StringArgumentType.getString(context, "event-id")
                )))
            .build();
    }

    private static int execute(CommandSource source, String eventId) {
        try {
            return EventCommandSupport.execute(
                Kansokusha.api(),
                eventId,
                source::hasPermission,
                source::sendMessage
            ) ? Command.SINGLE_SUCCESS : 0;
        } catch (IllegalStateException e) {
            source.sendMessage(EventCommandMessages.LOOKUP_FAILED.asComponent());
            return 0;
        }
    }

    private EventCommand() {
        throw new UnsupportedOperationException();
    }
}
