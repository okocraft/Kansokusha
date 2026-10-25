package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

@ApiStatus.Internal
@NotNullByDefault
public final class EventCommandSupport {

    public static final String PERMISSION = "kansokusha.command.event";

    /**
     * Looks up one event and sends its details asynchronously.
     *
     * @return whether the lookup was started
     */
    public static boolean execute(
        EventSearchBackend backend,
        String rawEventId,
        Predicate<String> hasPermission,
        Consumer<Component> sendMessage
    ) {
        return execute(backend, rawEventId, hasPermission, sendMessage,
            EventRenderer.generic(java.time.Clock.systemUTC(), java.time.ZoneOffset.UTC));
    }

    public static boolean execute(
        EventSearchBackend backend,
        String rawEventId,
        Predicate<String> hasPermission,
        Consumer<Component> sendMessage,
        EventRenderer renderer
    ) {
        return execute(backend, rawEventId, hasPermission, sendMessage, renderer, "", 1);
    }

    public static boolean execute(EventSearchBackend backend, String rawEventId, Predicate<String> hasPermission,
                                  Consumer<Component> sendMessage, EventRenderer renderer, String view, int page) {
        return lookup(backend, rawEventId, hasPermission, sendMessage,
            detail -> renderer.view(detail, view, page).forEach(sendMessage));
    }

    public static boolean lookup(EventSearchBackend backend, String rawEventId, Predicate<String> hasPermission,
                                 Consumer<Component> sendMessage, Consumer<EventDetail> action) {
        final UUID eventId;
        try {
            eventId = parseEventId(rawEventId);
        } catch (IllegalArgumentException e) {
            sendMessage.accept(EventCommandMessages.INVALID_ID.asComponent());
            return false;
        }

        backend.findEvent(eventId).whenComplete((found, failure) -> {
            if (failure != null) {
                sendMessage.accept(EventCommandMessages.LOOKUP_FAILED.asComponent());
                return;
            }
            if (found.isEmpty()) {
                sendMessage.accept(EventCommandMessages.NOT_FOUND.asComponent());
                return;
            }

            var detail = found.get();
            if (!hasPermission.test(SearchCommandSupport.eventPermission(detail.event().eventType()))) {
                sendMessage.accept(EventCommandMessages.PERMISSION_DENIED.asComponent());
                return;
            }

            action.accept(detail);
        });
        return true;
    }

    public static List<Component> formatEvent(EventDetail detail) {
        return commonFields(detail, EventRenderer.generic(java.time.Clock.systemUTC(), java.time.ZoneOffset.UTC));
    }

    static List<Component> commonFields(EventDetail detail, EventRenderer renderer) {
        var event = detail.event();
        var lines = new ArrayList<Component>();

        lines.add(line(EventCommandMessages.EVENT_ID, Component.text(event.eventId().toString())));
        lines.add(line(EventCommandMessages.OCCURRED_AT, renderer.absolute(event.occurredAt())));
        lines.add(line(EventCommandMessages.EVENT_TYPE, eventType(event.eventType())));
        event.server().ifPresent(value ->
            lines.add(line(EventCommandMessages.SERVER, Component.text(value.asString())))
        );
        event.world().ifPresent(value ->
            lines.add(line(EventCommandMessages.WORLD, Component.text(value.asString())))
        );
        event.position().ifPresent(position -> lines.add(line(
            EventCommandMessages.POSITION,
            Component.text(position.x() + ", " + position.y() + ", " + position.z())
        )));
        event.actor().ifPresent(actor -> lines.add(line(EventCommandMessages.ACTOR, switch (actor) {
            case PlayerActor player -> SearchCommandSupport.withUuidHover(
                EventCommandMessages.ACTOR_PLAYER.asComponent()
                    .append(Component.space())
                    .append(Component.text(event.actorName().orElse(player.uniqueId().toString()))),
                player.uniqueId()
            );
            case EntityActor entity -> SearchCommandSupport.withUuidHover(
                EventCommandMessages.ACTOR_ENTITY.asComponent()
                    .append(Component.space())
                    .append(renderer.name(entity.entityType(), EventRenderer.NameKind.ENTITY)),
                entity.uniqueId()
            );
            case BlockActor block -> EventCommandMessages.ACTOR_BLOCK.asComponent()
                .append(Component.space())
                .append(renderer.name(block.blockType(), EventRenderer.NameKind.BLOCK));
        })));
        event.targetType().ifPresent(value ->
            lines.add(line(EventCommandMessages.TARGET_TYPE, Component.text(value.asString())))
        );
        lines.add(line(
            EventCommandMessages.PAYLOAD_GENERATION,
            Component.text(Integer.toString(detail.payloadGeneration().value()))
        ));
        lines.add(line(EventCommandMessages.EXPIRES_AT, renderer.absolute(detail.expiresAt())));
        event.searchText().ifPresent(value -> {
            if (value.isEmpty()) {
                lines.add(line(EventCommandMessages.COMMUNICATION_TEXT, Component.empty()));
            }
            while (!value.isEmpty()) {
                var end = value.offsetByCodePoints(0, Math.min(256, value.codePointCount(0, value.length())));
                lines.add(line(EventCommandMessages.COMMUNICATION_TEXT, Component.text(value.substring(0, end))));
                value = value.substring(end);
            }
        });
        return List.copyOf(lines);
    }

    static List<Component> metadata(EventDetail detail, EventRenderer renderer) {
        var event = detail.event();
        var common = new EventDetail(new net.okocraft.kansokusha.common.search.SearchPage.Event(
            event.eventId(), event.eventType(), event.occurredAt(), event.server(), event.world(), event.position(),
            event.actor(), event.actorName(), event.targetType(), java.util.Optional.empty()),
            detail.payloadGeneration(), detail.expiresAt());
        var lines = new ArrayList<>(commonFields(common, renderer));
        event.actor().ifPresent(actor -> {
            switch (actor) {
                case PlayerActor player -> lines.add(Component.text("UUID: " + player.uniqueId()));
                case EntityActor entity -> lines.add(Component.text("UUID: " + entity.uniqueId()));
                case BlockActor ignored -> { }
            }
        });
        return List.copyOf(lines);
    }

    static UUID parseEventId(String input) {
        var eventId = UUID.fromString(input);
        if (!eventId.toString().equalsIgnoreCase(input)) {
            throw new IllegalArgumentException("event ID must use canonical UUID syntax");
        }
        return eventId;
    }

    private static Component eventType(Key eventType) {
        var full = eventType.asString();
        var display = SearchCommandSupport.displayEventType(eventType);
        var component = Component.text(display);
        return display.equals(full)
            ? component
            : component.hoverEvent(HoverEvent.showText(Component.text(full)));
    }

    private static Component line(MessageKey label, Component value) {
        return EventCommandMessages.DETAIL_LINE.apply(label.asComponent(), value);
    }

    private EventCommandSupport() {
        throw new UnsupportedOperationException();
    }
}
