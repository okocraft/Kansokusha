package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

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

    /**
     * Parses, authorizes and runs {@code /kansokusha search}, sending the page asynchronously.
     *
     * @return whether the search was started
     */
    public static boolean execute(
        EventSearchBackend backend,
        SearchSource source,
        String rawInput,
        Clock clock,
        ZoneId timezone
    ) {
        final Invocation invocation;
        try {
            invocation = parseInvocation(rawInput, clock, timezone, source.origin());
        } catch (SearchQueryParseException e) {
            source.sendMessage().accept(SearchCommandMessages.PARSE_ERROR.apply(e.getMessage()));
            return false;
        }

        var query = invocation.query();
        var maxLimit = source.player() ? PLAYER_MAX_LIMIT : NON_PLAYER_MAX_LIMIT;
        if (query.limit().orElse(0) > maxLimit) {
            source.sendMessage().accept(SearchCommandMessages.LIMIT_RANGE.apply(Integer.toString(maxLimit)));
            return false;
        }

        for (var eventType : explicitActions(query)) {
            if (!source.hasPermission().test(eventPermission(eventType))) {
                source.sendMessage().accept(SearchCommandMessages.EVENT_PERMISSION.apply(displayEventType(eventType)));
                return false;
            }
        }

        var defaultLimit = source.player() ? PLAYER_DEFAULT_LIMIT : NON_PLAYER_DEFAULT_LIMIT;
        var eventDetailsPermitted = source.hasPermission().test(EventCommandSupport.PERMISSION);
        backend.searchMetadata()
            .thenCompose(metadata -> backend.search(new SearchRequest(
                query,
                new SearchRequest.Constraints(allowedEventTypes(source.hasPermission(), metadata)),
                invocation.cursor(),
                defaultLimit
            )))
            .whenComplete((page, failure) -> {
                if (failure != null) {
                    source.sendMessage().accept(SearchCommandMessages.SEARCH_FAILED.asComponent());
                    return;
                }
                renderPage(source.sendMessage(), invocation.queryText(), page, eventDetailsPermitted);
            });
        return true;
    }

    /**
     * Completes the argument of {@code /kansokusha search}.
     *
     * @param radiusAvailable whether the sender has a position that {@code radius} can use
     */
    public static CompletableFuture<Suggestions> suggest(
        EventSearchBackend backend,
        Predicate<String> hasPermission,
        boolean radiusAvailable,
        String input,
        Clock clock,
        ZoneId timezone
    ) {
        var completion = SearchQueryParser.completion(input, clock, timezone, radiusAvailable);
        var prefix = completion.prefix();
        CompletableFuture<List<String>> values = switch (completion.kind()) {
            case USER -> backend.offlinePlayerNames();
            case ACTION, TARGET, ACTOR_TYPE, WORLD -> backend.searchMetadata().thenApply(metadata ->
                dynamicSuggestions(completion.kind(), prefix, visibleMetadata(hasPermission, metadata))
            );
            default -> CompletableFuture.completedFuture(completion.staticSuggestions());
        };
        return values
            .exceptionally(failure -> List.of())
            .thenApply(candidates -> new Suggestions(
                completion.replacementStart(),
                candidates.stream()
                    .filter(value -> startsWithIgnoreCase(value, prefix))
                    .distinct()
                    .toList()
            ));
    }

    static Invocation parseInvocation(
        String rawInput,
        Clock clock,
        ZoneId timezone,
        SearchQuery.@Nullable Position origin
    ) {
        var input = rawInput.strip();
        var lastToken = SearchQueryParser.trailingToken(input).orElse(null);
        if (lastToken == null || lastToken.quotedOrEscaped() || !lastToken.value().startsWith(CURSOR_PREFIX)) {
            return new Invocation(input, SearchQueryParser.parse(input, clock, timezone, origin), Optional.empty());
        }

        var queryText = input.substring(0, lastToken.start()).stripTrailing();
        return new Invocation(
            queryText,
            SearchQueryParser.parse(queryText, clock, timezone, origin),
            Optional.of(parseCursor(lastToken.value().substring(CURSOR_PREFIX.length())))
        );
    }

    static Set<Key> explicitActions(SearchQuery query) {
        var actions = new LinkedHashSet<Key>();
        actions.addAll(query.conditions().actions());
        actions.addAll(query.exclusions().actions());
        return actions;
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

    static SearchMetadata visibleMetadata(
        Predicate<String> hasPermission,
        SearchMetadata metadata
    ) {
        return metadata.retainEventTypes(allowedEventTypes(hasPermission, metadata));
    }

    static List<String> dynamicSuggestions(
        SearchQueryParser.CompletionKind kind,
        String prefix,
        SearchMetadata metadata
    ) {
        var values = switch (kind) {
            case ACTION -> metadata.eventTypes().stream().map(type -> completionEventType(type, prefix));
            case WORLD -> metadata.worlds().stream().map(Key::asString);
            case ACTOR_TYPE -> metadata.actorTypes().stream().map(Key::asString);
            case TARGET -> metadata.targetTypes().stream().map(Key::asString);
            default -> Stream.<String>empty();
        };
        return values.sorted().toList();
    }

    public static String displayEventType(Key eventType) {
        Objects.requireNonNull(eventType, "eventType");
        return "kansokusha".equals(eventType.namespace())
            ? eventType.value()
            : eventType.asString();
    }

    static boolean startsWithIgnoreCase(String value, String prefix) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(prefix, "prefix");
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static Component formatEvent(SearchPage.Event event, boolean eventDetailsPermitted) {
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
            event.position().ifPresent(position -> location.append(' ')
                .append(position.x())
                .append(' ')
                .append(position.y())
                .append(' ')
                .append(position.z()));
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
        result = result.hoverEvent(HoverEvent.showText(Component.text(event.eventId().toString())));
        return eventDetailsPermitted
            ? result.clickEvent(ClickEvent.runCommand("/kansokusha event " + event.eventId()))
            : result;
    }

    private static void renderPage(
        Consumer<Component> sendMessage,
        String query,
        SearchPage page,
        boolean eventDetailsPermitted
    ) {
        if (page.events().isEmpty()) {
            sendMessage.accept(SearchCommandMessages.NO_RESULTS.asComponent());
        }
        for (var event : page.events()) {
            sendMessage.accept(SearchCommandMessages.RESULT.apply(formatEvent(event, eventDetailsPermitted)));
        }

        var pagination = paginationComponent(query, page);
        if (pagination != null) {
            sendMessage.accept(pagination);
        }
    }

    static @Nullable Component paginationComponent(String query, SearchPage page) {
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
        return switch (event.actor().orElse(null)) {
            case null -> null;
            case PlayerActor player -> withUuidHover(
                Component.text(event.actorName().orElse(player.uniqueId().toString())),
                player.uniqueId()
            );
            case EntityActor entity -> withUuidHover(
                Component.text(entity.entityType().asString()),
                entity.uniqueId()
            );
            case BlockActor block -> Component.text(block.blockType().asString());
        };
    }

    static Component withUuidHover(Component component, UUID uuid) {
        return component.hoverEvent(HoverEvent.showText(Component.text(uuid.toString())));
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
                UUID.fromString(parts[2]),
                direction
            );
        } catch (RuntimeException e) {
            throw new SearchQueryParseException("invalid search cursor", e);
        }
    }

    /**
     * The sender of {@code /kansokusha search}.
     *
     * @param origin the sender's position used as the center of {@code radius}, if any
     */
    public record SearchSource(
        Predicate<String> hasPermission,
        Consumer<Component> sendMessage,
        boolean player,
        SearchQuery.@Nullable Position origin
    ) {
    }

    /**
     * Completion candidates replacing the input from {@code replacementStart}.
     */
    public record Suggestions(int replacementStart, List<String> values) {
    }

    record Invocation(
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
