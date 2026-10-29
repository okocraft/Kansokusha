package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.common.command.HealthCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class HealthCommand {

    static LiteralCommandNode<CommandSourceStack> createHealthCommand(EventSearchBackend backend) {
        return Commands.literal("health")
            .requires(source -> source.getSender().hasPermission(HealthCommandSupport.PERMISSION))
            .executes(context -> {
                HealthCommandSupport.execute(backend, context.getSource().getSender()::sendMessage);
                return Command.SINGLE_SUCCESS;
            })
            .build();
    }

    private HealthCommand() {
        throw new UnsupportedOperationException();
    }
}
