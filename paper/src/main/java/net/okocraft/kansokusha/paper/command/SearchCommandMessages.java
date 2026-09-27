package net.okocraft.kansokusha.paper.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class SearchCommandMessages {

    static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    static final MessageKey.Arg1<String> PARSE_ERROR = DEFINER
        .define("kansokusha.command.search.parse-error", "Invalid search query.<unused>")
        .with(unused -> Argument.string("unused", unused));

    static final MessageKey.Arg1<String> EVENT_PERMISSION = DEFINER
        .define(
            "kansokusha.command.search.event-permission",
            "You do not have permission to search event <event>."
        )
        .with(event -> Argument.string("event", event));

    static final MessageKey.Arg1<String> RADIUS_PLAYER_ONLY = DEFINER
        .define(
            "kansokusha.command.search.radius-player-only",
            "radius is only available to players.<unused>"
        )
        .with(unused -> Argument.string("unused", unused));

    static final MessageKey.Arg1<String> LIMIT_RANGE = DEFINER
        .define(
            "kansokusha.command.search.limit-range",
            "limit must be between 1 and <max>."
        )
        .with(max -> Argument.string("max", max));

    static final MessageKey.Arg1<String> SEARCH_FAILED = DEFINER
        .define("kansokusha.command.search.failed", "Search failed.<unused>")
        .with(unused -> Argument.string("unused", unused));

    static final MessageKey.Arg1<String> NO_RESULTS = DEFINER
        .define("kansokusha.command.search.no-results", "No matching events.<unused>")
        .with(unused -> Argument.string("unused", unused));

    static final MessageKey.Arg1<String> RESULT = DEFINER
        .define("kansokusha.command.search.result", "<line>")
        .with(line -> Argument.string("line", line));

    static final MessageKey.Arg1<String> PREVIOUS = DEFINER
        .define("kansokusha.command.search.previous", "Previous<unused>")
        .with(unused -> Argument.string("unused", unused));

    static final MessageKey.Arg1<String> NEXT = DEFINER
        .define("kansokusha.command.search.next", "Next<unused>")
        .with(unused -> Argument.string("unused", unused));

    private SearchCommandMessages() {
        throw new UnsupportedOperationException();
    }
}
