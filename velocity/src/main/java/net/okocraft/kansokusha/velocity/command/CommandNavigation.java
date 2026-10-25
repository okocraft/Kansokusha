package net.okocraft.kansokusha.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.okocraft.kansokusha.common.command.SearchNavigation;

public final class CommandNavigation {
    static Object key(CommandSource source) {
        return source instanceof Player player ? player.getUniqueId() : "velocity-console";
    }

    static void remember(CommandSource source, String command) {
        if (!(source instanceof Player player) || player.isActive()) SearchNavigation.remember(key(source), command);
    }

    @Subscribe
    public void disconnect(DisconnectEvent event) { SearchNavigation.forget(event.getPlayer().getUniqueId()); }
}
