package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class EventCommand {

    static final String PERMISSION = EventCommandSupport.PERMISSION;

    static LiteralCommandNode<CommandSource> createEventCommand(EventSearchBackend backend) {
        return BrigadierCommand.literalArgumentBuilder("event")
            .requires(source -> source.hasPermission(PERMISSION))
            .then(BrigadierCommand.requiredArgumentBuilder("event-id", StringArgumentType.word())
                .executes(context -> {
                    var source = context.getSource();
                    return EventCommandSupport.execute(
                        backend,
                        StringArgumentType.getString(context, "event-id"),
                        source::hasPermission,
                        source::sendMessage
                    ) ? Command.SINGLE_SUCCESS : 0;
                }))
            .build();
    }

    private EventCommand() {
        throw new UnsupportedOperationException();
    }
}
