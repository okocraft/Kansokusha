package net.okocraft.kansokusha.paper.builtin;

import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface PaperInFlightListener extends Listener {

    void clearInFlightState();
}
