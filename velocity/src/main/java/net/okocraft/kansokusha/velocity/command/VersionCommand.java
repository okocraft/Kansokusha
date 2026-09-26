package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.okocraft.kansokusha.common.command.CommandMessages;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class VersionCommand {

    static final String UNKNOWN_VERSION = "unknown";

    static LiteralCommandNode<CommandSource> createVersionCommand() {
        return BrigadierCommand.literalArgumentBuilder("version")
            .requires(source -> source.hasPermission("kansokusha.command.version"))
            .executes(context -> {
                context.getSource().sendMessage(CommandMessages.VERSION_PRINT.apply(detectVersion()));
                return Command.SINGLE_SUCCESS;
            })
            .build();
    }

    private static String detectVersion() {
        String version = VersionCommand.class.getPackage().getImplementationVersion();
        return version != null ? version : UNKNOWN_VERSION;
    }

    private VersionCommand() {
        throw new UnsupportedOperationException();
    }
}
