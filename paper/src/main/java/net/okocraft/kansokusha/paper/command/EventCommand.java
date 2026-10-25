package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.paper.builtin.PaperEventPresenter;
import org.jetbrains.annotations.NotNullByDefault;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.time.ZoneId;

@NotNullByDefault
final class EventCommand {

    static final String PERMISSION = EventCommandSupport.PERMISSION;

    static LiteralArgumentBuilder<CommandSourceStack> createEventCommand(EventSearchBackend backend, Clock clock, ZoneId timezone, Plugin plugin) {
        return Commands.literal("event")
            .requires(source -> source.getSender().hasPermission(PERMISSION))
            .executes(context -> {
                context.getSource().getSender().sendMessage(CommandMessages.HELP_EVENT.asComponent());
                return 0;
            })
            .then(Commands.argument("event-id", StringArgumentType.word())
                .executes(context -> {
                    var sender = context.getSource().getSender();
                    return EventCommandSupport.execute(
                        backend,
                        StringArgumentType.getString(context, "event-id"),
                        sender::hasPermission,
                        sender::sendMessage,
                        PaperEventPresenter.renderer(clock, timezone)
                    ) ? Command.SINGLE_SUCCESS : 0;
                })
                .then(Commands.literal("read")
                    .then(Commands.argument("item", StringArgumentType.word())
                        .executes(context -> ReadBookCommand.execute(backend, plugin, context.getSource().getSender(),
                            StringArgumentType.getString(context, "event-id"), StringArgumentType.getString(context, "item"))
                            ? Command.SINGLE_SUCCESS : 0)))
                .then(Commands.argument("view", StringArgumentType.word())
                    .executes(context -> {
                        var source = context.getSource().getSender();
                        return EventCommandSupport.execute(backend, StringArgumentType.getString(context, "event-id"),
                            source::hasPermission, source::sendMessage, PaperEventPresenter.renderer(clock, timezone),
                            StringArgumentType.getString(context, "view"), 1) ? Command.SINGLE_SUCCESS : 0;
                    })
                    .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> {
                            var source = context.getSource().getSender();
                            return EventCommandSupport.execute(backend, StringArgumentType.getString(context, "event-id"),
                                source::hasPermission, source::sendMessage, PaperEventPresenter.renderer(clock, timezone),
                                StringArgumentType.getString(context, "view"), IntegerArgumentType.getInteger(context, "page"))
                                ? Command.SINGLE_SUCCESS : 0;
                        }))));
    }

    private EventCommand() {
        throw new UnsupportedOperationException();
    }
}
