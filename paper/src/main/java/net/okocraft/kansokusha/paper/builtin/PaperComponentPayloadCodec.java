package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperComponentPayloadCodec {

    private static final GsonComponentSerializer SERIALIZER = GsonComponentSerializer.gson();

    private PaperComponentPayloadCodec() {
    }

    public static String encode(Component component) {
        return SERIALIZER.serialize(Objects.requireNonNull(component, "component"));
    }

    public static Component decode(String serialized) {
        return SERIALIZER.deserialize(Objects.requireNonNull(serialized, "serialized"));
    }
}
