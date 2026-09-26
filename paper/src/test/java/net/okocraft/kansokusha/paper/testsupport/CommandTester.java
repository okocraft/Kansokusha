package net.okocraft.kansokusha.paper.testsupport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.List;

/**
 * Runs a command through a {@link CommandDispatcher} so tests exercise the command tree itself.
 */
public final class CommandTester {

    public static CommandTester of(LiteralCommandNode<CommandSourceStack> command) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command);
        return new CommandTester(dispatcher);
    }

    private final CommandDispatcher<CommandSourceStack> dispatcher;

    private CommandTester(CommandDispatcher<CommandSourceStack> dispatcher) {
        this.dispatcher = dispatcher;
    }

    public int execute(CommandSourceStack source, String input) throws CommandSyntaxException {
        return this.dispatcher.execute(input, source);
    }

    public List<String> suggest(CommandSourceStack source, String input) {
        return this.dispatcher.getCompletionSuggestions(this.dispatcher.parse(input, source))
            .join()
            .getList()
            .stream()
            .map(Suggestion::getText)
            .toList();
    }
}
