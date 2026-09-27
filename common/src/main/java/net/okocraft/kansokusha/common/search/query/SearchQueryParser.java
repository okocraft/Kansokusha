package net.okocraft.kansokusha.common.search.query;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.search.query.SearchQuery.ActorKind;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Around;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Conditions;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Order;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Position;
import net.okocraft.kansokusha.common.search.query.SearchQuery.TimeRange;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parser for the platform-neutral modifiers following {@code /kansokusha search}.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class SearchQueryParser {

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([mhdw])");

    private SearchQueryParser() {
    }

    /**
     * Parses a raw modifier string. Single and double quoted values are supported.
     */
    public static SearchQuery parse(String input, Clock clock, ZoneId timezone) {
        Objects.requireNonNull(input, "input");
        return parse(tokenize(input), clock, timezone);
    }

    /**
     * Parses already-tokenized modifier arguments.
     */
    public static SearchQuery parse(List<String> arguments, Clock clock, ZoneId timezone) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(timezone, "timezone");

        var cursor = new Cursor(arguments);
        var conditions = new MutableConditions();
        var exclusions = new MutableConditions();
        var order = Order.NEWEST;
        var limit = OptionalInt.empty();
        var orderSet = false;
        var limitSet = false;
        var fromSet = false;
        var toSet = false;
        var relativeTimeSet = false;
        var from = Optional.<Instant>empty();
        var to = Optional.<Instant>empty();
        var now = clock.instant();

        while (cursor.hasNext()) {
            var modifier = cursor.next("modifier");
            switch (modifier) {
                case "user" -> conditions.users.add(nonEmpty(cursor.next("user"), "user"));
                case "action" -> conditions.actions.add(parseActionKey(cursor.next("action")));
                case "time" -> {
                    if (fromSet || toSet) {
                        throw error("time cannot be combined with from/to");
                    }
                    relativeTimeSet = true;
                    conditions.timeRanges.add(parseRelativeTime(cursor.next("time"), now, timezone));
                }
                case "radius" -> conditions.radii.add(parsePositiveInt(cursor.next("radius"), "radius"));
                case "include", "target" ->
                    conditions.targets.add(parseKey(cursor.next(modifier), modifier));
                case "filter" -> conditions.filters.add(nonEmpty(cursor.next("filter"), "filter"));
                case "actor-uuid" -> conditions.actorUuids.add(parseUuid(cursor.next("actor-uuid")));
                case "actor-kind" -> conditions.actorKinds.add(parseActorKind(cursor.next("actor-kind")));
                case "actor-type" ->
                    conditions.actorTypes.add(parseKey(cursor.next("actor-type"), "actor-type"));
                case "world" -> conditions.worlds.add(parseKey(cursor.next("world"), "world"));
                case "position" -> conditions.positions.add(parsePosition(cursor));
                case "around" -> conditions.around.add(parseAround(cursor));
                case "from" -> {
                    if (relativeTimeSet) {
                        throw error("from/to cannot be combined with time");
                    }
                    if (fromSet) {
                        throw error("from may only be specified once");
                    }
                    fromSet = true;
                    from = Optional.of(parseAbsoluteBound(cursor.next("from"), timezone, false));
                }
                case "to" -> {
                    if (relativeTimeSet) {
                        throw error("from/to cannot be combined with time");
                    }
                    if (toSet) {
                        throw error("to may only be specified once");
                    }
                    toSet = true;
                    to = Optional.of(parseAbsoluteBound(cursor.next("to"), timezone, true));
                }
                case "exclude" -> parseExcluded(cursor, exclusions, now, timezone);
                case "order" -> {
                    if (orderSet) {
                        throw error("order may only be specified once");
                    }
                    orderSet = true;
                    order = parseOrder(cursor.next("order"));
                }
                case "limit" -> {
                    if (limitSet) {
                        throw error("limit may only be specified once");
                    }
                    limitSet = true;
                    limit = OptionalInt.of(parsePositiveInt(cursor.next("limit"), "limit"));
                }
                default -> throw error("unknown modifier: " + modifier);
            }
        }

        if (fromSet || toSet) {
            try {
                conditions.timeRanges.add(new TimeRange(from, to));
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage(), e);
            }
        }

        try {
            return new SearchQuery(conditions.freeze(), exclusions.freeze(), order, limit);
        } catch (IllegalArgumentException e) {
            throw error(e.getMessage(), e);
        }
    }

    private static void parseExcluded(
        Cursor cursor,
        MutableConditions exclusions,
        Instant now,
        ZoneId timezone
    ) {
        var condition = cursor.next("exclude condition");
        switch (condition) {
            case "user" -> exclusions.users.add(nonEmpty(cursor.next("exclude user"), "exclude user"));
            case "action" -> exclusions.actions.add(parseActionKey(cursor.next("exclude action")));
            case "time" ->
                exclusions.timeRanges.add(parseRelativeTime(cursor.next("exclude time"), now, timezone));
            case "radius" ->
                exclusions.radii.add(parsePositiveInt(cursor.next("exclude radius"), "exclude radius"));
            case "include", "target" ->
                exclusions.targets.add(parseKey(cursor.next("exclude " + condition), "exclude " + condition));
            case "filter" ->
                exclusions.filters.add(nonEmpty(cursor.next("exclude filter"), "exclude filter"));
            case "actor-uuid" ->
                exclusions.actorUuids.add(parseUuid(cursor.next("exclude actor-uuid")));
            case "actor-kind" ->
                exclusions.actorKinds.add(parseActorKind(cursor.next("exclude actor-kind")));
            case "actor-type" ->
                exclusions.actorTypes.add(parseKey(cursor.next("exclude actor-type"), "exclude actor-type"));
            case "world" ->
                exclusions.worlds.add(parseKey(cursor.next("exclude world"), "exclude world"));
            case "position" -> exclusions.positions.add(parsePosition(cursor));
            case "around" -> exclusions.around.add(parseAround(cursor));
            default -> throw error("unsupported exclude condition: " + condition);
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
            default -> throw error("order must be newest or oldest: " + value);
        };
    }

    private static ActorKind parseActorKind(String value) {
        return switch (value) {
            case "player" -> ActorKind.PLAYER;
            case "entity" -> ActorKind.ENTITY;
            case "block" -> ActorKind.BLOCK;
            default -> throw error("actor-kind must be player, entity, or block: " + value);
        };
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw error("invalid actor UUID: " + value, e);
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
            throw error("invalid " + field + " key: " + value, e);
        }
    }

    private static int parsePositiveInt(String value, String field) {
        var parsed = parseInt(value, field);
        if (parsed <= 0) {
            throw error(field + " must be greater than zero: " + value);
        }
        return parsed;
    }

    private static int parseInt(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw error("invalid " + field + ": " + value, e);
        }
    }

    private static String nonEmpty(String value, String field) {
        if (value.isEmpty()) {
            throw error(field + " must not be empty");
        }
        return value;
    }

    private static TimeRange parseRelativeTime(String value, Instant now, ZoneId timezone) {
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
                throw error("relative time is out of range: " + value, e);
            }
        }
        if (separator == 0 || separator == value.length() - 1 || separator != value.lastIndexOf('-')) {
            throw error("invalid relative time range: " + value);
        }

        var first = parseDuration(value.substring(0, separator));
        var second = parseDuration(value.substring(separator + 1));
        if (first.equals(second)) {
            throw error("relative time range must span a non-zero interval: " + value);
        }

        var older = first.compareTo(second) > 0 ? first : second;
        var newer = first.compareTo(second) > 0 ? second : first;
        try {
            return TimeRange.bounded(now.minus(older), now.minus(newer));
        } catch (DateTimeException | ArithmeticException e) {
            throw error("relative time range is out of range: " + value, e);
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
                    throw error("invalid relative duration: " + value);
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
            throw error("relative duration is out of range: " + value, e);
        }

        if (!found || position != value.length() || duration.isZero()) {
            throw error("invalid relative duration: " + value);
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
            throw error("absolute date is out of range: " + value, e);
        }

        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try local date-time below.
        }

        try {
            return LocalDateTime.parse(value).atZone(timezone).toInstant();
        } catch (DateTimeParseException e) {
            throw error("invalid date or datetime: " + value, e);
        } catch (DateTimeException e) {
            throw error("datetime is out of range: " + value, e);
        }
    }

    static List<String> tokenize(String input) {
        var tokens = new ArrayList<String>();
        var token = new StringBuilder();
        var tokenStarted = false;
        var quote = '\0';
        var escaping = false;

        for (var index = 0; index < input.length(); index++) {
            var character = input.charAt(index);
            if (escaping) {
                token.append(character);
                tokenStarted = true;
                escaping = false;
                continue;
            }
            if (character == '\\') {
                escaping = true;
                tokenStarted = true;
                continue;
            }
            if (quote != '\0') {
                if (character == quote) {
                    quote = '\0';
                } else {
                    token.append(character);
                }
                tokenStarted = true;
                continue;
            }
            if (character == '"' || character == '\'') {
                quote = character;
                tokenStarted = true;
                continue;
            }
            if (Character.isWhitespace(character)) {
                if (tokenStarted) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    tokenStarted = false;
                }
                continue;
            }
            token.append(character);
            tokenStarted = true;
        }

        if (escaping) {
            throw error("query ends with an incomplete escape");
        }
        if (quote != '\0') {
            throw error("query contains an unterminated quote");
        }
        if (tokenStarted) {
            tokens.add(token.toString());
        }
        return List.copyOf(tokens);
    }

    private static SearchQueryParseException error(String message) {
        return new SearchQueryParseException(message);
    }

    private static SearchQueryParseException error(String message, Throwable cause) {
        return new SearchQueryParseException(message, cause);
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
                throw error("missing " + expected);
            }
            return this.arguments.get(this.index++);
        }
    }

    private static final class MutableConditions {

        private final Set<String> users = new LinkedHashSet<>();
        private final Set<Key> actions = new LinkedHashSet<>();
        private final Set<TimeRange> timeRanges = new LinkedHashSet<>();
        private final Set<Integer> radii = new LinkedHashSet<>();
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
                this.radii,
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
