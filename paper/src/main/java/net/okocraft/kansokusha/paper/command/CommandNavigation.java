package net.okocraft.kansokusha.paper.command;

import net.okocraft.kansokusha.common.command.SearchNavigation;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

public final class CommandNavigation implements Listener {
    static @Nullable Object key(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId()
            : sender instanceof ConsoleCommandSender ? "paper-console" : null;
    }

    static void remember(CommandSender sender, String command) {
        if (!(sender instanceof Player player) || player.isOnline()) SearchNavigation.remember(key(sender), command);
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) { SearchNavigation.forget(event.getPlayer().getUniqueId()); }
}
