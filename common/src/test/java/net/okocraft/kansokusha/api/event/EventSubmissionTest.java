package net.okocraft.kansokusha.api.event;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventSubmissionTest {

    private static final Key EVENT_TYPE = Key.key("test", "block-break");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05Z");
    private static final OpaquePayload PAYLOAD = OpaquePayload.copyOf(new byte[]{10, 20});

    @Test
    void testSubmissionPreservesRequiredAndOptionalValues() {
        EventPosition position = new EventPosition(1.25, 64.0, -3.5);
        EventSubmission submission = submission("survival-1", "world", position, "player:123");

        assertAll(
            () -> assertEquals(EVENT_TYPE, submission.eventType()),
            () -> assertEquals(new PayloadGeneration(3), submission.payloadGeneration()),
            () -> assertEquals(OCCURRED_AT, submission.occurredAt()),
            () -> assertEquals("survival-1", submission.serverIdentifier()),
            () -> assertEquals("world", submission.worldIdentifier()),
            () -> assertEquals(position, submission.position()),
            () -> assertEquals("player:123", submission.subjectReference()),
            () -> assertEquals(PAYLOAD, submission.payload())
        );
    }

    @Test
    void testOptionalFieldsMayBeAbsentAndPositionRequiresWorld() {
        EventSubmission withoutLocation = submission("survival-1", null, null, null);
        assertAll(
            () -> assertNull(withoutLocation.worldIdentifier()),
            () -> assertNull(withoutLocation.position()),
            () -> assertNull(withoutLocation.subjectReference()),
            () -> assertThrows(IllegalArgumentException.class,
                () -> submission("survival-1", null, new EventPosition(0.0, 64.0, 0.0), null))
        );
    }

    @Test
    void testBlankOrNullIdentifiersAndRequiredValuesAreRejected() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> submission("", null, null, null)),
            () -> assertThrows(IllegalArgumentException.class, () -> submission("survival-1", " ", null, null)),
            () -> assertThrows(IllegalArgumentException.class, () -> submission("survival-1", null, null, "\t")),
            () -> assertThrows(NullPointerException.class, () -> submission(null, null, null, null)),
            () -> assertThrows(NullPointerException.class,
                () -> new EventSubmission(null, PayloadGeneration.FIRST, OCCURRED_AT,
                    "survival-1", null, null, null, PAYLOAD)),
            () -> assertThrows(NullPointerException.class,
                () -> new EventSubmission(EVENT_TYPE, null, OCCURRED_AT,
                    "survival-1", null, null, null, PAYLOAD)),
            () -> assertThrows(NullPointerException.class,
                () -> new EventSubmission(EVENT_TYPE, PayloadGeneration.FIRST, null,
                    "survival-1", null, null, null, PAYLOAD)),
            () -> assertThrows(NullPointerException.class,
                () -> new EventSubmission(EVENT_TYPE, PayloadGeneration.FIRST, OCCURRED_AT,
                    "survival-1", null, null, null, null))
        );
    }

    @Test
    void testEnvelopeRetainsSubmissionAndResolvedRetentionReference() {
        EventSubmission submission = submission("survival-1", null, null, null);
        Key retentionReference = Key.key("test", "default-retention");
        EventEnvelope envelope = new EventEnvelope(submission, retentionReference);

        assertEquals(submission, envelope.submission());
        assertEquals(retentionReference, envelope.retentionReference());
    }

    private static EventSubmission submission(
        String serverIdentifier, String worldIdentifier, EventPosition position, String subjectReference
    ) {
        return new EventSubmission(
            EVENT_TYPE, new PayloadGeneration(3), OCCURRED_AT, serverIdentifier,
            worldIdentifier, position, subjectReference, PAYLOAD
        );
    }
}
