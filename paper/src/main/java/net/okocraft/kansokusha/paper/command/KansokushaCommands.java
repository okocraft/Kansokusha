package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.common.command.CommandHelp;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.EventFieldMessages;
import net.okocraft.kansokusha.common.command.EventPresentationMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.query.SearchQueryMessages;
import net.okocraft.kansokusha.paper.builtin.PaperEventPresenter;
import net.okocraft.kansokusha.paper.builtin.PaperViewMessages;
import net.okocraft.kansokusha.paper.inspection.InspectionSearchMessages;
import net.okocraft.kansokusha.paper.inspection.InspectionSessionManager;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@NotNullByDefault
public final class KansokushaCommands {

    public static List<Map<String, String>> getDefaultMessages() {
        var defaults = new ArrayList<>(List.of(
            CommandMessages.defaultMessages(),
            EventPresentationMessages.defaultMessages(),
            SearchCommandMessages.defaultMessages(),
            SearchQueryMessages.defaultMessages(),
            EventCommandMessages.defaultMessages(),
            InspectionCommandMessages.defaultMessages(),
            InspectionSearchMessages.defaultMessages(),
            EventFieldMessages.defaultMessages(),
            PaperViewMessages.defaultMessages()
        ));
        defaults.addAll(PaperEventPresenter.defaultMessages());
        return List.copyOf(defaults);
    }

    /**
     * Registers {@code /kansokusha}. The caller owns the inspection session lifecycle.
     */
    public static void register(
        Commands commands,
        EventSearchBackend backend,
        ZoneId searchTimeZone,
        InspectionSessionManager inspectionSessions,
        Plugin plugin
    ) {
        commands.register(createCommand(backend, Clock.systemUTC(), searchTimeZone, inspectionSessions, plugin));
    }

    static LiteralCommandNode<CommandSourceStack> createCommand(
        EventSearchBackend backend,
        Clock clock,
        ZoneId searchTimeZone,
        InspectionSessionManager inspectionSessions,
        Plugin plugin
    ) {
        return Commands.literal("kansokusha")
            .requires(source -> source.getSender().hasPermission("kansokusha.command"))
            .executes(context -> sendHelp(context.getSource()))
            .then(Commands.literal("help").executes(context -> sendHelp(context.getSource())))
            .then(VersionCommand.createVersionCommand())
            .then(SearchCommand.createSearchCommand(backend, clock, searchTimeZone))
            .then(EventCommand.createEventCommand(backend, clock, searchTimeZone, plugin))
            .then(InspectCommand.createInspectCommand("inspect", inspectionSessions, backend, clock, searchTimeZone))
            .then(InspectCommand.createInspectCommand("i", inspectionSessions, backend, clock, searchTimeZone))
            .build();
    }

    private static int sendHelp(CommandSourceStack source) {
        var sender = source.getSender();
        CommandHelp.send(sender::hasPermission, sender::sendMessage);
        if (sender.hasPermission(InspectionSessionManager.PERMISSION)) {
            sender.sendMessage(InspectionCommandMessages.HELP_INSPECT.asComponent());
        }
        return 1;
    }

    private KansokushaCommands() {
        throw new UnsupportedOperationException();
    }
}
