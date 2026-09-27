package net.okocraft.kansokusha.paper.builtin;

import net.okocraft.kansokusha.paper.inspection.InspectionInteractionListener;
import net.okocraft.kansokusha.paper.inspection.InspectionSessionManager;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

class InspectionMutationLogIsolationTest {

    @Test
    void testInspectionCancelledBreakDoesNotReachBuiltInMutationLog() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var builtIn = PaperBlockBreakListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC)
        );

        var sessions = new InspectionSessionManager();
        var playerId = UUID.fromString("123e4567-e89b-12d3-a456-426614174101");
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerId);
        TestSources.grant(player, InspectionSessionManager.PERMISSION);
        sessions.enable(playerId);

        var cancelled = new AtomicBoolean();
        var event = Mockito.mock(BlockBreakEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.isCancelled()).thenAnswer(ignored -> cancelled.get());
        Mockito.doAnswer(invocation -> {
            cancelled.set(invocation.getArgument(0));
            return null;
        }).when(event).setCancelled(Mockito.anyBoolean());

        var inspection = new InspectionInteractionListener(sessions, (ignoredPlayer, ignoredTarget) -> {
        });
        inspection.suppressBreak(event);
        PaperListenerTestSupport.fire(builtIn, event);

        Assertions.assertTrue(cancelled.get());
        Assertions.assertTrue(api.submissions.isEmpty());
    }
}
