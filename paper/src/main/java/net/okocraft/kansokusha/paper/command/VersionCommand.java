package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class VersionCommand {

    static final String UNKNOWN_VERSION = "unknown";

    static LiteralCommandNode<CommandSourceStack> createVersionCommand() {
        return Commands.literal("version")
            .requires(source -> source.getSender().hasPermission("kansokusha.command.version"))
            .executes(context -> {
                context.getSource().getSender().sendMessage(versionMessage(detectVersion()));
                return Command.SINGLE_SUCCESS;
            })
            .build();
    }

    static Component versionMessage(String version) {
        return Component.text("Kansokusha " + version);
    }

    private static String detectVersion() {
        String version = VersionCommand.class.getPackage().getImplementationVersion();
        return version != null ? version : UNKNOWN_VERSION;
    }

    private VersionCommand() {
        throw new UnsupportedOperationException();
    }
}
