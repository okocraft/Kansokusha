package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.okocraft.kansokusha.common.command.HealthCommandSupport;
import net.okocraft.kansokusha.common.command.RetentionCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * {@code /kansokusha admin}: operations on the database itself rather than on the recorded events.
 */
@NotNullByDefault
final class AdminCommand {

    static LiteralCommandNode<CommandSource> createAdminCommand(EventSearchBackend backend) {
        return BrigadierCommand.literalArgumentBuilder("admin")
            .requires(source -> source.hasPermission(HealthCommandSupport.PERMISSION)
                || source.hasPermission(RetentionCommandSupport.PERMISSION))
            .then(HealthCommand.createHealthCommand(backend))
            .then(BrigadierCommand.literalArgumentBuilder("retention")
                .requires(source -> source.hasPermission(RetentionCommandSupport.PERMISSION))
                .then(BrigadierCommand.literalArgumentBuilder("apply")
                    .executes(context -> {
                        RetentionCommandSupport.preview(backend, context.getSource()::sendMessage);
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(BrigadierCommand.literalArgumentBuilder("confirm")
                        .executes(context -> {
                            RetentionCommandSupport.apply(backend, context.getSource()::sendMessage);
                            return Command.SINGLE_SUCCESS;
                        }))))
            .build();
    }

    private AdminCommand() {
        throw new UnsupportedOperationException();
    }
}
