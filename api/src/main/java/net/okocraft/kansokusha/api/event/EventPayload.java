package net.okocraft.kansokusha.api.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

    /**
     * Creates a payload that takes ownership of {@code bytes} without copying it.
     *
     * <p>The caller must not access or modify the array after this call.</p>
     */
    @ApiStatus.Internal
    public static EventPayload takeOwnership(byte[] bytes) {
        return new EventPayload(Objects.requireNonNull(bytes, "bytes"));
    }

    public int size() {
        return this.bytes.length;
    }

    public byte[] copyBytes() {
        return this.bytes.clone();
    }

    /**
     * Opens a read-only stream over this payload without copying its bytes.
     */
    public InputStream openStream() {
        return new ByteArrayInputStream(this.bytes);
    }

    /**
     * Returns the backing byte array without copying it.
     *
     * <p>The returned array must never be modified.</p>
     */
    @ApiStatus.Internal
    public byte[] unsafeBytes() {
        return this.bytes;
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
