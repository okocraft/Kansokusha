package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.SearchPage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.Optional;
import java.util.function.BiFunction;

/** Shared search/inspection/detail composition, independent of storage and platform codecs. */
@ApiStatus.Internal
@NotNullByDefault
public final class EventRenderer {

    public enum NameKind { BLOCK, ITEM, ENTITY, OTHER }

    private final Clock clock;
    private final ZoneId timezone;
    private final EventPresenter presenter;
    private final BiFunction<Key, NameKind, Component> names;

    public EventRenderer(Clock clock, ZoneId timezone, EventPresenter presenter,
                         BiFunction<Key, NameKind, Component> names) {
        this.clock = clock;
        this.timezone = timezone;
        this.presenter = presenter;
        this.names = names;
    }

    public static EventRenderer generic(Clock clock, ZoneId timezone) {
        return new EventRenderer(clock, timezone, (event, detailed, renderer) -> Optional.empty(),
            (key, kind) -> Component.text(key.asString()));
    }

    public Component name(Key key, NameKind kind) {
        return this.names.apply(key, kind);
    }

    public Component actor(SearchPage.Event event) {
        return switch (event.actor().orElse(null)) {
            case null -> Component.empty();
            case PlayerActor player -> SearchCommandSupport.withUuidHover(
                Component.text(event.actorName().orElse(player.uniqueId().toString())), player.uniqueId());
            case EntityActor entity -> SearchCommandSupport.withUuidHover(
                this.name(entity.entityType(), NameKind.ENTITY), entity.uniqueId());
            case BlockActor block -> this.name(block.blockType(), NameKind.BLOCK);
        };
    }

    public Component row(SearchPage.Event event, boolean detailsPermitted, boolean location) {
        return this.row(event, detailsPermitted, location, true);
    }

    public Component row(SearchPage.Event event, boolean detailsPermitted, boolean location, boolean world) {
        var content = this.content(event, false);
        var result = EventPresentationMessages.ROW.apply(
            EventTime.relative(event.occurredAt(), this.clock.instant(), this.timezone), content.summary());
        if (location) {
            result = result.append(Component.space()).append(location(event, world));
        }
        var hover = EventFieldMessages.field("location", location(event, true))
            .append(Component.newline()).append(content.hover());
        if (detailsPermitted) {
            hover = hover.append(Component.newline()).append(EventPresentationMessages.HINT.asComponent());
        }
        result = result.hoverEvent(HoverEvent.showText(hover));
        return detailsPermitted ? result.clickEvent(ClickEvent.runCommand(command(event.eventId(), "", 1))) : result;
    }

    public List<Component> detail(EventDetail detail) {
        return this.view(detail, "", 1);
    }

    public List<Component> view(EventDetail detail, String view, int page) {
        var event = detail.event();
        var content = this.content(event, true);
        var metadata = EventCommandSupport.metadata(detail, this);
        if (!view.isEmpty()) {
            Supplier<List<Component>> lines = view.equals("record") ? () -> {
                var all = new ArrayList<>(EventCommandSupport.commonFields(detail, this));
                var raw = content.sections().get("raw");
                if (raw != null) {
                    all.addAll(raw.lines().get());
                } else {
                    all.addAll(content.details());
                }
                return all;
            } : content.sections().containsKey(view) ? content.sections().get(view).lines() : null;
            return lines == null ? List.of(EventPresentationMessages.INVALID_VIEW.asComponent())
                : section(event.eventId(), view, page, lines.get());
        }
        var result = new ArrayList<Component>();
        result.add(EventPresentationMessages.ROW.apply(
            EventTime.relative(event.occurredAt(), this.clock.instant(), this.timezone), content.summary()));
        result.addAll(content.details());
        if (event.world().isPresent() || event.server().isPresent() || event.position().isPresent()) {
            result.add(EventCommandMessages.DETAIL_LINE.apply(EventPresentationMessages.LOCATION.asComponent(), location(event, true)));
        }
        var actions = EventPresentationMessages.RECORD.asComponent().hoverEvent(HoverEvent.showText(join(metadata)))
            .clickEvent(ClickEvent.runCommand(command(event.eventId(), "record", 1)));
        for (var entry : content.sections().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (!entry.getKey().equals("raw")) {
                actions = actions.append(Component.space()).append(entry.getValue().label()
                    .clickEvent(ClickEvent.runCommand(command(event.eventId(), entry.getKey(), 1))));
            }
        }
        result.add(actions);
        return List.copyOf(result);
    }

    private static List<Component> section(UUID id, String view, int page, List<Component> lines) {
        var pages = Math.max(1, (lines.size() + 7) / 8);
        if (page < 1 || page > pages) {
            return List.of(EventPresentationMessages.INVALID_VIEW.asComponent());
        }
        var result = new ArrayList<Component>(lines.subList(Math.min((page - 1) * 8, lines.size()), Math.min(page * 8, lines.size())));
        var navigation = EventPresentationMessages.OVERVIEW.asComponent()
            .clickEvent(ClickEvent.runCommand(command(id, "", 1))).append(Component.space())
            .append(EventPresentationMessages.PAGE.apply(page, pages));
        if (page > 1) {
            navigation = navigation.append(Component.space()).append(SearchCommandMessages.PREVIOUS.asComponent()
                .clickEvent(ClickEvent.runCommand(command(id, view, page - 1))));
        }
        if (page < pages) {
            navigation = navigation.append(Component.space()).append(SearchCommandMessages.NEXT.asComponent()
                .clickEvent(ClickEvent.runCommand(command(id, view, page + 1))));
        }
        result.add(navigation);
        return List.copyOf(result);
    }

    public static Component join(List<Component> lines) {
        var result = Component.empty();
        for (var line : lines) {
            if (!result.equals(Component.empty())) {
                result = result.append(Component.newline());
            }
            result = result.append(line);
        }
        return result;
    }

    public static String command(UUID id, String view, int page) {
        return "/kansokusha event " + id + (view.isEmpty() ? "" : " " + view + " " + page);
    }

    Component absolute(java.time.Instant instant) {
        return EventTime.absolute(instant, this.timezone);
    }

    private EventPresenter.Content content(SearchPage.Event event, boolean detailed) {
        try {
            var content = this.presenter.present(event, detailed, this);
            if (content.isPresent()) {
                return content.get();
            }
        } catch (RuntimeException ignored) {
            // A corrupt/unsupported provider payload must not hide the rest of the page.
        }
        var summary = this.actor(event).append(Component.space())
            .append(Component.text(SearchCommandSupport.displayEventType(event.eventType())));
        if (event.targetType().isPresent()) {
            summary = summary.append(Component.space()).append(this.name(event.targetType().get(), NameKind.OTHER));
        }
        if (event.searchText().isPresent()) {
            summary = summary.append(Component.space()).append(Component.text(preview(event.searchText().get())));
        }
        if (!detailed && event.payload().isEmpty()) {
            summary = summary.append(Component.space()).append(EventPresentationMessages.MORE_IN_DETAILS.asComponent());
        }
        return new EventPresenter.Content(summary, detailed
            ? List.of(EventPresentationMessages.UNAVAILABLE.asComponent()) : List.of());
    }

    public static String preview(String text) {
        var line = text.replace('\r', ' ').replace('\n', ' ');
        var count = line.codePointCount(0, line.length());
        return count <= 36 ? line : line.substring(0, line.offsetByCodePoints(0, 36)) + "…";
    }

    private static Component location(SearchPage.Event event, boolean world) {
        var value = world ? event.world().map(Key::asString).orElseGet(() -> event.server().map(Key::asString).orElse("")) : "";
        if (event.position().isPresent()) {
            var position = event.position().get();
            value += " (" + position.x() + "," + position.y() + "," + position.z() + ")";
        }
        return value.isEmpty() ? Component.empty() : Component.text(value);
    }
}
