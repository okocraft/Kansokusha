package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.common.command.HealthCommandSupport;
import net.okocraft.kansokusha.common.command.RetentionCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * {@code /kansokusha admin}: operations on the database itself rather than on the recorded events.
 */
@NotNullByDefault
final class AdminCommand {

    static LiteralCommandNode<CommandSourceStack> createAdminCommand(EventSearchBackend backend) {
        return Commands.literal("admin")
            .requires(source -> source.getSender().hasPermission(HealthCommandSupport.PERMISSION)
                || source.getSender().hasPermission(RetentionCommandSupport.PERMISSION))
            .then(HealthCommand.createHealthCommand(backend))
            .then(Commands.literal("retention")
                .requires(source -> source.getSender().hasPermission(RetentionCommandSupport.PERMISSION))
                .then(Commands.literal("apply")
                    .executes(context -> {
                        RetentionCommandSupport.preview(backend, context.getSource().getSender()::sendMessage);
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.literal("confirm")
                        .executes(context -> {
                            RetentionCommandSupport.apply(backend, context.getSource().getSender()::sendMessage);
                            return Command.SINGLE_SUCCESS;
                        }))))
            .build();
    }

    private AdminCommand() {
        throw new UnsupportedOperationException();
    }
}
