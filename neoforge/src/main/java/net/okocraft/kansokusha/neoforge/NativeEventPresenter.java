package net.okocraft.kansokusha.neoforge;

import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.key.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.okocraft.kansokusha.common.command.EventFieldMessages;
import net.okocraft.kansokusha.common.command.EventPresentationMessages;
import net.okocraft.kansokusha.common.command.EventPresenter;
import net.okocraft.kansokusha.common.command.EventRenderer;
import net.okocraft.kansokusha.common.search.SearchPage;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Renders the native JSON payload without interpreting Paper's binary payload generation. */
final class NativeEventPresenter implements EventPresenter {
    static EventRenderer renderer(Clock clock, ZoneId timezone) {
        return new EventRenderer(clock, timezone, new NativeEventPresenter(),
            NativeEventPresenter::name);
    }

    private static Component name(Key key, EventRenderer.NameKind kind) {
        var id = Identifier.tryParse(key.asString());
        if (id == null) return Component.text(key.asString());
        var translation = switch (kind) {
            case BLOCK -> BuiltInRegistries.BLOCK.getOptional(id).map(block -> block.getDescriptionId());
            case ITEM -> BuiltInRegistries.ITEM.getOptional(id).map(item -> item.getDescriptionId());
            case ENTITY -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(type -> type.getDescriptionId());
            default -> Optional.<String>empty();
        };
        return translation.<Component>map(Component::translatable)
            .orElseGet(() -> Component.text(key.asString()));
    }

    @Override
    public Optional<Content> present(SearchPage.Event event, boolean detailed, EventRenderer renderer) {
        if (!event.eventType().namespace().equals("kansokusha")) return Optional.empty();
        var stored = event.payload().orElse(null);
        if (stored == null || stored.generation().value() != 2) return Optional.empty();
        try {
            var payload = JsonParser.parseString(new String(stored.bytes().copyBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var target = event.targetType().map(key -> renderer.name(key, targetKind(event.eventType().value())))
                .orElse(Component.empty());
            var delta = payload.get("amount_delta");
            if (delta != null) {
                var quantity = delta.getAsInt();
                var amount = "x" + Math.abs((long) quantity);
                target = target.equals(Component.empty()) ? Component.text(amount)
                    : target.append(Component.text(" " + amount));
            }
            var action = event.eventType().value().replace('_', ' ');
            if (delta != null && event.eventType().value().equals("container_transaction")) {
                action = delta.getAsInt() > 0 ? "added to container" : "removed from container";
            }
            var summary = renderer.actor(event).append(Component.space()).append(Component.text(action));
            if (!target.equals(Component.empty())) summary = summary.append(Component.space()).append(target);
            var communication = payload.has("message") ? payload.get("message")
                : payload.has("command") ? payload.get("command") : null;
            if (!detailed && communication != null) summary = summary.append(Component.text(" “"
                + EventRenderer.preview(communication.getAsString()) + "”"));

            var details = new ArrayList<Component>();
            var hover = new ArrayList<Component>();
            var sections = new LinkedHashMap<String, Section>();
            for (var field : payload.entrySet()) {
                if (!renderer.debug() && technical(field.getKey())) continue;
                if (event.eventType().value().equals("book_edit")
                    && (field.getKey().equals("before") || field.getKey().equals("after"))
                    && field.getValue().isJsonObject() && field.getValue().getAsJsonObject().has("pages")) {
                    var book = field.getValue().getAsJsonObject();
                    var pages = book.getAsJsonArray("pages");
                    var item = book.has("item") ? book.get("item").getAsString() : "empty";
                    var view = "read_" + field.getKey();
                    var line = Component.text(field.getKey() + ": " + item + " (" + pages.size() + " pages)")
                        .append(Component.space()).append(Component.text("[Read]")
                            .clickEvent(ClickEvent.runCommand(EventRenderer.command(event.eventId(), view, 1))));
                    if (detailed) details.add(line);
                    if (hover.size() < 3) hover.add(line);
                    sections.put(view, new Section(Component.text("[Read " + field.getKey() + "]"),
                        () -> bookLines(book)));
                    continue;
                }
                var value = display(field.getValue(), renderer.debug());
                var line = Component.text(field.getKey() + ": " + value);
                if (detailed) details.add(line);
                if (hover.size() < 3) hover.add(line);
                if (field.getKey().equals("message") || field.getKey().equals("command") || value.length() > 64) {
                    sections.put("text_" + field.getKey(), new Section(EventFieldMessages.label(field.getKey())
                        .append(Component.space()).append(EventPresentationMessages.FULL.asComponent()),
                        () -> EventRenderer.textLines(value)));
                }
            }
            if (renderer.debug()) sections.put("raw", new Section(EventPresentationMessages.RAW.asComponent(),
                () -> EventRenderer.textLines(payload.toString())));
            return Optional.of(new Content(summary, details, EventRenderer.join(hover), Map.copyOf(sections)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static EventRenderer.NameKind targetKind(String action) {
        if (action.startsWith("block_") || action.startsWith("fluid_") || action.startsWith("piston_")
            || action.startsWith("world_") || action.startsWith("bucket_")
            || action.equals("explosion_block_change") || action.equals("sign_change")
            || action.equals("tnt_prime")) return EventRenderer.NameKind.BLOCK;
        if (action.startsWith("item_") || action.startsWith("container_") || action.equals("craft_item")
            || action.equals("anvil_use") || action.equals("smith_item") || action.equals("enchant_item")
            || action.equals("player_trade") || action.equals("book_edit")
            || action.equals("flower_pot_change") || action.equals("dispenser_dispense")) return EventRenderer.NameKind.ITEM;
        if (action.startsWith("entity_") || action.equals("armor_stand_manipulate")
            || action.equals("item_frame_change")) return EventRenderer.NameKind.ENTITY;
        return EventRenderer.NameKind.OTHER;
    }

    private static boolean technical(String field) {
        return field.equals("components") || field.equals("nbt") || field.equals("raw")
            || field.equals("killer") || field.equals("new_owner")
            || field.equals("holder_before") || field.equals("holder_after")
            || field.toLowerCase(java.util.Locale.ROOT).contains("uuid");
    }

    private static String display(JsonElement value, boolean debug) {
        var shown = debug ? value : visible(value);
        return shown.isJsonPrimitive() ? shown.getAsString() : shown.toString();
    }

    private static JsonElement visible(JsonElement value) {
        if (value.isJsonObject()) {
            var result = new JsonObject();
            for (var field : value.getAsJsonObject().entrySet()) {
                if (!technical(field.getKey())) result.add(field.getKey(), visible(field.getValue()));
            }
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray();
            for (var child : value.getAsJsonArray()) result.add(visible(child));
            return result;
        }
        return value;
    }

    private static List<Component> bookLines(JsonObject book) {
        var lines = new ArrayList<Component>();
        if (book.has("title")) lines.add(Component.text("Title: " + display(book.get("title"), false)));
        if (book.has("author")) lines.add(Component.text("Author: " + display(book.get("author"), false)));
        var pages = book.getAsJsonArray("pages");
        for (int index = 0; index < pages.size(); index++) {
            lines.add(Component.text("Page " + (index + 1)));
            lines.addAll(EventRenderer.textLines(display(pages.get(index), false)));
        }
        return List.copyOf(lines);
    }
}
