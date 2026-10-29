package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.okocraft.kansokusha.common.command.HealthCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class HealthCommand {

    static LiteralCommandNode<CommandSource> createHealthCommand(EventSearchBackend backend) {
        return BrigadierCommand.literalArgumentBuilder("health")
            .requires(source -> source.hasPermission(HealthCommandSupport.PERMISSION))
            .executes(context -> {
                HealthCommandSupport.execute(backend, context.getSource()::sendMessage);
                return Command.SINGLE_SUCCESS;
            })
            .build();
    }

    private HealthCommand() {
        throw new UnsupportedOperationException();
    }
}
