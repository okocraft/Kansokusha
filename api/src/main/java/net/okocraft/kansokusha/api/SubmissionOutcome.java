package net.okocraft.kansokusha.api;

/**
 * Result of handing an event to Kansokusha's ingestion boundary.
 *
 * <p>{@link #ACCEPTED} means that ingestion took ownership of the event. It
 * does not guarantee that the event has already been persisted.</p>
 */
public enum SubmissionOutcome {
    ACCEPTED,
    UNREGISTERED_EVENT_TYPE,
    PAYLOAD_GENERATION_MISMATCH,
    CLOSED,
    INGESTION_UNAVAILABLE
}
