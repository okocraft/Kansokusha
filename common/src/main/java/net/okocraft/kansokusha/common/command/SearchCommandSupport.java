package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.common.search.query.SearchQueryParseException;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

@ApiStatus.Internal
@NotNullByDefault
public final class SearchCommandSupport {

    public static final String PERMISSION = "kansokusha.command.search";
    public static final String EVENT_PERMISSION_PREFIX = "kansokusha.command.search.event.";

    public static final int PLAYER_DEFAULT_LIMIT = 10;
    public static final int PLAYER_MAX_LIMIT = 50;
    public static final int NON_PLAYER_DEFAULT_LIMIT = 50;
    public static final int NON_PLAYER_MAX_LIMIT = SearchRequest.MAX_LIMIT;

    private static final String CURSOR_PREFIX = "__cursor=";

    public static Invocation parseInvocation(String rawInput, Clock clock, ZoneId timezone) {
        Objects.requireNonNull(rawInput, "rawInput");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(timezone, "timezone");

        var input = rawInput.strip();
        if (input.isEmpty()) {
            return new Invocation("", SearchQueryParser.parse("", clock, timezone), Optional.empty());
        }

        var lastToken = SearchQueryParser.trailingToken(input).orElseThrow();
        if (lastToken.quotedOrEscaped() || !lastToken.value().startsWith(CURSOR_PREFIX)) {
            return new Invocation(
                input,
                SearchQueryParser.parse(input, clock, timezone),
                Optional.empty()
            );
        }

        var queryText = input.substring(0, lastToken.start()).stripTrailing();
        return new Invocation(
            queryText,
            SearchQueryParser.parse(queryText, clock, timezone),
            Optional.of(parseCursor(lastToken.value().substring(CURSOR_PREFIX.length())))
        );
    }

    public static int defaultLimit(boolean playerSource) {
        return playerSource ? PLAYER_DEFAULT_LIMIT : NON_PLAYER_DEFAULT_LIMIT;
    }

    public static int maxLimit(boolean playerSource) {
        return playerSource ? PLAYER_MAX_LIMIT : NON_PLAYER_MAX_LIMIT;
    }

    public static boolean hasRadius(SearchQuery query) {
        Objects.requireNonNull(query, "query");
        return !query.conditions().radii().isEmpty() || !query.exclusions().radii().isEmpty();
    }

    public static Set<Key> explicitActions(SearchQuery query) {
        Objects.requireNonNull(query, "query");
        var actions = new LinkedHashSet<Key>();
        actions.addAll(query.conditions().actions());
        actions.addAll(query.exclusions().actions());
        return Set.copyOf(actions);
    }

    public static String eventPermission(Key eventType) {
        Objects.requireNonNull(eventType, "eventType");
        return EVENT_PERMISSION_PREFIX + eventType.asString();
    }

    public static Set<Key> allowedEventTypes(
        Predicate<String> hasPermission,
        SearchMetadata metadata
    ) {
        Objects.requireNonNull(hasPermission, "hasPermission");
        Objects.requireNonNull(metadata, "metadata");

        var allowed = new LinkedHashSet<Key>();
        for (var eventType : metadata.eventTypes()) {
            if (hasPermission.test(eventPermission(eventType))) {
                allowed.add(eventType);
            }
        }
        return Set.copyOf(allowed);
    }

    public static SearchMetadata visibleMetadata(
        Predicate<String> hasPermission,
        SearchMetadata metadata
    ) {
        return metadata.retainEventTypes(allowedEventTypes(hasPermission, metadata));
    }

    public static List<String> dynamicSuggestions(
        SearchQueryParser.CompletionKind kind,
        String prefix,
        SearchMetadata metadata
    ) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(metadata, "metadata");

        var values = switch (kind) {
            case ACTION -> metadata.eventTypes().stream()
                .map(type -> completionEventType(type, prefix))
                .toList();
            case WORLD -> metadata.worlds().stream().map(Key::asString).toList();
            case ACTOR_TYPE -> metadata.actorTypes().stream().map(Key::asString).toList();
            case TARGET -> metadata.targetTypes().stream().map(Key::asString).toList();
            default -> List.<String>of();
        };

        return values.stream()
            .distinct()
            .filter(value -> startsWithIgnoreCase(value, prefix))
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    public static String displayEventType(Key eventType) {
        Objects.requireNonNull(eventType, "eventType");
        return "kansokusha".equals(eventType.namespace())
            ? eventType.value()
            : eventType.asString();
    }

    public static boolean startsWithIgnoreCase(String value, String prefix) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(prefix, "prefix");
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static Component formatEvent(SearchPage.Event event) {
        Objects.requireNonNull(event, "event");
        var parts = new ArrayList<Component>();
        parts.add(Component.text(event.occurredAt().toString()));
        parts.add(Component.text(displayEventType(event.eventType())));

        var actor = formatActor(event);
        var target = event.targetType().map(type -> Component.text(type.asString())).orElse(null);
        if (actor != null || target != null) {
            if (actor != null && target != null) {
                parts.add(actor.append(Component.text(" -> ")).append(target));
            } else if (actor != null) {
                parts.add(actor);
            } else {
                parts.add(Component.text("-> ").append(target));
            }
        }

        event.world().ifPresent(world -> {
            var location = new StringBuilder("@ ").append(world.asString());
            if (event.x().isPresent() && event.y().isPresent() && event.z().isPresent()) {
                location.append(' ')
                    .append(event.x().getAsInt())
                    .append(' ')
                    .append(event.y().getAsInt())
                    .append(' ')
                    .append(event.z().getAsInt());
            }
            parts.add(Component.text(location.toString()));
        });

        event.searchText()
            .map(SearchCommandSupport::singleLine)
            .filter(text -> !text.isEmpty())
            .ifPresent(text -> parts.add(Component.text('"' + text + '"')));

        var result = Component.empty();
        for (var index = 0; index < parts.size(); index++) {
            if (index > 0) {
                result = result.append(Component.text(" | "));
            }
            result = result.append(parts.get(index));
        }
        return result;
    }

    public static @Nullable Component paginationComponent(String query, SearchPage page) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(page, "page");

        Component result = Component.empty();
        var present = false;

        if (page.previousCursor().isPresent()) {
            result = result.append(
                SearchCommandMessages.PREVIOUS.asComponent()
                    .clickEvent(ClickEvent.runCommand(pageCommand(query, page.previousCursor().get())))
            );
            present = true;
        }

        if (page.nextCursor().isPresent()) {
            if (present) {
                result = result.append(Component.text(" | "));
            }
            result = result.append(
                SearchCommandMessages.NEXT.asComponent()
                    .clickEvent(ClickEvent.runCommand(pageCommand(query, page.nextCursor().get())))
            );
            present = true;
        }

        return present ? result : null;
    }

    private static @Nullable Component formatActor(SearchPage.Event event) {
        if (event.actorKind().isEmpty()) {
            return null;
        }

        return switch (event.actorKind().get()) {
            case PLAYER -> {
                var uuid = event.actorUuid().map(Object::toString).orElse("");
                if (event.actorName().isPresent()) {
                    var name = Component.text(event.actorName().get());
                    yield uuid.isEmpty()
                        ? name
                        : name.hoverEvent(HoverEvent.showText(Component.text(uuid)));
                }
                yield uuid.isEmpty() ? null : Component.text(uuid);
            }
            case ENTITY -> {
                var type = event.actorType().map(Key::asString).orElse("");
                var uuid = event.actorUuid().map(Object::toString).orElse("");
                if (type.isEmpty()) {
                    yield uuid.isEmpty() ? null : Component.text(uuid);
                }
                var component = Component.text(type);
                yield uuid.isEmpty()
                    ? component
                    : component.hoverEvent(HoverEvent.showText(Component.text(uuid)));
            }
            case BLOCK -> event.actorType()
                .map(type -> Component.text(type.asString()))
                .orElse(null);
        };
    }

    private static String completionEventType(Key type, String prefix) {
        if ("kansokusha".equals(type.namespace()) && prefix.indexOf(':') < 0) {
            return type.value();
        }
        return type.asString();
    }

    private static String singleLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    private static String pageCommand(String query, SearchRequest.Cursor cursor) {
        var command = new StringBuilder("/kansokusha search");
        if (!query.isBlank()) {
            command.append(' ').append(query.strip());
        }
        command.append(' ').append(CURSOR_PREFIX).append(serializeCursor(cursor));
        return command.toString();
    }

    private static String serializeCursor(SearchRequest.Cursor cursor) {
        return cursor.direction().name().toLowerCase(Locale.ROOT)
            + ","
            + cursor.occurredAt().toEpochMilli()
            + ","
            + cursor.eventId();
    }

    private static SearchRequest.Cursor parseCursor(String value) {
        var parts = value.split(",", 3);
        if (parts.length != 3) {
            throw new SearchQueryParseException("invalid search cursor");
        }

        try {
            var direction = switch (parts[0]) {
                case "next" -> SearchRequest.Direction.NEXT;
                case "previous" -> SearchRequest.Direction.PREVIOUS;
                default -> throw new IllegalArgumentException();
            };
            return new SearchRequest.Cursor(
                Instant.ofEpochMilli(Long.parseLong(parts[1])),
                java.util.UUID.fromString(parts[2]),
                direction
            );
        } catch (RuntimeException e) {
            throw new SearchQueryParseException("invalid search cursor", e);
        }
    }

    public record Invocation(
        String queryText,
        SearchQuery query,
        Optional<SearchRequest.Cursor> cursor
    ) {

        public Invocation {
            Objects.requireNonNull(queryText, "queryText");
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(cursor, "cursor");
        }
    }

    private SearchCommandSupport() {
        throw new UnsupportedOperationException();
    }
}
