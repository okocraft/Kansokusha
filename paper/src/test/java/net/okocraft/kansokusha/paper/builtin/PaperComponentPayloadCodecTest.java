package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PaperComponentPayloadCodecTest {

    @Test
    void testComponentRoundTripsThroughAdventureJson() {
        var component = Component.text("observed", NamedTextColor.GOLD)
            .append(Component.text(" event").decorate(TextDecoration.BOLD));

        Assertions.assertEquals(
            component,
            PaperComponentPayloadCodec.decode(
                PaperComponentPayloadCodec.encode(component)
            )
        );
    }

    @Test
    void testEmptyComponentRoundTrips() {
        var empty = Component.empty();

        Assertions.assertEquals(
            empty,
            PaperComponentPayloadCodec.decode(PaperComponentPayloadCodec.encode(empty))
        );
    }

    @Test
    void testNullValuesAreRejected() {
        Assertions.assertThrows(
            NullPointerException.class,
            () -> PaperComponentPayloadCodec.encode(null)
        );
        Assertions.assertThrows(
            NullPointerException.class,
            () -> PaperComponentPayloadCodec.decode(null)
        );
    }
}
