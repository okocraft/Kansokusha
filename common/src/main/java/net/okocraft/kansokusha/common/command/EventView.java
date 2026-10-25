package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
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
        this.message = this.definer.define("kansokusha.display.event." + type, english)
            .with(actor -> Argument.component("actor", actor),
                target -> Argument.component("target", target), context -> Argument.component("context", context));
    }

    public Component summary(Component actor, Component target, Component context) {
        return this.message.apply(actor, target, context);
    }

    public Map<String, String> defaultMessages() {
        return this.definer.getCollectedMessages();
    }
}
