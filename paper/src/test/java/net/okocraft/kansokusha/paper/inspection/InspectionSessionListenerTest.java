package net.okocraft.kansokusha.paper.inspection;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

class InspectionSessionListenerTest {

    @Test
    void testQuitDisablesInspectionForPlayer() {
        var sessions = new InspectionSessionManager();
        var playerId = UUID.randomUUID();
        sessions.enable(playerId);

        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerId);
        var event = Mockito.mock(PlayerQuitEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);

        new InspectionSessionListener(sessions).cleanup(event);

        Assertions.assertFalse(sessions.isEnabled(playerId));
    }
}
