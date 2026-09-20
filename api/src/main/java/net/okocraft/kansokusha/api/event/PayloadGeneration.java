package net.okocraft.kansokusha.api.event;

public record PayloadGeneration(int value) {

    public static final PayloadGeneration FIRST = new PayloadGeneration(1);

    public PayloadGeneration {
        if (value < 1) {
            throw new IllegalArgumentException("Payload generation must be positive");
        }
    }
}
