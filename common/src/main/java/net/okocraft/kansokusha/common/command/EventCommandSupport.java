package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

@ApiStatus.Internal
@NotNullByDefault
public final class EventCommandSupport {

    public static final String PERMISSION = "kansokusha.command.event";

    private static final Set<Key> COMMUNICATION_EVENT_TYPES = Set.of(
        Key.key("kansokusha", "paper_chat"),
        Key.key("kansokusha", "velocity_chat"),
        Key.key("kansokusha", "paper_player_command"),
        Key.key("kansokusha", "paper_server_command"),
        Key.key("kansokusha", "velocity_command")
    );

    private static final List<EventSpecificRenderer> EVENT_SPECIFIC_RENDERERS = List.of();

    public static boolean execute(
        KansokushaApi api,
        String rawEventId,
        Predicate<String> hasPermission,
        Consumer<Component> sendMessage
    ) {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(rawEventId, "rawEventId");
        Objects.requireNonNull(hasPermission, "hasPermission");
        Objects.requireNonNull(sendMessage, "sendMessage");

        final UUID eventId;
        try {
            eventId = parseEventId(rawEventId);
        } catch (IllegalArgumentException e) {
            sendMessage.accept(EventCommandMessages.INVALID_ID.asComponent());
            return false;
        }

        final EventSearchBackend backend;
        try {
            backend = EventSearchBackend.require(api);
        } catch (IllegalArgumentException e) {
            sendMessage.accept(EventCommandMessages.LOOKUP_FAILED.asComponent());
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

            var event = found.get();
            if (!hasPermission.test(eventPermission(event.eventType()))) {
                sendMessage.accept(EventCommandMessages.PERMISSION_DENIED.asComponent());
                return;
            }

            formatEvent(event).forEach(sendMessage);
        });
        return true;
    }

    public static String eventPermission(Key eventType) {
        return SearchCommandSupport.eventPermission(eventType);
    }

    public static List<Component> formatEvent(EventDetail event) {
        Objects.requireNonNull(event, "event");
        var lines = new ArrayList<Component>();

        lines.add(line(EventCommandMessages.EVENT_ID, Component.text(event.eventId().toString())));
        lines.add(line(EventCommandMessages.OCCURRED_AT, Component.text(event.occurredAt().toString())));
        lines.add(line(EventCommandMessages.EVENT_TYPE, eventType(event.eventType())));
        event.server().ifPresent(value ->
            lines.add(line(EventCommandMessages.SERVER, Component.text(value.asString())))
        );
        event.world().ifPresent(value ->
            lines.add(line(EventCommandMessages.WORLD, Component.text(value.asString())))
        );
        if (event.x().isPresent() && event.y().isPresent() && event.z().isPresent()) {
            lines.add(line(
                EventCommandMessages.POSITION,
                Component.text(
                    event.x().getAsInt() + ", "
                        + event.y().getAsInt() + ", "
                        + event.z().getAsInt()
                )
            ));
        }

        var actor = actor(event);
        if (actor != null) {
            lines.add(line(EventCommandMessages.ACTOR, actor));
        }

        event.targetType().ifPresent(value ->
            lines.add(line(EventCommandMessages.TARGET_TYPE, Component.text(value.asString())))
        );
        lines.add(line(
            EventCommandMessages.PAYLOAD_GENERATION,
            Component.text(Integer.toString(event.payloadGeneration().value()))
        ));
        lines.add(line(EventCommandMessages.EXPIRES_AT, Component.text(event.expiresAt().toString())));

        if (COMMUNICATION_EVENT_TYPES.contains(event.eventType())) {
            event.searchText()
                .map(EventCommandSupport::singleLine)
                .ifPresent(value ->
                    lines.add(line(EventCommandMessages.COMMUNICATION_TEXT, Component.text(value)))
                );
        }

        for (var renderer : EVENT_SPECIFIC_RENDERERS) {
            if (renderer.supports(event.eventType(), event.payloadGeneration())) {
                lines.addAll(renderer.render(event));
            }
        }
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

    private static @Nullable Component actor(EventDetail event) {
        if (event.actorKind().isEmpty()) {
            return null;
        }

        return switch (event.actorKind().get()) {
            case PLAYER -> {
                var value = event.actorName()
                    .map(Component::text)
                    .orElseGet(() -> event.actorUuid()
                        .map(uuid -> Component.text(uuid.toString()))
                        .orElse(Component.empty()));
                var result = EventCommandMessages.ACTOR_PLAYER.asComponent()
                    .append(Component.space())
                    .append(value);
                yield event.actorUuid()
                    .map(uuid -> result.hoverEvent(HoverEvent.showText(Component.text(uuid.toString()))))
                    .orElse(result);
            }
            case ENTITY -> {
                var value = event.actorType()
                    .map(type -> Component.text(type.asString()))
                    .orElseGet(() -> event.actorUuid()
                        .map(uuid -> Component.text(uuid.toString()))
                        .orElse(Component.empty()));
                var result = EventCommandMessages.ACTOR_ENTITY.asComponent()
                    .append(Component.space())
                    .append(value);
                yield event.actorUuid()
                    .map(uuid -> result.hoverEvent(HoverEvent.showText(Component.text(uuid.toString()))))
                    .orElse(result);
            }
            case BLOCK -> {
                var result = EventCommandMessages.ACTOR_BLOCK.asComponent();
                if (event.actorType().isPresent()) {
                    result = result.append(Component.space())
                        .append(Component.text(event.actorType().get().asString()));
                }
                yield result;
            }
        };
    }

    private static Component line(MessageKey label, Component value) {
        return label.asComponent().append(Component.text(": ")).append(value);
    }

    private static String singleLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    @ApiStatus.Internal
    public interface EventSpecificRenderer {

        boolean supports(Key eventType, PayloadGeneration payloadGeneration);

        List<Component> render(EventDetail event);
    }

    private EventCommandSupport() {
        throw new UnsupportedOperationException();
    }
}
