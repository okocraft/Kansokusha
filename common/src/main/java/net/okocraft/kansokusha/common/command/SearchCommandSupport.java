package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.common.search.query.ActionCategories;
import net.okocraft.kansokusha.common.search.query.SearchQueryMessages;
import net.okocraft.kansokusha.common.search.query.SearchQueryParseException;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
        return execute(backend, source, rawInput, clock, timezone, EventRenderer.generic(clock, timezone));
    }

    public static boolean execute(
        EventSearchBackend backend,
        SearchSource source,
        String rawInput,
        Clock clock,
        ZoneId timezone,
        EventRenderer renderer
    ) {
        final Invocation invocation;
        try {
            invocation = parseInvocation(rawInput, clock, timezone, source.origin(), source.hasPermission());
        } catch (SearchQueryParseException e) {
            source.sendMessage().accept(SearchCommandMessages.PARSE_ERROR.apply(e.reason()));
            return false;
        }

        var query = invocation.query();
        var maxLimit = source.player() ? PLAYER_MAX_LIMIT : NON_PLAYER_MAX_LIMIT;
        if (query.limit().orElse(0) > maxLimit) {
            source.sendMessage().accept(SearchCommandMessages.LIMIT_RANGE.apply(maxLimit));
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
            .thenCompose(metadata -> {
                var request = new SearchRequest(query,
                    new SearchRequest.Constraints(allowedEventTypes(source.hasPermission(), metadata)),
                    invocation.cursor(), defaultLimit);
                return backend.search(request).thenCompose(page -> query.count()
                    ? backend.count(request).thenApply(total -> new CountedPage(page, java.util.OptionalLong.of(total), request))
                    : CompletableFuture.completedFuture(new CountedPage(page, java.util.OptionalLong.empty(), request)));
            })
            .whenComplete((result, failure) -> {
                if (failure != null) {
                    source.sendMessage().accept(SearchCommandMessages.SEARCH_FAILED.asComponent());
                    return;
                }
                var total = result.request().constraints().allowedEventTypes().stream()
                    .allMatch(type -> source.hasPermission().test(eventPermission(type))) ? result.total() : java.util.OptionalLong.empty();
                renderPage(source.sendMessage(), invocation.queryText(), result.page(), eventDetailsPermitted, renderer,
                    source.hasPermission(), SearchDisplayScope.of(query), source.player(), total, invocation.page(), result.request().limit());
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
        return parseInvocation(rawInput, clock, timezone, origin, permission -> true);
    }

    static Invocation parseInvocation(String rawInput, Clock clock, ZoneId timezone,
                                      SearchQuery.@Nullable Position origin, Predicate<String> permission) {
        java.util.function.Function<String, Set<Key>> categories = name -> {
            var members = ActionCategories.events(name);
            if (members.isEmpty()) {
                return members;
            }
            var visible = members.stream().filter(type -> permission.test(eventPermission(type)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (visible.isEmpty()) {
                throw new SearchQueryParseException("No permitted events in category " + name,
                    SearchCommandMessages.EVENT_PERMISSION.apply(name));
            }
            return visible;
        };
        var input = rawInput.strip();
        var lastToken = SearchQueryParser.trailingToken(input).orElse(null);
        if (lastToken == null || lastToken.quotedOrEscaped() || !lastToken.value().startsWith(CURSOR_PREFIX)) {
            return new Invocation(input, SearchQueryParser.parse(input, clock, timezone, origin, categories), Optional.empty(), 1);
        }

        var queryText = input.substring(0, lastToken.start()).stripTrailing();
        return new Invocation(
            queryText,
            SearchQueryParser.parse(queryText, clock, timezone, origin, categories),
            Optional.of(parseCursor(lastToken.value().substring(CURSOR_PREFIX.length()))),
            cursorPage(lastToken.value().substring(CURSOR_PREFIX.length()))
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
        var literals = values.sorted().toList();
        if (kind != SearchQueryParser.CompletionKind.ACTION) {
            return literals;
        }
        return Stream.concat(ActionCategories.names().stream()
            .filter(name -> ActionCategories.events(name).stream().anyMatch(metadata.eventTypes()::contains)), literals.stream())
            .distinct().toList();
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
        return EventRenderer.generic(Clock.systemUTC(), java.time.ZoneOffset.UTC).row(event, eventDetailsPermitted, true);
    }

    private static void renderPage(
        Consumer<Component> sendMessage,
        String query,
        SearchPage page,
        boolean eventDetailsPermitted,
        EventRenderer renderer,
        Predicate<String> hasPermission,
        SearchDisplayScope scope,
        boolean interactive,
        java.util.OptionalLong total,
        int currentPage,
        int pageSize
    ) {
        if (!page.events().isEmpty() && page.events().stream().noneMatch(event -> hasPermission.test(eventPermission(event.eventType())))) {
            sendMessage.accept(SearchCommandMessages.NO_RESULTS.asComponent());
            return;
        }
        scope.header().ifPresent(header -> sendMessage.accept(EventPresentationMessages.SCOPE.apply(header)));
        if (!interactive && !page.events().isEmpty()) {
            sendMessage.accept(EventPresentationMessages.CONSOLE_HINT.asComponent());
        }
        if (page.events().isEmpty()) {
            sendMessage.accept(SearchCommandMessages.NO_RESULTS.asComponent());
        }
        for (var event : page.events()) {
            if (!hasPermission.test(eventPermission(event.eventType()))) {
                continue;
            }
            var line = renderer.row(event, eventDetailsPermitted && hasPermission.test(EventCommandSupport.PERMISSION), scope.position().isEmpty(), scope.world().isEmpty());
            if (!interactive) {
                line = line.append(Component.text(" [" + event.eventId() + "]"));
            }
            sendMessage.accept(SearchCommandMessages.RESULT.apply(line));
        }

        var pagination = paginationComponent(query, page, total, currentPage, pageSize);
        if (pagination != null) {
            sendMessage.accept(pagination);
        }
    }

    static @Nullable Component paginationComponent(String query, SearchPage page) {
        return paginationComponent(query, page, java.util.OptionalLong.empty(), 1, 10);
    }

    static @Nullable Component paginationComponent(String query, SearchPage page, java.util.OptionalLong total,
                                                    int currentPage, int pageSize) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(page, "page");

        Component result = Component.empty();
        var present = false;

        if (page.previousCursor().isPresent()) {
            result = result.append(
                SearchCommandMessages.PREVIOUS.asComponent()
                    .clickEvent(ClickEvent.runCommand(pageCommand(query, page.previousCursor().get(), total.isPresent() ? Math.max(1, currentPage - 1) : 0)))
            );
            present = true;
        }

        if (page.nextCursor().isPresent()) {
            if (present) {
                result = result.append(Component.text(" | "));
            }
            result = result.append(
                SearchCommandMessages.NEXT.asComponent()
                    .clickEvent(ClickEvent.runCommand(pageCommand(query, page.nextCursor().get(), total.isPresent() ? Math.addExact(currentPage, 1) : 0)))
            );
            present = true;
        }

        if (total.isPresent()) {
            if (present) result = result.append(Component.space());
            var pages = Math.max(1L, (total.getAsLong() + pageSize - 1) / pageSize);
            result = result.append(SearchCommandMessages.COUNT.apply(total.getAsLong(), currentPage, pages));
            present = true;
        }
        return present ? result : null;
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

    private static String pageCommand(String query, SearchRequest.Cursor cursor, int page) {
        var command = new StringBuilder("/kansokusha search");
        if (!query.isBlank()) {
            command.append(' ').append(query.strip());
        }
        command.append(' ').append(CURSOR_PREFIX).append(serializeCursor(cursor));
        if (page > 0) command.append(',').append(page);
        return command.toString();
    }

    public static String serializeCursor(SearchRequest.Cursor cursor) {
        return cursor.direction().name().toLowerCase(Locale.ROOT)
            + ","
            + cursor.occurredAt().toEpochMilli()
            + ","
            + cursor.eventId();
    }

    public static SearchRequest.Cursor parseCursor(String value) {
        var parts = value.split(",", -1);
        if (parts.length != 3 && parts.length != 4) {
            throw new SearchQueryParseException("invalid search cursor", SearchQueryMessages.INVALID_CURSOR.asComponent());
        }

        try {
            cursorPage(value);
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
            throw new SearchQueryParseException("invalid search cursor", SearchQueryMessages.INVALID_CURSOR.asComponent(), e);
        }
    }

    private static int cursorPage(String value) {
        var parts = value.split(",", -1);
        if (parts.length == 3) return 1;
        try {
            if (parts.length != 4) throw new IllegalArgumentException();
            var page = Integer.parseInt(parts[3]);
            if (page < 1 || page == Integer.MAX_VALUE) throw new IllegalArgumentException();
            return page;
        } catch (IllegalArgumentException e) {
            throw new SearchQueryParseException("invalid search cursor page", SearchQueryMessages.INVALID_CURSOR.asComponent(), e);
        }
    }

    private record CountedPage(SearchPage page, java.util.OptionalLong total, SearchRequest request) { }

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
        Optional<SearchRequest.Cursor> cursor,
        int page
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
