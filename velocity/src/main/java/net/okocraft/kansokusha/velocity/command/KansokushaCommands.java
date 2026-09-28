package net.okocraft.kansokusha.velocity.command;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
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

    public static void register(
        CommandManager manager,
        Object plugin,
        EventSearchBackend backend,
        ZoneId searchTimeZone
    ) {
        BrigadierCommand command = createCommand(backend, Clock.systemUTC(), searchTimeZone);
        manager.register(manager.metaBuilder(command).plugin(plugin).build(), command);
    }

    static BrigadierCommand createCommand(EventSearchBackend backend, Clock clock, ZoneId searchTimeZone) {
        return new BrigadierCommand(
            BrigadierCommand.literalArgumentBuilder("kansokusha")
                .requires(source -> source.hasPermission("kansokusha.command"))
                .then(VersionCommand.createVersionCommand())
                .then(SearchCommand.createSearchCommand(backend, clock, searchTimeZone))
                .then(EventCommand.createEventCommand(backend))
        );
    }

    private KansokushaCommands() {
        throw new UnsupportedOperationException();
    }
}
