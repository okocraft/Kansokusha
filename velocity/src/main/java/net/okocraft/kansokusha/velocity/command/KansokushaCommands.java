package net.okocraft.kansokusha.velocity.command;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import net.okocraft.kansokusha.common.command.CommandHelp;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.query.SearchQueryMessages;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@NotNullByDefault
public final class KansokushaCommands {

    public static List<Map<String, String>> getDefaultMessages() {
        return List.of(
            CommandMessages.defaultMessages(),
            SearchCommandMessages.defaultMessages(),
            SearchQueryMessages.defaultMessages(),
            EventCommandMessages.defaultMessages()
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
                .executes(context -> sendHelp(context.getSource()))
                .then(BrigadierCommand.literalArgumentBuilder("help")
                    .executes(context -> sendHelp(context.getSource())))
                .then(VersionCommand.createVersionCommand())
                .then(SearchCommand.createSearchCommand(backend, clock, searchTimeZone))
                .then(EventCommand.createEventCommand(backend))
        );
    }

    private static int sendHelp(CommandSource source) {
        CommandHelp.send(source::hasPermission, source::sendMessage);
        return 1;
    }

    private KansokushaCommands() {
        throw new UnsupportedOperationException();
    }
}
