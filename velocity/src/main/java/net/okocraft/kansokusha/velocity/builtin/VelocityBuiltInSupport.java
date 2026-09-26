package net.okocraft.kansokusha.velocity.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/**
 * Helpers shared by Velocity built-in event subscribers.
 */
@NotNullByDefault
final class VelocityBuiltInSupport {

    private VelocityBuiltInSupport() {
    }

    /**
     * Registers the given built-in event types with payload generation 1.
     *
     * @throws IllegalStateException if an event type conflicts with an existing registration
     */
    static void register(KansokushaApi api, Key... eventTypes) {
        Objects.requireNonNull(api, "api");
        for (var eventType : eventTypes) {
            var outcome = api.registerEventType(
                new EventTypeDefinition(eventType, PayloadGeneration.FIRST)
            );
            if (
                outcome != RegistrationOutcome.REGISTERED
                    && outcome != RegistrationOutcome.ALREADY_REGISTERED
            ) {
                throw new IllegalStateException(
                    "Could not register built-in event type " + eventType + ": " + outcome
                );
            }
        }
    }
}
