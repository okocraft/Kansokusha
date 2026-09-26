package net.okocraft.kansokusha.api.event;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

@NotNullByDefault
public final class EventPayload {

    private final byte[] bytes;
    private final Optional<Key> retentionQualifier;

    private EventPayload(byte[] bytes, Optional<Key> retentionQualifier) {
        this.bytes = bytes;
        this.retentionQualifier = retentionQualifier;
    }

    public static EventPayload copyOf(byte[] bytes) {
        return new EventPayload(
            Objects.requireNonNull(bytes, "bytes").clone(),
            Optional.empty()
        );
    }

    /**
     * Returns a copy carrying a transient semantic qualifier for operator-configured retention.
     *
     * <p>Event providers may use this when one event type needs retention classification based on
     * event-specific semantics. The qualifier is not part of the opaque payload bytes, is not
     * persisted, and does not name or select a retention policy by itself.</p>
     *
     * @param qualifier provider-defined semantic classification key
     * @return a payload with the same opaque bytes and the supplied retention qualifier
     */
    public EventPayload withRetentionQualifier(Key qualifier) {
        return new EventPayload(
            this.bytes.clone(),
            Optional.of(Objects.requireNonNull(qualifier, "qualifier"))
        );
    }

    @ApiStatus.Internal
    public Optional<Key> retentionQualifier() {
        return this.retentionQualifier;
    }

    public int size() {
        return this.bytes.length;
    }

    public byte[] copyBytes() {
        return this.bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EventPayload payload && Arrays.equals(this.bytes, payload.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.bytes);
    }

    @Override
    public String toString() {
        return "EventPayload[size=" + this.bytes.length + ']';
    }
}
