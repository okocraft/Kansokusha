package net.okocraft.kansokusha.velocity.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Helpers shared by Velocity built-in event subscribers.
 */
@NotNullByDefault
final class VelocityBuiltInSupport {

    private VelocityBuiltInSupport() {
    }

    /**
     * Registers the given built-in event types with payload generation 1.
     */
    static void register(KansokushaApi api, Key... eventTypes) {
        for (var eventType : eventTypes) {
            api.registerEventType(new EventTypeDefinition(eventType, PayloadGeneration.FIRST));
        }
    }
}
