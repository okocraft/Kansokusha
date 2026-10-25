package net.okocraft.kansokusha.paper.builtin;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.inventory.CraftItemFactory;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class PaperItemViewTestSupport {

    static MockedStatic<Bukkit> install() {
        var server = Mockito.mock(Server.class);
        Mockito.when(server.getItemFactory()).thenReturn(CraftItemFactory.instance());
        var mocked = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS);
        mocked.when(Bukkit::getServer).thenReturn(server);
        return mocked;
    }

    private PaperItemViewTestSupport() {
    }
}
