package net.okocraft.kansokusha.paper.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.command.EventLocation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.util.Optional;

class PaperEventLocationsTest {
    @Test
    void testOnlyCoordinateReceivesTeleportAndWorldRetainsFullKeyHover() {
        var location = new EventLocation(Optional.of(Key.key("example:overworld")),
            Optional.of(new BlockPosition(1, 64, 2)), Optional.empty());
        var expected = ClickEvent.runCommand("/kansokusha teleport example:overworld 1 64 2");
        Assertions.assertTrue(hasClick(PaperEventLocations.format(location, true, true), expected));
        Assertions.assertFalse(hasClick(PaperEventLocations.format(location, true, false), expected));
        var row = PaperEventLocations.format(location, true, true);
        Assertions.assertNull(row.clickEvent());
        Assertions.assertTrue(row.toString().contains("example:overworld"));
    }
    private static boolean hasClick(Component component, ClickEvent<?> expected) {
        return expected.equals(component.clickEvent()) || component.children().stream().anyMatch(child -> hasClick(child, expected));
    }
}
