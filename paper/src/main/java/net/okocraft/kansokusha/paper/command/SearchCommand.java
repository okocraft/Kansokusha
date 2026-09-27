package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.common.player.PlayerNameDirectory;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.common.search.query.SearchQueryParseException;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@NotNullByDefault
final class SearchCommand {

    static final String PERMISSION = "kansokusha.command.search";
    static final String EVENT_PERMISSION_PREFIX = "kansokusha.command.search.event.";

    private static final String CURSOR_PREFIX = "__cursor=";
    private static final int PLAYER_DEFAULT_LIMIT = 10;
    private static final int PLAYER_MAX_LIMIT = 50;
    private static final int NON_PLAYER_DEFAULT_LIMIT = 50;
    private static final int NON_PLAYER_MAX_LIMIT = SearchRequest.MAX_LIMIT;

    static LiteralArgumentBuilder<CommandSourceStack> createSearchCommand() {
        return createSearchCommand(Clock.systemDefaultZone(), ZoneId.systemDefault());
    }

    static LiteralArgumentBuilder<CommandSourceStack> createSearchCommand(
        Clock clock,
        ZoneId timezone
    ) {
        return Commands.literal("search")
            .requires(source -> source.getSender().hasPermission(PERMISSION))
            .executes(context -> execute(context.getSource(), "", clock, timezone))
            .then(Commands.argument("query", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggest(context, builder, clock, timezone))
                .executes(context -> execute(
                    context.getSource(),
                    StringArgumentType.getString(context, "query"),
                    clock,
                    timezone
                )));
    }

    private static int execute(
        CommandSourceStack source,
        String rawInput,
        Clock clock,
        ZoneId timezone
    ) {
        var sender = source.getSender();
        ParsedInvocation invocation;
        SearchQuery query;
        try {
            invocation = parseInvocation(rawInput);
            query = SearchQueryParser.parse(invocation.query(), clock, timezone);
        } catch (SearchQueryParseException | IllegalArgumentException e) {
            sender.sendMessage(SearchCommandMessages.PARSE_ERROR.apply(""));
            return 0;
        }

        var player = source.getExecutor() instanceof Player executor ? executor : null;
        var maxLimit = player == null ? NON_PLAYER_MAX_LIMIT : PLAYER_MAX_LIMIT;
        var defaultLimit = player == null ? NON_PLAYER_DEFAULT_LIMIT : PLAYER_DEFAULT_LIMIT;

        if (query.limit().isPresent() && query.limit().getAsInt() > maxLimit) {
            sender.sendMessage(SearchCommandMessages.LIMIT_RANGE.apply(Integer.toString(maxLimit)));
            return 0;
        }

        for (var eventType : explicitActions(query)) {
            if (!hasEventPermission(sender, eventType)) {
                sender.sendMessage(
                    SearchCommandMessages.EVENT_PERMISSION.apply(displayEventType(eventType))
                );
                return 0;
            }
        }

        Optional<SearchRequest.RadiusCenter> radiusCenter = Optional.empty();
        if (hasRadius(query)) {
            if (player == null) {
                sender.sendMessage(SearchCommandMessages.RADIUS_PLAYER_ONLY.apply(""));
                return 0;
            }
            var location = player.getLocation();
            var world = location.getWorld();
            radiusCenter = Optional.of(new SearchRequest.RadiusCenter(
                PaperKansokusha.key(world.getKey()),
                location.getBlockX(),
                location.getBlockZ()
            ));
        }

        final EventSearchBackend backend;
        try {
            backend = EventSearchBackend.require(Kansokusha.api());
        } catch (IllegalStateException | IllegalArgumentException e) {
            sender.sendMessage(SearchCommandMessages.SEARCH_FAILED.apply(""));
            return 0;
        }

        var center = radiusCenter;
        backend.searchMetadata()
            .thenCompose(metadata -> backend.search(new SearchRequest(
                query,
                new SearchRequest.Constraints(allowedEventTypes(sender, metadata)),
                center,
                invocation.cursor(),
                defaultLimit
            )))
            .whenComplete((page, failure) -> {
                if (failure != null) {
                    sender.sendMessage(SearchCommandMessages.SEARCH_FAILED.apply(""));
                    return;
                }
                renderPage(sender, invocation.query(), page);
            });

        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggest(
        CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder,
        Clock clock,
        ZoneId timezone
    ) {
        var completion = SearchQueryParser.completion(builder.getRemaining(), clock, timezone);
        if (completion.kind() == SearchQueryParser.CompletionKind.NONE) {
            return builder.buildFuture();
        }

        var target = builder.createOffset(builder.getStart() + completion.replacementStart());
        var source = context.getSource();
        var player = source.getExecutor() instanceof Player;

        if (!completion.staticSuggestions().isEmpty()) {
            completion.staticSuggestions().stream()
                .filter(value -> player || !"radius".equals(value))
                .filter(value -> startsWithIgnoreCase(value, completion.prefix()))
                .forEach(target::suggest);
            return target.buildFuture();
        }

        final var api = Kansokusha.api();
        if (
            completion.kind() == SearchQueryParser.CompletionKind.USER
                && api instanceof PlayerNameDirectory playerNames
        ) {
            return playerNames.offlinePlayerNames().handle((names, failure) -> {
                if (failure == null) {
                    names.stream()
                        .filter(value -> startsWithIgnoreCase(value, completion.prefix()))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .forEach(target::suggest);
                }
                return target.build();
            });
        }

        if (!(api instanceof EventSearchBackend backend)) {
            return target.buildFuture();
        }

        return backend.searchMetadata().handle((metadata, failure) -> {
            if (failure == null) {
                dynamicSuggestions(
                    source,
                    completion.kind(),
                    completion.prefix(),
                    metadata
                ).forEach(target::suggest);
            }
            return target.build();
        });
    }

    private static List<String> dynamicSuggestions(
        CommandSourceStack source,
        SearchQueryParser.CompletionKind kind,
        String prefix,
        SearchMetadata metadata
    ) {
        var values = switch (kind) {
            case ACTION -> metadata.eventTypes().stream()
                .filter(type -> hasEventPermission(source.getSender(), type))
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

    private static String completionEventType(Key type, String prefix) {
        if ("kansokusha".equals(type.namespace()) && prefix.indexOf(':') < 0) {
            return type.value();
        }
        return type.asString();
    }

    private static Set<Key> allowedEventTypes(
        org.bukkit.command.CommandSender sender,
        SearchMetadata metadata
    ) {
        var allowed = new LinkedHashSet<Key>();
        for (var eventType : metadata.eventTypes()) {
            if (hasEventPermission(sender, eventType)) {
                allowed.add(eventType);
            }
        }
        return Set.copyOf(allowed);
    }

    private static boolean hasEventPermission(
        org.bukkit.command.CommandSender sender,
        Key eventType
    ) {
        return sender.hasPermission(EVENT_PERMISSION_PREFIX + eventType.asString());
    }

    private static Set<Key> explicitActions(SearchQuery query) {
        var actions = new LinkedHashSet<Key>();
        actions.addAll(query.conditions().actions());
        actions.addAll(query.exclusions().actions());
        return Set.copyOf(actions);
    }

    private static boolean hasRadius(SearchQuery query) {
        return !query.conditions().radii().isEmpty() || !query.exclusions().radii().isEmpty();
    }

    private static void renderPage(
        org.bukkit.command.CommandSender sender,
        String query,
        SearchPage page
    ) {
        if (page.events().isEmpty()) {
            sender.sendMessage(SearchCommandMessages.NO_RESULTS.apply(""));
        } else {
            for (var event : page.events()) {
                sender.sendMessage(SearchCommandMessages.RESULT.apply(formatEvent(event)));
            }
        }

        var pagination = paginationComponent(query, page);
        if (pagination != null) {
            sender.sendMessage(pagination);
        }
    }

    static String formatEvent(SearchPage.Event event) {
        var parts = new ArrayList<String>();
        parts.add(event.occurredAt().toString());
        parts.add(displayEventType(event.eventType()));

        var actor = formatActor(event);
        var target = event.targetType().map(Key::asString).orElse("");
        if (!actor.isEmpty() || !target.isEmpty()) {
            if (!actor.isEmpty() && !target.isEmpty()) {
                parts.add(actor + " -> " + target);
            } else if (!actor.isEmpty()) {
                parts.add(actor);
            } else {
                parts.add("-> " + target);
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
            parts.add(location.toString());
        });

        event.searchText()
            .map(SearchCommand::singleLine)
            .filter(text -> !text.isEmpty())
            .ifPresent(text -> parts.add('"' + text + '"'));

        return String.join(" | ", parts);
    }

    private static String formatActor(SearchPage.Event event) {
        if (event.actorKind().isEmpty()) {
            return "";
        }

        return switch (event.actorKind().get()) {
            case PLAYER -> {
                var uuid = event.actorUuid().map(Object::toString).orElse("");
                yield event.actorName()
                    .map(name -> uuid.isEmpty() ? name : name + " (" + uuid + ")")
                    .orElse(uuid);
            }
            case ENTITY -> {
                var type = event.actorType().map(Key::asString).orElse("");
                var uuid = event.actorUuid().map(Object::toString).orElse("");
                if (type.isEmpty()) {
                    yield uuid;
                }
                yield uuid.isEmpty() ? type : type + " (" + uuid + ")";
            }
            case BLOCK -> event.actorType().map(Key::asString).orElse("");
        };
    }

    private static String singleLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    static Component paginationComponent(String query, SearchPage page) {
        Component result = Component.empty();
        var present = false;

        if (page.previousCursor().isPresent()) {
            result = result.append(
                SearchCommandMessages.PREVIOUS.apply("")
                    .clickEvent(ClickEvent.runCommand(pageCommand(query, page.previousCursor().get())))
            );
            present = true;
        }

        if (page.nextCursor().isPresent()) {
            if (present) {
                result = result.append(Component.text(" | "));
            }
            result = result.append(
                SearchCommandMessages.NEXT.apply("")
                    .clickEvent(ClickEvent.runCommand(pageCommand(query, page.nextCursor().get())))
            );
            present = true;
        }

        return present ? result : null;
    }

    private static String pageCommand(String query, SearchRequest.Cursor cursor) {
        var command = new StringBuilder("/kansokusha search");
        if (!query.isBlank()) {
            command.append(' ').append(query.strip());
        }
        command.append(' ').append(CURSOR_PREFIX).append(serializeCursor(cursor));
        return command.toString();
    }

    private static ParsedInvocation parseInvocation(String rawInput) {
        var input = rawInput.strip();
        if (input.isEmpty()) {
            return new ParsedInvocation("", Optional.empty());
        }

        var lastSpace = input.lastIndexOf(' ');
        var lastToken = lastSpace < 0 ? input : input.substring(lastSpace + 1);
        if (!lastToken.startsWith(CURSOR_PREFIX)) {
            return new ParsedInvocation(input, Optional.empty());
        }

        var query = lastSpace < 0 ? "" : input.substring(0, lastSpace).stripTrailing();
        return new ParsedInvocation(
            query,
            Optional.of(parseCursor(lastToken.substring(CURSOR_PREFIX.length())))
        );
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

    private static String displayEventType(Key eventType) {
        return "kansokusha".equals(eventType.namespace())
            ? eventType.value()
            : eventType.asString();
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private record ParsedInvocation(
        String query,
        Optional<SearchRequest.Cursor> cursor
    ) {
    }

    private SearchCommand() {
        throw new UnsupportedOperationException();
    }
}
