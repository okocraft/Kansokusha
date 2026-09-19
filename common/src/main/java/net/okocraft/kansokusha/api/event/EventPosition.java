package net.okocraft.kansokusha.api.event;

public record EventPosition(double x, double y, double z) {

    public EventPosition {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Position coordinates must be finite");
        }
    }
}
