package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.paper.inspection.InspectionSearchMessages;
import net.okocraft.kansokusha.paper.inspection.InspectionSessionManager;
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
            EventCommandMessages.DEFINER,
            InspectionCommandMessages.DEFINER,
            InspectionSearchMessages.DEFINER
        );
    }

    /**
     * Registers {@code /kansokusha}. The caller owns the inspection session lifecycle.
     */
    public static void register(
        Commands commands,
        EventSearchBackend backend,
        ZoneId searchTimeZone,
        InspectionSessionManager inspectionSessions
    ) {
        commands.register(createCommand(backend, Clock.systemUTC(), searchTimeZone, inspectionSessions));
    }

    static LiteralCommandNode<CommandSourceStack> createCommand(
        EventSearchBackend backend,
        Clock clock,
        ZoneId searchTimeZone,
        InspectionSessionManager inspectionSessions
    ) {
        return Commands.literal("kansokusha")
            .requires(source -> source.getSender().hasPermission("kansokusha.command"))
            .then(VersionCommand.createVersionCommand())
            .then(SearchCommand.createSearchCommand(backend, clock, searchTimeZone))
            .then(EventCommand.createEventCommand(backend))
            .then(InspectCommand.createInspectCommand("inspect", inspectionSessions))
            .then(InspectCommand.createInspectCommand("i", inspectionSessions))
            .build();
    }

    private KansokushaCommands() {
        throw new UnsupportedOperationException();
    }
}
