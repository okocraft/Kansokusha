package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.common.command.CommandMessages;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;

@NotNullByDefault
public final class KansokushaCommands {

    public static List<DefaultMessageDefiner> getDefiners() {
        return List.of(CommandMessages.DEFINER);
    }

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
