package net.okocraft.kansokusha.velocity.builtin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.common.command.EventFieldMessages;
import net.okocraft.kansokusha.common.command.EventPresenter;
import net.okocraft.kansokusha.common.command.EventRenderer;
import net.okocraft.kansokusha.common.command.EventView;
import net.okocraft.kansokusha.common.search.SearchPage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApiStatus.Internal
@NotNullByDefault
public final class VelocityEventPresenter implements EventPresenter {

    private static final Map<String, View> VIEWS = Map.of(
        "server_connected", view("server_connected", "<actor> connected to <target><context>",
            payload -> facts("from", VelocityServerConnectedPayloadCodec.decode(payload).map(Object::toString).orElse(null)), "from"),
        "velocity_post_login", view("velocity_post_login", "<actor> logged into the proxy<context>", payload -> {
            var data = VelocityPlayerSessionPayloadCodec.decodePostLogin(payload);
            return facts("username", data.username(), "remote_address", data.remoteAddress(),
                "virtual_host", data.virtualHost(), "raw_virtual_host", data.rawVirtualHost());
        }),
        "velocity_disconnect", view("velocity_disconnect", "<actor> left the proxy<context>", payload -> {
            var data = VelocityPlayerSessionPayloadCodec.decodeDisconnect(payload);
            return facts("username", data.username(), "login_status", data.loginStatus(), "from", data.currentBackendKey());
        }, "from", "login_status"),
        "backend_kick", view("backend_kick", "<actor> was kicked by <target><context>", payload -> {
            var data = VelocityPlayerSessionPayloadCodec.decodeBackendKick(payload);
            return facts("reason", data.originalReason(), "during_server_connect", data.duringServerConnect(),
                "action", data.action(), "destination", data.redirectTarget(), "message", data.message());
        }, "reason", "action", "destination"),
        "velocity_chat", view("velocity_chat", "<actor> said<context>",
            payload -> facts("message", VelocityCommunicationPayloadCodec.decodeChat(payload)), "message"),
        "velocity_command", view("velocity_command", "<actor> entered a command<context>", payload -> {
            var data = VelocityCommunicationPayloadCodec.decodeCommand(payload);
            return facts("source_kind", data.sourceKind(), "source_name", data.sourceName(), "command", data.command());
        }, "source_kind", "source_name", "command"),
        "backend_registry_change", view("backend_registry_change", "Backend registry changed<context>", payload -> {
            var data = VelocityBackendRegistryChangePayloadCodec.decode(payload);
            var address = data.serverInfo().getAddress();
            return facts("action", data.action(), "server", data.serverKey(), "source_name", data.serverInfo().getName(),
                "host", address.getHostString(), "port", address.getPort(), "unresolved", address.isUnresolved(),
                "resolved_address", address.isUnresolved() ? null : address.getAddress().getHostAddress());
        }, "action", "server", "source_name")
    );

    public static List<Map<String, String>> defaultMessages() {
        return VIEWS.values().stream().map(view -> view.sentence().defaultMessages()).toList();
    }

    public static EventRenderer renderer(Clock clock, ZoneId timezone) {
        // Velocity does not expose a Minecraft item/block registry. Its built-ins have no such targets.
        return new EventRenderer(clock, timezone, new VelocityEventPresenter(),
            (key, kind) -> Component.text(key.asString()));
    }

    @Override
    public Optional<Content> present(SearchPage.Event event, boolean detailed, EventRenderer renderer) {
        var view = event.eventType().namespace().equals("kansokusha") ? VIEWS.get(event.eventType().value()) : null;
        if (view == null || event.payload().isEmpty() || event.payload().get().generation().value() != 1) {
            return Optional.empty();
        }
        try {
            var facts = view.decoder().decode(event.payload().get().bytes());
            var context = Component.empty();
            for (var field : view.summaryFields()) {
                if (facts.containsKey(field)) {
                    context = context.append(Component.text(" | ")).append(EventFieldMessages.field(field,
                        Component.text(EventRenderer.preview(facts.get(field)))));
                }
            }
            var lines = new ArrayList<Component>();
            if (detailed) {
                for (var entry : facts.entrySet()) {
                    var text = entry.getValue();
                    if (text.isEmpty()) {
                        lines.add(EventFieldMessages.field(entry.getKey(), Component.empty()));
                    }
                    while (!text.isEmpty()) {
                        var end = text.offsetByCodePoints(0, Math.min(256, text.codePointCount(0, text.length())));
                        lines.add(EventFieldMessages.field(entry.getKey(), Component.text(text.substring(0, end))));
                        text = text.substring(end);
                    }
                }
            }
            var target = event.server().map(key -> Component.text(key.asString())).orElse(Component.empty());
            return Optional.of(new Content(view.sentence().summary(renderer.actor(event), target, context), lines));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static View view(String type, String english, Decoder decoder, String... fields) {
        return new View(new EventView(type, english), decoder, List.of(fields));
    }

    private static Map<String, String> facts(Object... values) {
        var result = new LinkedHashMap<String, String>();
        for (var i = 0; i < values.length; i += 2) {
            var value = values[i + 1];
            if (value != null) {
                // Never re-emit click/hover events from a recorded kick reason or message.
                var text = value instanceof Component component
                    ? PlainTextComponentSerializer.plainText().serialize(component) : value.toString();
                result.put((String) values[i], text);
            }
        }
        return result;
    }

    private record View(EventView sentence, Decoder decoder, List<String> summaryFields) {
    }

    @FunctionalInterface
    private interface Decoder {
        Map<String, String> decode(EventPayload payload) throws IOException;
    }
}
