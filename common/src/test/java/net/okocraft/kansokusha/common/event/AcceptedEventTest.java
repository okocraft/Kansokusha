package net.okocraft.kansokusha.common.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AcceptedEventTest {

    private static final Key EVENT_TYPE = Key.key("test", "block-break");
    private static final Key SERVER_KEY = Key.key("test", "survival-1");
    private static final Key SUBJECT_KEY = Key.key("test", "player-123");
    private static final Key RETENTION_POLICY_KEY = Key.key("test", "default-retention");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05Z");
    private static final EventPayload PAYLOAD = EventPayload.copyOf(new byte[]{10, 20});

    @Test
    void testAcceptedEventRetainsSubmissionAndRetentionPolicyKey() {
        EventSubmission submission = submission();

        AcceptedEvent acceptedEvent = new AcceptedEvent(submission, RETENTION_POLICY_KEY);

        assertEquals(submission, acceptedEvent.submission());
        assertEquals(RETENTION_POLICY_KEY, acceptedEvent.retentionPolicyKey());
    }

    @Test
    void testNullRequiredValuesAreRejected() {
        EventSubmission submission = submission();

        assertThrows(NullPointerException.class,
            () -> new AcceptedEvent(null, RETENTION_POLICY_KEY));
        assertThrows(NullPointerException.class,
            () -> new AcceptedEvent(submission, null));
    }

    private static EventSubmission submission() {
        return new EventSubmission(
            EVENT_TYPE, PayloadGeneration.FIRST, OCCURRED_AT, SERVER_KEY,
            null, null, SUBJECT_KEY, PAYLOAD
        );
    }
}
