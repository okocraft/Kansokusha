package net.okocraft.kansokusha.paper.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.common.command.EventLocation;

public final class PaperEventLocations {
    public static Component format(EventLocation location, boolean showWorld, boolean teleport) {
        Component result = Component.empty();
        if (showWorld) {
            result = location.world().map(key -> (Component) Component.text(key.value())
                .hoverEvent(HoverEvent.showText(Component.text(key.asString()))))
                .orElseGet(() -> location.server().map(key -> (Component) Component.text(key.asString())).orElse(Component.empty()));
        }
        if (location.position().isPresent()) {
            var point = location.position().get();
            Component coordinate = Component.text("(" + point.x() + "," + point.y() + "," + point.z() + ")");
            if (teleport && location.world().isPresent()) {
                coordinate = coordinate.clickEvent(ClickEvent.runCommand("/kansokusha teleport " + location.world().get().asString()
                    + " " + point.x() + " " + point.y() + " " + point.z()))
                    .hoverEvent(HoverEvent.showText(TeleportMessages.HOVER.asComponent()));
            }
            if (!result.equals(Component.empty())) result = result.append(Component.space());
            result = result.append(coordinate);
        }
        return result;
    }
    private PaperEventLocations() { }
}
