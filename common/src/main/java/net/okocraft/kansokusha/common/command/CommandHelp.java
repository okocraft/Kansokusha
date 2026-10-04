package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.Consumer;
import java.util.function.Predicate;

@NotNullByDefault
public final class CommandHelp {

    public static void send(Predicate<String> hasPermission, Consumer<Component> sendMessage) {
        sendMessage.accept(CommandMessages.HELP_HEADER.asComponent());
        if (hasPermission.test("kansokusha.command.version")) {
            sendMessage.accept(CommandMessages.HELP_VERSION.asComponent());
        }
        if (hasPermission.test(SearchCommandSupport.PERMISSION)) {
            sendMessage.accept(CommandMessages.HELP_SEARCH.asComponent());
        }
        if (hasPermission.test(EventCommandSupport.PERMISSION)) {
            sendMessage.accept(CommandMessages.HELP_EVENT.asComponent());
        }
    }

    private CommandHelp() {
        throw new UnsupportedOperationException();
    }
}
