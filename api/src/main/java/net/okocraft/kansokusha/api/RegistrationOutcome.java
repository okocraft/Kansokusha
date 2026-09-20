package net.okocraft.kansokusha.api;

/**
 * Result of registering an event type for the current runtime.
 */
public enum RegistrationOutcome {
    REGISTERED,
    ALREADY_REGISTERED,
    CONFLICT,
    CLOSED
}
