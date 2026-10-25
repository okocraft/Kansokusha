package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.okocraft.kansokusha.common.command.EventRenderer;
import java.time.Clock;
import java.time.ZoneId;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class EventCommand {

    static final String PERMISSION = EventCommandSupport.PERMISSION;

    static LiteralCommandNode<CommandSource> createEventCommand(EventSearchBackend backend, Clock clock, ZoneId timezone) {
        return BrigadierCommand.literalArgumentBuilder("event")
            .requires(source -> source.hasPermission(PERMISSION))
            .executes(context -> {
                context.getSource().sendMessage(CommandMessages.HELP_EVENT.asComponent());
                return 0;
            })
            .then(BrigadierCommand.requiredArgumentBuilder("event-id", StringArgumentType.word())
                .executes(context -> {
                    var source = context.getSource();
                    return EventCommandSupport.execute(
                        backend,
                        StringArgumentType.getString(context, "event-id"),
                        source::hasPermission,
                        source::sendMessage,
                        EventRenderer.generic(clock, timezone)
                    ) ? Command.SINGLE_SUCCESS : 0;
                }))
            .build();
    }

    private EventCommand() {
        throw new UnsupportedOperationException();
    }
}
