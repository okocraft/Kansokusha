package net.okocraft.kansokusha.api.event;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.Objects;

@NotNullByDefault
public final class OpaquePayload {

    private final byte[] bytes;

    private OpaquePayload(byte[] bytes) {
        this.bytes = bytes;
    }

    public static OpaquePayload copyOf(byte[] bytes) {
        return new OpaquePayload(Objects.requireNonNull(bytes, "bytes").clone());
    }

    public int size() {
        return this.bytes.length;
    }

    public byte[] copyBytes() {
        return this.bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof OpaquePayload payload && Arrays.equals(this.bytes, payload.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.bytes);
    }

    @Override
    public String toString() {
        return "OpaquePayload[size=" + this.bytes.length + ']';
    }
}
