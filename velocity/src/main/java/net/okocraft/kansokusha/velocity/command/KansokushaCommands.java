package net.okocraft.kansokusha.velocity.command;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

@NotNullByDefault
public final class KansokushaCommands {

    public static List<DefaultMessageDefiner> getDefiners() {
        return List.of(
            CommandMessages.DEFINER,
            SearchCommandMessages.DEFINER,
            EventCommandMessages.DEFINER
        );
    }

    public static void register(CommandManager manager, Object plugin) {
        BrigadierCommand command = createCommand();
        manager.register(manager.metaBuilder(command).plugin(plugin).build(), command);
    }

    public static void register(CommandManager manager, Object plugin, ZoneId searchTimeZone) {
        BrigadierCommand command = createCommand(Clock.systemUTC(), searchTimeZone);
        manager.register(manager.metaBuilder(command).plugin(plugin).build(), command);
    }

    static BrigadierCommand createCommand() {
        return createCommand(Clock.systemUTC(), ZoneId.systemDefault());
    }

    static BrigadierCommand createCommand(Clock clock, ZoneId searchTimeZone) {
        return new BrigadierCommand(
            BrigadierCommand.literalArgumentBuilder("kansokusha")
                .requires(source -> source.hasPermission("kansokusha.command"))
                .then(VersionCommand.createVersionCommand())
                .then(SearchCommand.createSearchCommand(clock, searchTimeZone))
                .then(EventCommand.createEventCommand())
        );
    }

    private KansokushaCommands() {
        throw new UnsupportedOperationException();
    }
}
