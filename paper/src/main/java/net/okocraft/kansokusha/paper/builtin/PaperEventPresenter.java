package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.common.command.EventFieldMessages;
import net.okocraft.kansokusha.common.command.EventPresentationMessages;
import net.okocraft.kansokusha.common.command.EventPresenter;
import net.okocraft.kansokusha.common.command.EventRenderer;
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
public final class PaperEventPresenter implements EventPresenter {

    private static final Map<String, PaperEventView> VIEWS = views();

    private static Map<String, PaperEventView> views() {
        var views = new LinkedHashMap<String, PaperEventView>();
        for (var group : List.of(PaperBlockViews.VIEWS, PaperItemViews.VIEWS, PaperPlayerViews.VIEWS,
            PaperEntityViews.VIEWS, PaperAdministrativeViews.VIEWS)) {
            for (var view : group) {
                if (views.putIfAbsent(view.type(), view) != null) {
                    throw new IllegalStateException("Duplicate event view: " + view.type());
                }
            }
        }
        return Map.copyOf(views);
    }

    public static List<Map<String, String>> defaultMessages() {
        return VIEWS.values().stream().map(view -> view.sentence().defaultMessages()).toList();
    }

    static boolean supports(SearchPage.Event event) {
        return event.eventType().namespace().equals("kansokusha") && VIEWS.containsKey(event.eventType().value());
    }

    public static EventRenderer renderer(Clock clock, ZoneId timezone) {
        return new EventRenderer(clock, timezone, new PaperEventPresenter(), PaperEventNames::name);
    }

    @Override
    public Optional<Content> present(SearchPage.Event event, boolean detailed, EventRenderer renderer) {
        if (!event.eventType().namespace().equals("kansokusha")) {
            return Optional.empty();
        }
        var view = VIEWS.get(event.eventType().value());
        if (view == null) {
            return Optional.empty();
        }
        if (event.payload().isEmpty()) {
            return Optional.of(view.sentence().withoutPayload(event, renderer, event.targetType().map(key -> renderer.name(key, view.targetKind())).orElse(Component.empty())));
        }
        if (event.payload().get().generation().value() != 1) {
            return Optional.empty();
        }
        try {
            var payload = PaperPayloadNbtCodec.decode(event.payload().get().bytes());
            var target = event.targetType().map(key -> renderer.name(key, view.targetKind())).orElse(Component.empty());
            var hover = new ArrayList<Component>();
            for (var spec : view.fields()) {
                var value = field(event, payload, spec, renderer.debug());
                if (value != null) {
                    hover.add(EventFieldMessages.field(label(spec), value));
                }
                if (hover.size() == 4) {
                    break;
                }
            }
            if (view.targetKind() == EventRenderer.NameKind.BLOCK) {
                var states = new ArrayList<Component>();
                for (var field : List.of("pre_state", "post_state", "before", "to", "old_state", "new_state", "replaced", "state")) {
                    var state = payload.get(field);
                    if (state instanceof CompoundTag compound && (compound.contains("id") || compound.contains("Name"))) {
                        states.add(EventFieldMessages.field(field, renderer.debug() ? PaperPayloadDisplay.hoverValue(state, field) : PaperPayloadDisplay.preview(state, field, false)));
                    }
                }
                if (event.targetType().isPresent()) {
                    if (view.type().equals("block_break")) {
                        states.add(properties(event.targetType().get(), payload, renderer.debug()));
                    } else if (view.type().equals("block_place") && payload.get("placed") instanceof CompoundTag placed) {
                        states.add(properties(event.targetType().get(), placed, renderer.debug()));
                    }
                }
                if (!states.isEmpty()) {
                    target = target.hoverEvent(HoverEvent.showText(EventRenderer.join(states)));
                }
            }
            if (!view.quantity().isEmpty()) {
                if (view.quantity().equals("amount_delta")) {
                    var delta = payload.getIntOr("amount_delta", 0);
                    target = target.append(Component.text(" " + (delta > 0 ? "+" : "") + delta));
                } else {
                    var item = PaperPayloadDisplay.at(payload, view.quantity());
                    if (item != null) {
                        // The item view contains the type once, alongside its saved quantity.
                        target = PaperPayloadDisplay.preview(item, view.quantity(), renderer.debug());
                    }
                }
            }
            var context = (detailed ? Optional.<String>empty() : event.searchText()).map(text -> Component.text(" “" + EventRenderer.preview(text) + "”"))
                .orElse(Component.empty());
            if (event.actor().isEmpty() && payload.contains("source_name")) {
                context = Component.space().append(Component.text(EventRenderer.preview(payload.getStringOr("source_name", "")))).append(context);
            }
            var details = new ArrayList<Component>();
            var sections = new LinkedHashMap<String, Section>();
            sections.put("raw", new Section(EventPresentationMessages.RAW.asComponent(), () -> PaperPayloadDisplay.details(payload)));
            if (detailed) {
                for (var spec : view.primary()) {
                    var value = field(event, payload, spec, renderer.debug());
                    if (value == null) {
                        continue;
                    }
                    var itemTag = PaperPayloadDisplay.at(payload, label(spec));
                    details.add(EventFieldMessages.field(label(spec), value).append(itemTag == null ? Component.empty()
                        : PaperRecordedItems.readAction(event.eventId(), label(spec), itemTag)));
                    var tag = PaperPayloadDisplay.at(payload, spec.startsWith("~") ? spec.substring(1) : spec);
                    if (tag != null && PaperPayloadDisplay.isText(tag)) {
                        var text = PaperPayloadDisplay.text(tag, spec);
                        var body = List.of("message", "command", "death_message").contains(label(spec))
                            || spec.startsWith("~") || tag instanceof net.minecraft.nbt.ListTag;
                        if (!body && text.codePointCount(0, text.length()) <= 36) {
                            continue;
                        }
                        sections.put("text_" + label(spec), new Section(EventFieldMessages.label(label(spec))
                            .append(Component.space()).append(EventPresentationMessages.FULL.asComponent()),
                            () -> EventRenderer.textLines(text)));
                    }
                }
            }
            if (detailed && PaperRecordedItems.hasItems(payload)) {
                sections.put("items", new Section(PaperViewMessages.ITEMS.asComponent(), () -> PaperRecordedItems.items(payload, event.eventId())));
            }
            return Optional.of(new Content(view.sentence().summary(renderer.actor(event), EventPresentationMessages.TARGET.apply(target), context),
                details, EventRenderer.join(hover), sections));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
    private static Component properties(net.kyori.adventure.key.Key type, CompoundTag properties, boolean debug) {
        // These schemas deliberately store the type in common columns and only properties in the payload.
        var state = new CompoundTag();
        state.putString("id", type.asString());
        state.put("properties", properties);
        return PaperPayloadDisplay.stateHover(state, debug);
    }

    private static String label(String spec) {
        return spec.startsWith("$") || spec.startsWith("~") ? spec.substring(1) : spec;
    }

    private static @org.jetbrains.annotations.Nullable Component field(SearchPage.Event event, CompoundTag payload, String spec, boolean debug) {
        var label = label(spec);
        if (spec.equals("$server") || spec.equals("$world")) {
            return (spec.equals("$server") ? event.server() : event.world()).map(key -> (Component) Component.text(key.asString())).orElse(null);
        }
        var value = PaperPayloadDisplay.at(payload, label);
        if (value == null) {
            return null;
        }
        return spec.startsWith("~") ? Component.text(EventRenderer.preview(PaperPayloadDisplay.text(value, spec)))
            : PaperPayloadDisplay.preview(value, label, debug);
    }
}
