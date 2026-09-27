package net.okocraft.kansokusha.paper.inspection;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class InspectionSearchOutput {

    static String positionText(InspectionTarget target) {
        return target.worldKey().asString() + " " + target.x() + " " + target.y() + " " + target.z();
    }

    static Component fullHistory(InspectionTarget target) {
        return InspectionSearchMessages.VIEW_FULL.asComponent().clickEvent(
            ClickEvent.runCommand("/kansokusha search position " + positionText(target))
        );
    }

    private InspectionSearchOutput() {
        throw new UnsupportedOperationException();
    }
}
