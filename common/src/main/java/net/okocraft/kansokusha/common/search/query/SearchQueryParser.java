package net.okocraft.kansokusha.common.search.query;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.common.search.query.SearchQuery.ActorKind;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Around;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Conditions;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Order;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Position;
import net.okocraft.kansokusha.common.search.query.SearchQuery.TimeRange;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parser for the platform-neutral modifiers following {@code /kansokusha search}.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class SearchQueryParser {

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([mhdw])");

    private static final List<String> CONDITION_SUGGESTIONS = List.of(
        "user",
        "action",
        "time",
        "radius",
        "include",
        "target",
        "filter",
        "actor-uuid",
        "actor-kind",
        "actor-type",
        "world",
        "position",
        "around"
    );
    private static final List<String> MODIFIER_SUGGESTIONS = Stream.concat(
        CONDITION_SUGGESTIONS.stream(),
        Stream.of("from", "to", "exclude", "order", "limit")
    ).toList();
    private static final List<String> ACTOR_KIND_SUGGESTIONS =
        List.of("player", "entity", "block");
    private static final List<String> ORDER_SUGGESTIONS = List.of("newest", "oldest");
    private static final Set<String> SINGLETON_MODIFIERS = Set.of("from", "to", "order", "limit");

    private SearchQueryParser() {
    }

    /**
     * Parses a raw modifier string. Single and double quoted values are supported.
     *
     * @param origin the executing player's position used as the center of {@code radius},
     *               or {@code null} when the sender has no position
     */
    public static SearchQuery parse(String input, Clock clock, ZoneId timezone, @Nullable Position origin) {
        Objects.requireNonNull(input, "input");
        return parse(tokenize(input), clock, timezone, origin);
    }

    /**
     * Parses already-tokenized modifier arguments.
     */
    public static SearchQuery parse(
        List<String> arguments,
        Clock clock,
        ZoneId timezone,
        @Nullable Position origin
    ) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(timezone, "timezone");

        var cursor = new Cursor(arguments);
        var context = new Context(clock.instant(), timezone, origin);
        var conditions = new MutableConditions();
        var exclusions = new MutableConditions();
        var singletons = new HashSet<String>();
        var order = Order.NEWEST;
        var limit = OptionalInt.empty();
        var from = Optional.<Instant>empty();
        var to = Optional.<Instant>empty();

        while (cursor.hasNext()) {
            var modifier = cursor.next("modifier");
            if (SINGLETON_MODIFIERS.contains(modifier) && !singletons.add(modifier)) {
                throw error(modifier + " may only be specified once", SearchQueryMessages.DUPLICATE.apply(modifier));
            }
            switch (modifier) {
                case "from" -> from = Optional.of(parseAbsoluteBound(cursor.next("from"), timezone, false));
                case "to" -> to = Optional.of(parseAbsoluteBound(cursor.next("to"), timezone, true));
                case "exclude" -> parseCondition(cursor.next("exclude condition"), "exclude ", cursor, exclusions, context);
                case "order" -> order = parseOrder(cursor.next("order"));
                case "limit" -> limit = OptionalInt.of(parsePositiveInt(cursor.next("limit"), "limit"));
                default -> parseCondition(modifier, "", cursor, conditions, context);
            }
        }

        if (from.isPresent() || to.isPresent()) {
            if (!conditions.timeRanges.isEmpty()) {
                throw error("time cannot be combined with from/to", SearchQueryMessages.TIME_CONFLICT.asComponent());
            }
            try {
                conditions.timeRanges.add(new TimeRange(from, to));
            } catch (IllegalArgumentException e) {
                throw error("time range start must be before its end", SearchQueryMessages.TIME_ORDER.asComponent(), e);
            }
        }

        return new SearchQuery(conditions.freeze(), exclusions.freeze(), order, limit);
    }

    /**
     * Interprets a partially typed query for platform completion without duplicating the query
     * grammar in a platform module.
     *
     * @param radiusAvailable whether the sender has a position that {@code radius} can use
     */
    public static Completion completion(String input, Clock clock, ZoneId timezone, boolean radiusAvailable) {
        Objects.requireNonNull(input, "input");

        var partial = tokenizeForCompletion(input);
        // Completion only needs to know whether radius is accepted, not the actual position.
        var origin = radiusAvailable ? new Position(Key.key("kansokusha", "completion"), 0, 0, 0) : null;
        CompletionKind kind;
        try {
            parse(partial.completedArguments(), clock, timezone, origin);
            kind = CompletionKind.MODIFIER;
        } catch (SearchQueryParseException e) {
            kind = completionKind(e.expected());
        }

        return new Completion(
            kind,
            partial.prefix(),
            partial.replacementStart(),
            staticSuggestions(kind, radiusAvailable)
        );
    }

    private static CompletionKind completionKind(@Nullable String expected) {
        if (expected == null) {
            return CompletionKind.NONE;
        }

        if (expected.equals("exclude condition")) {
            return CompletionKind.EXCLUDE_CONDITION;
        }
        return switch (expected.startsWith("exclude ") ? expected.substring("exclude ".length()) : expected) {
            case "user" -> CompletionKind.USER;
            case "action" -> CompletionKind.ACTION;
            case "include", "target" -> CompletionKind.TARGET;
            case "actor-kind" -> CompletionKind.ACTOR_KIND;
            case "actor-type" -> CompletionKind.ACTOR_TYPE;
            case "world" -> CompletionKind.WORLD;
            case "order" -> CompletionKind.ORDER;
            default -> CompletionKind.NONE;
        };
    }

    private static List<String> staticSuggestions(CompletionKind kind, boolean radiusAvailable) {
        var suggestions = switch (kind) {
            case MODIFIER -> MODIFIER_SUGGESTIONS;
            case EXCLUDE_CONDITION -> CONDITION_SUGGESTIONS;
            case ACTOR_KIND -> ACTOR_KIND_SUGGESTIONS;
            case ORDER -> ORDER_SUGGESTIONS;
            default -> List.<String>of();
        };
        return radiusAvailable
            ? suggestions
            : suggestions.stream().filter(value -> !value.equals("radius")).toList();
    }

    private static CompletionTokens tokenizeForCompletion(String input) {
        var scanned = scanTokens(input, true);
        if (!scanned.tokenInProgress()) {
            return new CompletionTokens(
                scanned.tokens().stream().map(InputToken::value).toList(),
                "",
                input.length()
            );
        }

        var current = scanned.tokens().getLast();
        return new CompletionTokens(
            scanned.tokens().subList(0, scanned.tokens().size() - 1)
                .stream()
                .map(InputToken::value)
                .toList(),
            current.value(),
            current.start()
        );
    }

    /**
     * Returns the final query token using the exact quote and escape rules used by {@link #parse}.
     */
    public static Optional<InputToken> trailingToken(String input) {
        Objects.requireNonNull(input, "input");
        var scanned = scanTokens(input, false);
        return scanned.tokens().isEmpty()
            ? Optional.empty()
            : Optional.of(scanned.tokens().getLast());
    }

    public enum CompletionKind {
        MODIFIER,
        USER,
        ACTION,
        TARGET,
        ACTOR_KIND,
        ACTOR_TYPE,
        WORLD,
        ORDER,
        EXCLUDE_CONDITION,
        NONE
    }

    public record Completion(
        CompletionKind kind,
        String prefix,
        int replacementStart,
        List<String> staticSuggestions
    ) {

        public Completion {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(prefix, "prefix");
            staticSuggestions = List.copyOf(staticSuggestions);
        }
    }

    private record CompletionTokens(
        List<String> completedArguments,
        String prefix,
        int replacementStart
    ) {
    }

    public record InputToken(
        String value,
        int start,
        int end,
        boolean quotedOrEscaped
    ) {

        public InputToken {
            Objects.requireNonNull(value, "value");
        }
    }

    private record ScanResult(
        List<InputToken> tokens,
        boolean tokenInProgress
    ) {
    }

    private static void parseCondition(
        String field,
        String labelPrefix,
        Cursor cursor,
        MutableConditions target,
        Context context
    ) {
        var label = labelPrefix + field;
        switch (field) {
            case "user" -> target.users.add(nonEmpty(cursor.next(label), label));
            case "action" -> target.actions.add(parseActionKey(cursor.next(label)));
            case "time" -> target.timeRanges.add(parseRelativeTime(cursor.next(label), context));
            case "radius" -> {
                var radius = parsePositiveInt(cursor.next(label), label);
                var origin = context.origin();
                if (origin == null) {
                    throw error(
                        "radius requires the position of a player",
                        SearchQueryMessages.RADIUS_UNAVAILABLE.asComponent()
                    );
                }
                target.around.add(new Around(origin.world(), origin.x(), origin.z(), radius));
            }
            case "include", "target" -> target.targets.add(parseKey(cursor.next(label), label));
            case "filter" -> target.filters.add(nonEmpty(cursor.next(label), label));
            case "actor-uuid" -> target.actorUuids.add(parseUuid(cursor.next(label)));
            case "actor-kind" -> target.actorKinds.add(parseActorKind(cursor.next(label)));
            case "actor-type" -> target.actorTypes.add(parseKey(cursor.next(label), label));
            case "world" -> target.worlds.add(parseKey(cursor.next(label), label));
            case "position" -> target.positions.add(parsePosition(cursor));
            case "around" -> target.around.add(parseAround(cursor));
            default -> throw error("unknown condition: " + label, SearchQueryMessages.UNKNOWN_CONDITION.apply(label));
        }
    }

    private static Position parsePosition(Cursor cursor) {
        var world = parseKey(cursor.next("position world"), "position world");
        var x = parseInt(cursor.next("position x"), "position x");
        var y = parseInt(cursor.next("position y"), "position y");
        var z = parseInt(cursor.next("position z"), "position z");
        return new Position(world, x, y, z);
    }

    private static Around parseAround(Cursor cursor) {
        var world = parseKey(cursor.next("around world"), "around world");
        var x = parseInt(cursor.next("around x"), "around x");
        var z = parseInt(cursor.next("around z"), "around z");
        var radius = parsePositiveInt(cursor.next("around radius"), "around radius");
        return new Around(world, x, z, radius);
    }

    private static Order parseOrder(String value) {
        return switch (value) {
            case "newest" -> Order.NEWEST;
            case "oldest" -> Order.OLDEST;
            default -> throw error(
                "order must be newest or oldest: " + value,
                SearchQueryMessages.INVALID_ORDER.apply(value)
            );
        };
    }

    private static ActorKind parseActorKind(String value) {
        return switch (value) {
            case "player" -> ActorKind.PLAYER;
            case "entity" -> ActorKind.ENTITY;
            case "block" -> ActorKind.BLOCK;
            default -> throw error(
                "actor-kind must be player, entity, or block: " + value,
                SearchQueryMessages.INVALID_ACTOR_KIND.apply(value)
            );
        };
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw error("invalid actor UUID: " + value, SearchQueryMessages.INVALID_UUID.apply(value), e);
        }
    }

    private static Key parseActionKey(String value) {
        if (value.indexOf(':') < 0) {
            return parseKey("kansokusha:" + value, "action");
        }
        return parseKey(value, "action");
    }

    private static Key parseKey(String value, String field) {
        try {
            return Key.key(value);
        } catch (RuntimeException e) {
            throw error("invalid " + field + " key: " + value, SearchQueryMessages.INVALID_KEY.apply(field, value), e);
        }
    }

    private static int parsePositiveInt(String value, String field) {
        var parsed = parseInt(value, field);
        if (parsed <= 0) {
            throw error(
                field + " must be greater than zero: " + value,
                SearchQueryMessages.POSITIVE_INTEGER.apply(field, value)
            );
        }
        return parsed;
    }

    private static int parseInt(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw error("invalid " + field + ": " + value, SearchQueryMessages.INVALID_INTEGER.apply(field, value), e);
        }
    }

    private static String nonEmpty(String value, String field) {
        if (value.isEmpty()) {
            throw error(field + " must not be empty", SearchQueryMessages.EMPTY.apply(field));
        }
        return value;
    }

    private static TimeRange parseRelativeTime(String value, Context context) {
        var now = context.now();
        var timezone = context.timezone();
        if ("today".equals(value) || "yesterday".equals(value)) {
            var today = now.atZone(timezone).toLocalDate();
            var date = "today".equals(value) ? today : today.minusDays(1);
            return TimeRange.bounded(
                date.atStartOfDay(timezone).toInstant(),
                date.plusDays(1).atStartOfDay(timezone).toInstant()
            );
        }

        var separator = value.indexOf('-');
        if (separator < 0) {
            var duration = parseDuration(value);
            try {
                return TimeRange.bounded(now.minus(duration), now);
            } catch (DateTimeException | ArithmeticException e) {
                throw error(
                    "relative time is out of range: " + value,
                    SearchQueryMessages.TIME_OUT_OF_RANGE.apply(value),
                    e
                );
            }
        }
        if (separator == 0 || separator == value.length() - 1 || separator != value.lastIndexOf('-')) {
            throw error("invalid relative time range: " + value, SearchQueryMessages.INVALID_TIME_RANGE.apply(value));
        }

        var first = parseDuration(value.substring(0, separator));
        var second = parseDuration(value.substring(separator + 1));
        if (first.equals(second)) {
            throw error(
                "relative time range must span a non-zero interval: " + value,
                SearchQueryMessages.INVALID_TIME_RANGE.apply(value)
            );
        }

        var older = first.compareTo(second) > 0 ? first : second;
        var newer = first.compareTo(second) > 0 ? second : first;
        try {
            return TimeRange.bounded(now.minus(older), now.minus(newer));
        } catch (DateTimeException | ArithmeticException e) {
            throw error(
                "relative time range is out of range: " + value,
                SearchQueryMessages.TIME_OUT_OF_RANGE.apply(value),
                e
            );
        }
    }

    private static Duration parseDuration(String value) {
        var matcher = DURATION_PART.matcher(value);
        var position = 0;
        var found = false;
        var duration = Duration.ZERO;
        try {
            while (matcher.find()) {
                if (matcher.start() != position) {
                    throw error(
                        "invalid relative duration: " + value,
                        SearchQueryMessages.INVALID_DURATION.apply(value)
                    );
                }
                found = true;
                var amount = Long.parseLong(matcher.group(1));
                var part = switch (matcher.group(2)) {
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    case "d" -> Duration.ofDays(amount);
                    case "w" -> Duration.ofDays(Math.multiplyExact(amount, 7L));
                    default -> throw new AssertionError();
                };
                duration = duration.plus(part);
                position = matcher.end();
            }
        } catch (NumberFormatException | ArithmeticException e) {
            throw error(
                "relative duration is out of range: " + value,
                SearchQueryMessages.TIME_OUT_OF_RANGE.apply(value),
                e
            );
        }

        if (!found || position != value.length() || duration.isZero()) {
            throw error("invalid relative duration: " + value, SearchQueryMessages.INVALID_DURATION.apply(value));
        }
        return duration;
    }

    private static Instant parseAbsoluteBound(String value, ZoneId timezone, boolean upperBound) {
        try {
            var date = LocalDate.parse(value);
            var boundaryDate = upperBound ? date.plusDays(1) : date;
            return boundaryDate.atStartOfDay(timezone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Not a date-only value.
        } catch (DateTimeException e) {
            throw error(
                "absolute date is out of range: " + value,
                SearchQueryMessages.TIME_OUT_OF_RANGE.apply(value),
                e
            );
        }

        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try local date-time below.
        }

        try {
            return LocalDateTime.parse(value).atZone(timezone).toInstant();
        } catch (DateTimeParseException e) {
            throw error("invalid date or datetime: " + value, SearchQueryMessages.INVALID_DATE.apply(value), e);
        } catch (DateTimeException e) {
            throw error("datetime is out of range: " + value, SearchQueryMessages.TIME_OUT_OF_RANGE.apply(value), e);
        }
    }

    static List<String> tokenize(String input) {
        return scanTokens(input, false).tokens().stream()
            .map(InputToken::value)
            .toList();
    }

    private static ScanResult scanTokens(String input, boolean tolerateIncomplete) {
        var tokens = new ArrayList<InputToken>();
        var token = new StringBuilder();
        var tokenStarted = false;
        var tokenStart = input.length();
        var quotedOrEscaped = false;
        var quote = '\0';
        var escaping = false;

        for (var index = 0; index < input.length(); index++) {
            var character = input.charAt(index);
            if (!tokenStarted && !Character.isWhitespace(character)) {
                tokenStarted = true;
                tokenStart = index;
            }

            if (escaping) {
                token.append(character);
                escaping = false;
                continue;
            }
            if (character == '\\') {
                escaping = true;
                quotedOrEscaped = true;
                continue;
            }
            if (quote != '\0') {
                if (character == quote) {
                    quote = '\0';
                } else {
                    token.append(character);
                }
                continue;
            }
            if (character == '"' || character == '\'') {
                quote = character;
                quotedOrEscaped = true;
                continue;
            }
            if (Character.isWhitespace(character)) {
                if (tokenStarted) {
                    tokens.add(new InputToken(
                        token.toString(),
                        tokenStart,
                        index,
                        quotedOrEscaped
                    ));
                    token.setLength(0);
                    tokenStarted = false;
                    tokenStart = input.length();
                    quotedOrEscaped = false;
                }
                continue;
            }
            token.append(character);
        }

        if (escaping) {
            if (!tolerateIncomplete) {
                throw error(
                    "query ends with an incomplete escape",
                    SearchQueryMessages.INCOMPLETE_ESCAPE.asComponent()
                );
            }
            token.append('\\');
        }
        if (quote != '\0' && !tolerateIncomplete) {
            throw error("query contains an unterminated quote", SearchQueryMessages.UNTERMINATED_QUOTE.asComponent());
        }
        if (tokenStarted) {
            tokens.add(new InputToken(
                token.toString(),
                tokenStart,
                input.length(),
                quotedOrEscaped
            ));
        }
        return new ScanResult(List.copyOf(tokens), tokenStarted);
    }

    private static SearchQueryParseException error(String message, Component reason) {
        return new SearchQueryParseException(message, reason);
    }

    private static SearchQueryParseException error(String message, Component reason, Throwable cause) {
        return new SearchQueryParseException(message, reason, cause);
    }

    private record Context(Instant now, ZoneId timezone, @Nullable Position origin) {
    }

    private static final class Cursor {

        private final List<String> arguments;
        private int index;

        private Cursor(List<String> arguments) {
            this.arguments = List.copyOf(arguments);
            for (var argument : this.arguments) {
                Objects.requireNonNull(argument, "arguments must not contain null");
            }
        }

        private boolean hasNext() {
            return this.index < this.arguments.size();
        }

        private String next(String expected) {
            if (!this.hasNext()) {
                throw SearchQueryParseException.missing(expected);
            }
            return this.arguments.get(this.index++);
        }
    }

    private static final class MutableConditions {

        private final Set<String> users = new LinkedHashSet<>();
        private final Set<Key> actions = new LinkedHashSet<>();
        private final Set<TimeRange> timeRanges = new LinkedHashSet<>();
        private final Set<Key> targets = new LinkedHashSet<>();
        private final Set<String> filters = new LinkedHashSet<>();
        private final Set<UUID> actorUuids = new LinkedHashSet<>();
        private final Set<ActorKind> actorKinds = new LinkedHashSet<>();
        private final Set<Key> actorTypes = new LinkedHashSet<>();
        private final Set<Key> worlds = new LinkedHashSet<>();
        private final Set<Position> positions = new LinkedHashSet<>();
        private final Set<Around> around = new LinkedHashSet<>();

        private Conditions freeze() {
            return new Conditions(
                this.users,
                this.actions,
                this.timeRanges,
                this.targets,
                this.filters,
                this.actorUuids,
                this.actorKinds,
                this.actorTypes,
                this.worlds,
                this.positions,
                this.around
            );
        }
    }
}
