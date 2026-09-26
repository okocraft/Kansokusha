package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public final class KansokushaCommands {

    public static void register(Commands commands) {
        commands.register(createCommand());
    }

    static LiteralCommandNode<CommandSourceStack> createCommand() {
        return Commands.literal("kansokusha")
            .requires(source -> source.getSender().hasPermission("kansokusha.command"))
            .then(VersionCommand.createVersionCommand())
            .build();
    }

    private KansokushaCommands() {
        throw new UnsupportedOperationException();
    }
}
