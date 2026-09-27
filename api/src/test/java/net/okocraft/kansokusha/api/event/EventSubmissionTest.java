package net.okocraft.kansokusha.api.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventSubmissionTest {

    private static final Key EVENT_TYPE = Key.key("test", "block-break");
    private static final Key SERVER_KEY = Key.key("test", "survival-1");
    private static final Key WORLD_KEY = Key.key("test", "world");
    private static final EventActor ACTOR = new PlayerActor(
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    );
    private static final Key TARGET_TYPE = Key.key("minecraft", "stone");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05Z");
    private static final EventPayload PAYLOAD = EventPayload.copyOf(new byte[]{10, 20});

    @Test
    void testOptionalFieldsMayBeAbsentAndLocationRequiresServerContext() {
        EventSubmission withoutContext = submission(null, null, null, null, null);
        assertAll(
            () -> assertNull(withoutContext.serverKey()),
            () -> assertNull(withoutContext.worldKey()),
            () -> assertNull(withoutContext.position()),
            () -> assertNull(withoutContext.actor()),
            () -> assertNull(withoutContext.targetType()),
            () -> assertThrows(IllegalArgumentException.class,
                () -> submission(null, WORLD_KEY, null, null, null)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> submission(SERVER_KEY, null, new BlockPosition(0, 64, 0), null, null))
        );
    }

    private static EventSubmission submission(
        Key serverKey, Key worldKey, BlockPosition position, EventActor actor, Key targetType
    ) {
        return new EventSubmission(
            EVENT_TYPE, new PayloadGeneration(3), OCCURRED_AT, serverKey,
            worldKey, position, actor, targetType, PAYLOAD
        );
    }
}
