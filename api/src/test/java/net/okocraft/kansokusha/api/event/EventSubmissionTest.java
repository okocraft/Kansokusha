package net.okocraft.kansokusha.api.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.EventSubject;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventSubmissionTest {

    private static final Key EVENT_TYPE = Key.key("test", "block-break");
    private static final Key SERVER_KEY = Key.key("test", "survival-1");
    private static final Key WORLD_KEY = Key.key("test", "world");
    private static final EventSubject SUBJECT = new PlayerSubject(
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    );
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05Z");
    private static final EventPayload PAYLOAD = EventPayload.copyOf(new byte[]{10, 20});

    @Test
    void testSubmissionPreservesRequiredAndOptionalValues() {
        BlockPosition position = new BlockPosition(1, 64, -3);
        EventSubmission submission = submission(SERVER_KEY, WORLD_KEY, position, SUBJECT);

        assertAll(
            () -> assertEquals(EVENT_TYPE, submission.eventType()),
            () -> assertEquals(new PayloadGeneration(3), submission.payloadGeneration()),
            () -> assertEquals(OCCURRED_AT, submission.occurredAt()),
            () -> assertEquals(SERVER_KEY, submission.serverKey()),
            () -> assertEquals(WORLD_KEY, submission.worldKey()),
            () -> assertEquals(position, submission.position()),
            () -> assertEquals(SUBJECT, submission.subject()),
            () -> assertEquals(PAYLOAD, submission.payload())
        );
    }

    @Test
    void testOptionalFieldsMayBeAbsentAndLocationRequiresServerContext() {
        EventSubmission withoutContext = submission(null, null, null, null);
        assertAll(
            () -> assertNull(withoutContext.serverKey()),
            () -> assertNull(withoutContext.worldKey()),
            () -> assertNull(withoutContext.position()),
            () -> assertNull(withoutContext.subject()),
            () -> assertThrows(IllegalArgumentException.class,
                () -> submission(null, WORLD_KEY, null, null)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> submission(SERVER_KEY, null, new BlockPosition(0, 64, 0), null))
        );
    }

    @Test
    void testRequiredValuesAreRejectedWhenNull() {
        assertAll(
            () -> assertThrows(NullPointerException.class,
                () -> new EventSubmission(null, PayloadGeneration.FIRST, OCCURRED_AT,
                    SERVER_KEY, null, null, null, PAYLOAD)),
            () -> assertThrows(NullPointerException.class,
                () -> new EventSubmission(EVENT_TYPE, null, OCCURRED_AT,
                    SERVER_KEY, null, null, null, PAYLOAD)),
            () -> assertThrows(NullPointerException.class,
                () -> new EventSubmission(EVENT_TYPE, PayloadGeneration.FIRST, null,
                    SERVER_KEY, null, null, null, PAYLOAD)),
            () -> assertThrows(NullPointerException.class,
                () -> new EventSubmission(EVENT_TYPE, PayloadGeneration.FIRST, OCCURRED_AT,
                    SERVER_KEY, null, null, null, null))
        );
    }

    private static EventSubmission submission(
        Key serverKey, Key worldKey, BlockPosition position, EventSubject subject
    ) {
        return new EventSubmission(
            EVENT_TYPE, new PayloadGeneration(3), OCCURRED_AT, serverKey,
            worldKey, position, subject, PAYLOAD
        );
    }
}
