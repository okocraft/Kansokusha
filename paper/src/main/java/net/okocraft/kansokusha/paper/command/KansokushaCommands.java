package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
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
            InspectionCommandMessages.DEFINER
        );
    }

    /**
     * Registers the legacy command surface without inspection commands.
     *
     * <p>Inspection requires an explicitly managed session lifecycle, so callers that need it
     * must use the overload accepting {@link InspectionSessionManager}.</p>
     */
    public static void register(Commands commands) {
        commands.register(createCommand());
    }

    /**
     * Registers the legacy command surface without inspection commands.
     *
     * <p>Inspection requires an explicitly managed session lifecycle, so callers that need it
     * must use the overload accepting {@link InspectionSessionManager}.</p>
     */
    public static void register(Commands commands, ZoneId searchTimeZone) {
        commands.register(createCommand(Clock.systemUTC(), searchTimeZone));
    }

    /**
     * Registers commands including inspection mode backed by the supplied session manager.
     *
     * <p>The caller owns that manager's lifecycle and must remove player state on quit and clear
     * all state when the plugin is disabled.</p>
     */
    public static void register(
        Commands commands,
        ZoneId searchTimeZone,
        InspectionSessionManager inspectionSessions
    ) {
        commands.register(createCommand(Clock.systemUTC(), searchTimeZone, inspectionSessions));
    }

    static LiteralCommandNode<CommandSourceStack> createCommand() {
        return createCommand(Clock.systemUTC(), ZoneId.systemDefault());
    }

    static LiteralCommandNode<CommandSourceStack> createCommand(InspectionSessionManager inspectionSessions) {
        return createCommand(Clock.systemUTC(), ZoneId.systemDefault(), inspectionSessions);
    }

    static LiteralCommandNode<CommandSourceStack> createCommand(Clock clock, ZoneId searchTimeZone) {
        return createBaseCommand(clock, searchTimeZone).build();
    }

    static LiteralCommandNode<CommandSourceStack> createCommand(
        Clock clock,
        ZoneId searchTimeZone,
        InspectionSessionManager inspectionSessions
    ) {
        return createBaseCommand(clock, searchTimeZone)
            .then(InspectCommand.createInspectCommand("inspect", inspectionSessions))
            .then(InspectCommand.createInspectCommand("i", inspectionSessions))
            .build();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createBaseCommand(
        Clock clock,
        ZoneId searchTimeZone
    ) {
        return Commands.literal("kansokusha")
            .requires(source -> source.getSender().hasPermission("kansokusha.command"))
            .then(VersionCommand.createVersionCommand())
            .then(SearchCommand.createSearchCommand(clock, searchTimeZone))
            .then(EventCommand.createEventCommand());
    }

    private KansokushaCommands() {
        throw new UnsupportedOperationException();
    }
}
