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
    void testEnableAndDisableAreIdempotent() {
        var sessions = new InspectionSessionManager();
        var playerId = UUID.randomUUID();

        Assertions.assertTrue(sessions.enable(playerId));
        Assertions.assertFalse(sessions.enable(playerId));
        Assertions.assertTrue(sessions.isEnabled(playerId));

        Assertions.assertTrue(sessions.disable(playerId));
        Assertions.assertFalse(sessions.disable(playerId));
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

    @Test
    void testClearRemovesAllSessions() {
        var sessions = new InspectionSessionManager();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        sessions.enable(first);
        sessions.enable(second);
        sessions.clear();

        Assertions.assertFalse(sessions.isEnabled(first));
        Assertions.assertFalse(sessions.isEnabled(second));
    }
}
