package net.okocraft.kansokusha.paper.testsupport;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.mockito.Mockito;

/**
 * Creates Paper command sources with explicit permission state.
 */
public final class TestSources {

    public static CommandSourceStack of(Player player) {
        return of(player, player);
    }

    public static CommandSourceStack ofSenderOnly(CommandSender sender) {
        return of(sender, null);
    }

    public static CommandSourceStack of(CommandSender sender, Player executor) {
        CommandSourceStack source = Mockito.mock(CommandSourceStack.class);
        Mockito.when(source.getSender()).thenReturn(sender);
        Mockito.when(source.getExecutor()).thenReturn(executor);
        return source;
    }

    public static void grant(Permissible permissible, String... permissions) {
        for (String permission : permissions) {
            Mockito.when(permissible.isPermissionSet(permission)).thenReturn(true);
            Mockito.when(permissible.hasPermission(permission)).thenReturn(true);
        }
    }

    public static void deny(Permissible permissible, String... permissions) {
        for (String permission : permissions) {
            Mockito.when(permissible.isPermissionSet(permission)).thenReturn(true);
            Mockito.when(permissible.hasPermission(permission)).thenReturn(false);
        }
    }

    private TestSources() {
        throw new UnsupportedOperationException();
    }
}
