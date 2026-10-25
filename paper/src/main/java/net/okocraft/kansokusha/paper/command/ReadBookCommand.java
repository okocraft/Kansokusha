package net.okocraft.kansokusha.paper.command;

import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.paper.builtin.PaperRecordedItems;
import net.okocraft.kansokusha.paper.builtin.PaperViewMessages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
final class ReadBookCommand {

    static boolean execute(EventSearchBackend backend, Plugin plugin, CommandSender sender, String id, String path) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PaperViewMessages.PLAYER_ONLY.asComponent());
            return false;
        }
        return EventCommandSupport.lookup(backend, id, sender::hasPermission, sender::sendMessage, detail ->
            player.getScheduler().execute(plugin, () -> {
                if (!player.hasPermission(EventCommandSupport.PERMISSION)
                    || !player.hasPermission(SearchCommandSupport.eventPermission(detail.event().eventType()))) {
                    return;
                }
                try {
                    player.openBook(PaperRecordedItems.book(detail.event(), path));
                } catch (IOException | RuntimeException failure) {
                    player.sendMessage(PaperViewMessages.BOOK_UNAVAILABLE.asComponent());
                }
            }, null, 1)
        );
    }

    private ReadBookCommand() {
    }
}
