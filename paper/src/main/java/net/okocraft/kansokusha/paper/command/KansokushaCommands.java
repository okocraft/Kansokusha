package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
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

    public static void register(Commands commands) {
        commands.register(createCommand());
    }

    public static void register(Commands commands, ZoneId searchTimeZone) {
        commands.register(createCommand(Clock.systemUTC(), searchTimeZone));
    }

    static LiteralCommandNode<CommandSourceStack> createCommand() {
        return createCommand(Clock.systemUTC(), ZoneId.systemDefault());
    }

    static LiteralCommandNode<CommandSourceStack> createCommand(Clock clock, ZoneId searchTimeZone) {
        return Commands.literal("kansokusha")
            .requires(source -> source.getSender().hasPermission("kansokusha.command"))
            .then(VersionCommand.createVersionCommand())
            .then(SearchCommand.createSearchCommand(clock, searchTimeZone))
            .then(EventCommand.createEventCommand())
            .build();
    }

    private KansokushaCommands() {
        throw new UnsupportedOperationException();
    }
}
