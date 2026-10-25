package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.common.command.EventFieldMessages;
import net.okocraft.kansokusha.common.command.EventPresenter;
import net.okocraft.kansokusha.common.command.EventRenderer;
import net.okocraft.kansokusha.common.search.SearchPage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
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
        for (var group : List.of(PaperBlockViews.VIEWS)) {
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

    public static EventRenderer renderer(Clock clock, ZoneId timezone) {
        return new EventRenderer(clock, timezone, new PaperEventPresenter(), PaperEventNames::name);
    }

    @Override
    public Optional<Content> present(SearchPage.Event event, boolean detailed, EventRenderer renderer) {
        if (!event.eventType().namespace().equals("kansokusha")) {
            return Optional.empty();
        }
        var view = VIEWS.get(event.eventType().value());
        if (view == null || event.payload().isEmpty() || event.payload().get().generation().value() != 1) {
            return Optional.empty();
        }
        try {
            var payload = PaperPayloadNbtCodec.decode(event.payload().get().bytes());
            var context = Component.empty();
            for (var field : view.fields()) {
                var value = payload.get(field);
                if (value != null) {
                    context = context.append(Component.text(" | "))
                        .append(EventFieldMessages.field(field, PaperPayloadDisplay.preview(value, field)));
                }
            }
            var target = event.targetType().map(key -> renderer.name(key, view.targetKind())).orElse(Component.empty());
            return Optional.of(new Content(view.sentence().summary(renderer.actor(event), target, context),
                detailed ? PaperPayloadDisplay.details(payload) : List.of()));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
