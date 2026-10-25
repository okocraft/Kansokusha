package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.okocraft.kansokusha.common.search.SearchPage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;

/** A customizable sentence; providers choose the meaning and its context. */
@ApiStatus.Internal
@NotNullByDefault
public final class EventView {

    private final DefaultMessageDefiner definer = DefaultMessageDefiner.create();
    private final MessageKey.Arg3<Component, Component, Component> message;

    public EventView(String type, String english) {
        // Keep legacy keys registered for existing custom files. The new layout has its own keys.
        this.definer.define("kansokusha.display.event." + type, english);
        this.message = this.definer.define("kansokusha.display.compact.event." + type, english)
            .with(actor -> Argument.component("actor", actor),
                target -> Argument.component("target", target), context -> Argument.component("context", context));
    }

    public Component summary(Component actor, Component target, Component context) {
        return this.message.apply(actor.equals(Component.empty()) ? actor : actor.append(Component.space()), target, context);
    }

    public EventPresenter.Content withoutPayload(SearchPage.Event event, EventRenderer renderer, Component target) {
        var context = event.searchText().map(text -> Component.text(" “" + EventRenderer.preview(text) + "”"))
            .orElse(Component.empty());
        return new EventPresenter.Content(this.summary(renderer.actor(event), target, context), java.util.List.of(),
            (renderer.debug() ? EventPresentationMessages.MORE_IN_DETAILS : EventPresentationMessages.UNAVAILABLE_USER).asComponent(), java.util.Map.of());
    }

    public Map<String, String> defaultMessages() {
        return this.definer.getCollectedMessages();
    }
}
