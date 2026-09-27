package net.okocraft.kansokusha.paper.inspection;

import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;
import java.util.stream.IntStream;

class InspectionSessionManagerTest {

    @Test
    void testToggleIsAtomicAndReturnsNewState() {
        var sessions = new InspectionSessionManager();
        var playerId = UUID.randomUUID();

        Assertions.assertTrue(sessions.toggle(playerId));
        Assertions.assertTrue(sessions.isEnabled(playerId));
        Assertions.assertFalse(sessions.toggle(playerId));
        Assertions.assertFalse(sessions.isEnabled(playerId));

        IntStream.range(0, 1_000).parallel().forEach(ignored -> sessions.toggle(playerId));
        Assertions.assertFalse(sessions.isEnabled(playerId));
    }

    @Test
    void testPermissionRecheckClearsEnabledSession() {
        var sessions = new InspectionSessionManager();
        var playerId = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerId);
        TestSources.grant(player, InspectionSessionManager.PERMISSION);

        sessions.enable(playerId);
        Assertions.assertTrue(sessions.isEnabled(player));

        TestSources.deny(player, InspectionSessionManager.PERMISSION);
        Assertions.assertFalse(sessions.isEnabled(player));
        Assertions.assertFalse(sessions.isEnabled(playerId));
    }

}
