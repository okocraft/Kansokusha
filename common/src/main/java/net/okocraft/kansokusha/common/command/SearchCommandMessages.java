package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Map;

@NotNullByDefault
public final class SearchCommandMessages {

    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey.Arg1<Component> PARSE_ERROR = DEFINER
        .define("kansokusha.command.search.parse-error", "<red>Invalid search query: <reason></red>")
        .with(reason -> Argument.component("reason", reason));

    public static final MessageKey.Arg1<String> EVENT_PERMISSION = DEFINER
        .define(
            "kansokusha.command.search.event-permission",
            "<red>You do not have permission to search event <aqua><event></aqua>. Contact a server administrator.</red>"
        )
        .with(event -> Argument.string("event", event));

    public static final MessageKey.Arg1<Integer> LIMIT_RANGE = DEFINER
        .define(
            "kansokusha.command.search.limit-range",
            "<red>Specify <aqua>limit</aqua> between <aqua>1</aqua> and <aqua><max></aqua>.</red>"
        )
        .with(max -> Argument.numeric("max", max));

    public static final MessageKey SEARCH_FAILED = DEFINER
        .define(
            "kansokusha.command.search.failed",
            "<red>Failed to search events. Contact a server administrator.</red>"
        );

    public static final MessageKey NO_RESULTS = DEFINER
        .define(
            "kansokusha.command.search.no-results",
            "<gray>No matching events were found. Try fewer conditions or a wider time range.</gray>"
        );

    public static final MessageKey.Arg1<Component> RESULT = DEFINER
        .define("kansokusha.command.search.result", "<gray><line></gray>")
        .with(line -> Argument.component("line", line));

    public static final MessageKey PREVIOUS = DEFINER
        .define("kansokusha.command.search.previous", "<gold>[Previous]</gold>");

    public static final MessageKey NEXT = DEFINER
        .define("kansokusha.command.search.next", "<gold>[Next]</gold>");

    public static final MessageKey.Arg1<String> VIEW_EVENT = DEFINER
        .define(
            "kansokusha.command.search.view-event",
            "<gray>Click to view event details<newline><aqua><event_id></aqua></gray>"
        )
        .with(event_id -> Argument.string("event_id", event_id));

    public static @UnmodifiableView Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private SearchCommandMessages() {
        throw new UnsupportedOperationException();
    }
}
