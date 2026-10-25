package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;

class SearchGuideTest {
    @Test
    void testRangeAndCategoryExamplesFollowCapabilities() {
        var player = new ArrayList<Component>();
        SearchGuide.send(permission -> permission.endsWith("block_break"), player::add, true);
        Assertions.assertTrue(player.stream().anyMatch(line -> line.clickEvent() != null
            && line.clickEvent().action() == ClickEvent.Action.SUGGEST_COMMAND
            && line.clickEvent().value().contains("radius 10")));
        Assertions.assertFalse(player.stream().anyMatch(line -> line.clickEvent() != null
            && line.clickEvent().value().contains("action container")));
        var console = new ArrayList<Component>();
        SearchGuide.send(permission -> true, console::add, false);
        Assertions.assertFalse(console.stream().anyMatch(line -> line.clickEvent() != null
            && line.clickEvent().value().contains("radius")));
        Assertions.assertTrue(console.stream().anyMatch(line -> line.clickEvent() != null
            && line.clickEvent().value().contains("around minecraft:overworld")));
    }
}
