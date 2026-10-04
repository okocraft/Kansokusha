package net.okocraft.kansokusha.common.search.query;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import dev.siroshun.mcmsgdef.Placeholder;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Map;

@NotNullByDefault
public final class SearchQueryMessages {

    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    private static final Placeholder<String> FIELD = field -> Argument.string("field", field);
    private static final Placeholder<String> VALUE = value -> Argument.string("value", value);

    public static final MessageKey.Arg1<String> MISSING = DEFINER
        .define("kansokusha.command.search.reason.missing", "<red>Specify a value for <aqua><field></aqua>.</red>")
        .with(FIELD);

    public static final MessageKey.Arg1<String> DUPLICATE = DEFINER
        .define("kansokusha.command.search.reason.duplicate", "<red>Specify <aqua><field></aqua> only once.</red>")
        .with(FIELD);

    public static final MessageKey TIME_CONFLICT = DEFINER
        .define(
            "kansokusha.command.search.reason.time-conflict",
            "<red>Use either <aqua>time</aqua> or <aqua>from/to</aqua>, not both.</red>"
        );

    public static final MessageKey TIME_ORDER = DEFINER
        .define(
            "kansokusha.command.search.reason.time-order",
            "<red>Set <aqua>from</aqua> before <aqua>to</aqua>.</red>"
        );

    public static final MessageKey RADIUS_UNAVAILABLE = DEFINER
        .define(
            "kansokusha.command.search.reason.radius-unavailable",
            "<red>Use <aqua>radius</aqua> as an in-game player on Paper/Folia, or specify <aqua>around</aqua> with a world and coordinates.</red>"
        );

    public static final MessageKey.Arg1<String> UNKNOWN_CONDITION = DEFINER
        .define(
            "kansokusha.command.search.reason.unknown-condition",
            "<red>Unknown condition <aqua><field></aqua>. Use Tab completion to choose a condition.</red>"
        )
        .with(FIELD);

    public static final MessageKey.Arg1<String> INVALID_ORDER = DEFINER
        .define(
            "kansokusha.command.search.reason.invalid-order",
            "<red>Invalid <aqua>order</aqua> value <aqua><value></aqua>. Specify <aqua>newest</aqua> or <aqua>oldest</aqua>.</red>"
        )
        .with(VALUE);

    public static final MessageKey.Arg1<String> INVALID_ACTOR_KIND = DEFINER
        .define(
            "kansokusha.command.search.reason.invalid-actor-kind",
            "<red>Invalid <aqua>actor-kind</aqua> value <aqua><value></aqua>. Specify <aqua>player</aqua>, <aqua>entity</aqua>, or <aqua>block</aqua>.</red>"
        )
        .with(VALUE);

    public static final MessageKey.Arg1<String> INVALID_UUID = DEFINER
        .define(
            "kansokusha.command.search.reason.invalid-uuid",
            "<red>Invalid <aqua>actor-uuid</aqua> value <aqua><value></aqua>. Specify a UUID.</red>"
        )
        .with(VALUE);

    public static final MessageKey.Arg2<String, String> INVALID_KEY = DEFINER
        .define(
            "kansokusha.command.search.reason.invalid-key",
            "<red>Invalid <aqua><field></aqua> value <aqua><value></aqua>. Use a lowercase identifier such as <aqua>minecraft:stone</aqua>.</red>"
        )
        .with(FIELD, VALUE);

    public static final MessageKey.Arg2<String, String> POSITIVE_INTEGER = DEFINER
        .define(
            "kansokusha.command.search.reason.positive-integer",
            "<red>Specify a positive integer for <aqua><field></aqua>; <aqua><value></aqua> is not valid.</red>"
        )
        .with(FIELD, VALUE);

    public static final MessageKey.Arg2<String, String> INVALID_INTEGER = DEFINER
        .define(
            "kansokusha.command.search.reason.invalid-integer",
            "<red>Specify an integer from <aqua>-2147483648</aqua> to <aqua>2147483647</aqua> for <aqua><field></aqua>; <aqua><value></aqua> is not valid.</red>"
        )
        .with(FIELD, VALUE);

    public static final MessageKey.Arg1<String> EMPTY = DEFINER
        .define(
            "kansokusha.command.search.reason.empty",
            "<red>Specify a non-empty value for <aqua><field></aqua>.</red>"
        )
        .with(FIELD);

    public static final MessageKey.Arg1<String> TIME_OUT_OF_RANGE = DEFINER
        .define(
            "kansokusha.command.search.reason.time-out-of-range",
            "<red>Time value <aqua><value></aqua> is out of range. Use a smaller duration or a supported date.</red>"
        )
        .with(VALUE);

    public static final MessageKey.Arg1<String> INVALID_TIME_RANGE = DEFINER
        .define(
            "kansokusha.command.search.reason.invalid-time-range",
            "<red>Invalid time range <aqua><value></aqua>. Use different durations such as <aqua>2h-1h</aqua>.</red>"
        )
        .with(VALUE);

    public static final MessageKey.Arg1<String> INVALID_DURATION = DEFINER
        .define(
            "kansokusha.command.search.reason.invalid-duration",
            "<red>Invalid duration <aqua><value></aqua>. Use a positive duration with <aqua>m/h/d/w</aqua>, such as <aqua>1h30m</aqua>.</red>"
        )
        .with(VALUE);

    public static final MessageKey.Arg1<String> INVALID_DATE = DEFINER
        .define(
            "kansokusha.command.search.reason.invalid-date",
            "<red>Invalid date or datetime <aqua><value></aqua>. Use <aqua>YYYY-MM-DD</aqua> or <aqua>YYYY-MM-DDTHH:MM:SS</aqua> with an optional offset.</red>"
        )
        .with(VALUE);

    public static final MessageKey INCOMPLETE_ESCAPE = DEFINER
        .define(
            "kansokusha.command.search.reason.incomplete-escape",
            "<red>The query ends with an incomplete escape. Add the escaped character or remove the final backslash.</red>"
        );

    public static final MessageKey UNTERMINATED_QUOTE = DEFINER
        .define(
            "kansokusha.command.search.reason.unterminated-quote",
            "<red>The query contains an unclosed quote. Add the closing quote.</red>"
        );

    public static final MessageKey INVALID_CURSOR = DEFINER
        .define(
            "kansokusha.command.search.reason.invalid-cursor",
            "<red>The page link is invalid. Run <aqua>/kansokusha search</aqua> again with your query.</red>"
        );

    public static @UnmodifiableView Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private SearchQueryMessages() {
        throw new UnsupportedOperationException();
    }
}
