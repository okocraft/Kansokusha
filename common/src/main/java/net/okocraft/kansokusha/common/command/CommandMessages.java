package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Map;

@NotNullByDefault
public final class CommandMessages {

    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey.Arg1<String> VERSION_PRINT = DEFINER
        .define("kansokusha.command.version.print", "<gray>Kansokusha <aqua><version></aqua></gray>")
        .with(version -> Argument.string("version", version));

    public static final MessageKey HELP_HEADER = DEFINER
        .define("kansokusha.command.help.header", "<gold>Kansokusha commands</gold>");

    public static final MessageKey HELP_VERSION = DEFINER
        .define(
            "kansokusha.command.help.version",
            "<aqua>/kansokusha version</aqua><dark_gray> - </dark_gray><gray>Show the plugin version</gray>"
        );

    public static final MessageKey HELP_SEARCH = DEFINER
        .define(
            "kansokusha.command.help.search",
            "<aqua>/kansokusha search [query]</aqua><dark_gray> - </dark_gray><gray>Search recorded events</gray>"
        );

    public static final MessageKey HELP_EVENT = DEFINER
        .define(
            "kansokusha.command.help.event",
            "<aqua>/kansokusha event \\<event-id></aqua><dark_gray> - </dark_gray><gray>Show event details</gray>"
        );

    public static @UnmodifiableView Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private CommandMessages() {
        throw new UnsupportedOperationException();
    }
}
