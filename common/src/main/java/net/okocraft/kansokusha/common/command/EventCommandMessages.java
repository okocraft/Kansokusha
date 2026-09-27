package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public final class EventCommandMessages {

    public static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey INVALID_ID = DEFINER
        .define("kansokusha.command.event.invalid-id", "Invalid event ID.");
    public static final MessageKey NOT_FOUND = DEFINER
        .define("kansokusha.command.event.not-found", "Event not found.");
    public static final MessageKey PERMISSION_DENIED = DEFINER
        .define("kansokusha.command.event.permission-denied", "You do not have permission to view this event.");
    public static final MessageKey LOOKUP_FAILED = DEFINER
        .define("kansokusha.command.event.lookup-failed", "Failed to load event.");
    public static final MessageKey EVENT_ID = DEFINER
        .define("kansokusha.command.event.label.event-id", "Event ID");
    public static final MessageKey OCCURRED_AT = DEFINER
        .define("kansokusha.command.event.label.occurred-at", "Occurred at");
    public static final MessageKey EVENT_TYPE = DEFINER
        .define("kansokusha.command.event.label.event-type", "Event type");
    public static final MessageKey SERVER = DEFINER
        .define("kansokusha.command.event.label.server", "Server");
    public static final MessageKey WORLD = DEFINER
        .define("kansokusha.command.event.label.world", "World");
    public static final MessageKey POSITION = DEFINER
        .define("kansokusha.command.event.label.position", "Position");
    public static final MessageKey ACTOR = DEFINER
        .define("kansokusha.command.event.label.actor", "Actor");
    public static final MessageKey TARGET_TYPE = DEFINER
        .define("kansokusha.command.event.label.target-type", "Target type");
    public static final MessageKey PAYLOAD_GENERATION = DEFINER
        .define("kansokusha.command.event.label.payload-generation", "Payload generation");
    public static final MessageKey EXPIRES_AT = DEFINER
        .define("kansokusha.command.event.label.expires-at", "Expires at");
    public static final MessageKey COMMUNICATION_TEXT = DEFINER
        .define("kansokusha.command.event.label.communication-text", "Text");
    public static final MessageKey ACTOR_PLAYER = DEFINER
        .define("kansokusha.command.event.actor.player", "Player");
    public static final MessageKey ACTOR_ENTITY = DEFINER
        .define("kansokusha.command.event.actor.entity", "Entity");
    public static final MessageKey ACTOR_BLOCK = DEFINER
        .define("kansokusha.command.event.actor.block", "Block");

    private EventCommandMessages() {
        throw new UnsupportedOperationException();
    }
}
