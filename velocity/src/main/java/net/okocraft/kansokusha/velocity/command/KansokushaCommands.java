package net.okocraft.kansokusha.velocity.command;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public final class KansokushaCommands {

    public static void register(CommandManager manager, Object plugin) {
        BrigadierCommand command = createCommand();
        manager.register(manager.metaBuilder(command).plugin(plugin).build(), command);
    }

    static BrigadierCommand createCommand() {
        return new BrigadierCommand(
            BrigadierCommand.literalArgumentBuilder("kansokusha")
                .requires(source -> source.hasPermission("kansokusha.command"))
                .then(VersionCommand.createVersionCommand())
        );
    }

    private KansokushaCommands() {
        throw new UnsupportedOperationException();
    }
}
