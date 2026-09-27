package net.okocraft.kansokusha.paper.inspection;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@NotNullByDefault
public final class InspectionSessionListener implements Listener {

    private final InspectionSessionManager sessions;

    public InspectionSessionListener(InspectionSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public static void register(Plugin plugin, InspectionSessionManager sessions) {
        Objects.requireNonNull(plugin, "plugin").getServer().getPluginManager().registerEvents(
            new InspectionSessionListener(sessions),
            plugin
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void cleanup(PlayerQuitEvent event) {
        this.sessions.disable(Objects.requireNonNull(event, "event").getPlayer().getUniqueId());
    }
}
