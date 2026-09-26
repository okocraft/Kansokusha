package net.okocraft.kansokusha.api.event;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.Objects;

/**
 * Provider-defined opaque payload bytes.
 */
@NotNullByDefault
public final class EventPayload {

    private final byte[] bytes;

    private EventPayload(byte[] bytes) {
        this.bytes = bytes;
    }

    public static EventPayload copyOf(byte[] bytes) {
        return new EventPayload(Objects.requireNonNull(bytes, "bytes").clone());
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
