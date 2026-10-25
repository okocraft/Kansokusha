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
        var content = this.content(event, false);
        var result = EventPresentationMessages.ROW.apply(
            EventTime.relative(event.occurredAt(), this.clock.instant(), this.timezone), content.summary());
        if (location) {
            result = result.append(location(event));
        }
        result = result.hoverEvent(HoverEvent.showText(detailsPermitted
            ? SearchCommandMessages.VIEW_EVENT.apply(event.eventId().toString())
            : Component.text(event.eventId().toString())));
        return detailsPermitted
            ? result.clickEvent(ClickEvent.runCommand("/kansokusha event " + event.eventId())) : result;
    }

    public List<Component> detail(EventDetail detail) {
        var content = this.content(detail.event(), true);
        var result = new ArrayList<Component>();
        result.add(content.summary());
        result.addAll(content.details());
        result.addAll(EventCommandSupport.commonFields(detail, this));
        return List.copyOf(result);
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
        return new EventPresenter.Content(summary, detailed
            ? List.of(EventPresentationMessages.UNAVAILABLE.asComponent()) : List.of());
    }

    public static String preview(String text) {
        var line = text.replace('\r', ' ').replace('\n', ' ');
        var count = line.codePointCount(0, line.length());
        return count <= 100 ? line : line.substring(0, line.offsetByCodePoints(0, 100)) + "…";
    }

    private static Component location(SearchPage.Event event) {
        var value = event.world().map(Key::asString).orElseGet(() -> event.server().map(Key::asString).orElse(""));
        if (event.position().isPresent()) {
            var position = event.position().get();
            value += " " + position.x() + " " + position.y() + " " + position.z();
        }
        return value.isEmpty() ? Component.empty() : Component.text(" @ " + value);
    }
}
