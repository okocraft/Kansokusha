package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public final class SearchCommandMessages {

    public static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey.Arg1<String> PARSE_ERROR = DEFINER
        .define("kansokusha.command.search.parse-error", "Invalid search query: <reason>")
        .with(reason -> Argument.string("reason", reason));

    public static final MessageKey.Arg1<String> EVENT_PERMISSION = DEFINER
        .define(
            "kansokusha.command.search.event-permission",
            "You do not have permission to search event <event>."
        )
        .with(event -> Argument.string("event", event));

    public static final MessageKey.Arg1<String> LIMIT_RANGE = DEFINER
        .define(
            "kansokusha.command.search.limit-range",
            "limit must be between 1 and <max>."
        )
        .with(max -> Argument.string("max", max));

    public static final MessageKey SEARCH_FAILED = DEFINER
        .define("kansokusha.command.search.failed", "Search failed.");

    public static final MessageKey NO_RESULTS = DEFINER
        .define("kansokusha.command.search.no-results", "No matching events.");

    public static final MessageKey.Arg1<Component> RESULT = DEFINER
        .define("kansokusha.command.search.result", "<line>")
        .with(line -> Argument.component("line", line));

    public static final MessageKey PREVIOUS = DEFINER
        .define("kansokusha.command.search.previous", "Previous");

    public static final MessageKey NEXT = DEFINER
        .define("kansokusha.command.search.next", "Next");

    private SearchCommandMessages() {
        throw new UnsupportedOperationException();
    }
}
