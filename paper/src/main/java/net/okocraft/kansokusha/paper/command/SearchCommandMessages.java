package net.okocraft.kansokusha.paper.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class SearchCommandMessages {

    static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    static final MessageKey PARSE_ERROR = DEFINER
        .define("kansokusha.command.search.parse-error", "Invalid search query.");

    static final MessageKey.Arg1<String> EVENT_PERMISSION = DEFINER
        .define(
            "kansokusha.command.search.event-permission",
            "You do not have permission to search event <event>."
        )
        .with(event -> Argument.string("event", event));

    static final MessageKey RADIUS_PLAYER_ONLY = DEFINER
        .define(
            "kansokusha.command.search.radius-player-only",
            "radius is only available to players."
        );

    static final MessageKey.Arg1<String> LIMIT_RANGE = DEFINER
        .define(
            "kansokusha.command.search.limit-range",
            "limit must be between 1 and <max>."
        )
        .with(max -> Argument.string("max", max));

    static final MessageKey SEARCH_FAILED = DEFINER
        .define("kansokusha.command.search.failed", "Search failed.");

    static final MessageKey NO_RESULTS = DEFINER
        .define("kansokusha.command.search.no-results", "No matching events.");

    static final MessageKey.Arg1<Component> RESULT = DEFINER
        .define("kansokusha.command.search.result", "<line>")
        .with(line -> Argument.component("line", line));

    static final MessageKey PREVIOUS = DEFINER
        .define("kansokusha.command.search.previous", "Previous");

    static final MessageKey NEXT = DEFINER
        .define("kansokusha.command.search.next", "Next");

    private SearchCommandMessages() {
        throw new UnsupportedOperationException();
    }
}
