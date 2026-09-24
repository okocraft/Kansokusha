package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

class PaperPlayerSessionListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174004");

    @Test
    void testSessionRecordersImplementBukkitListener() {
        var api = new PaperBlockEventTestSupport.RecordingApi();

        Assertions.assertInstanceOf(
            Listener.class,
            PaperPlayerJoinListener.register(api, PaperBlockEventTestSupport.SERVER_KEY)
        );
        Assertions.assertInstanceOf(
            Listener.class,
            PaperPlayerQuitListener.register(api, PaperBlockEventTestSupport.SERVER_KEY)
        );
        Assertions.assertInstanceOf(
            Listener.class,
            PaperPlayerKickListener.register(api, PaperBlockEventTestSupport.SERVER_KEY)
        );
    }

    @Test
    void testJoinRecordsSuccessfulBackendSessionStart() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerJoinListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var player = player(PaperBlockEventTestSupport.world(), 12.75, 64.0, 8.25);
        var event = Mockito.mock(PlayerJoinEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);

        listener.record(event);

        var submission = onlySubmission(api);
        assertCommon(
            submission,
            PaperPlayerJoinListener.EVENT_TYPE,
            new BlockPosition(12, 64, 8)
        );
        Assertions.assertEquals(new CompoundTag(), PaperPayloadNbtCodec.decode(submission.payload()));
    }

    @Test
    void testQuitRecordsFinalReadableLocationAndReason() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerQuitListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var player = player(PaperBlockEventTestSupport.world(), 20.9, 70.1, 31.4);
        var event = Mockito.mock(PlayerQuitEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getReason()).thenReturn(PlayerQuitEvent.QuitReason.TIMED_OUT);

        listener.record(event);

        var submission = onlySubmission(api);
        assertCommon(
            submission,
            PaperPlayerQuitListener.EVENT_TYPE,
            new BlockPosition(20, 70, 31)
        );
        Assertions.assertEquals(
            quitPayload(PlayerQuitEvent.QuitReason.TIMED_OUT),
            PaperPayloadNbtCodec.decode(submission.payload())
        );
    }

    @Test
    void testCancelledKickIsNotRecorded() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerKickListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var player = player(PaperBlockEventTestSupport.world(), 1, 2, 3);
        var event = new PlayerKickEvent(
            player,
            Component.text("original"),
            Component.empty(),
            PlayerKickEvent.Cause.PLUGIN
        );
        event.setCancelled(true);

        listener.record(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Mockito.verify(player, Mockito.never()).getLocation();
    }

    @Test
    void testKickRecordsFinalReasonCauseAndLocationAtMonitor() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerKickListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var player = player(PaperBlockEventTestSupport.world(), 4.9, 65.2, 6.1);
        var event = new PlayerKickEvent(
            player,
            Component.text("original"),
            Component.empty(),
            PlayerKickEvent.Cause.ILLEGAL_ACTION
        );
        var finalReason = Component.text("final reason");
        event.reason(finalReason);

        listener.record(event);

        var submission = onlySubmission(api);
        assertCommon(
            submission,
            PaperPlayerKickListener.EVENT_TYPE,
            new BlockPosition(4, 65, 6)
        );
        Assertions.assertEquals(
            kickPayload(PlayerKickEvent.Cause.ILLEGAL_ACTION, finalReason),
            PaperPayloadNbtCodec.decode(submission.payload())
        );

        var annotation = PaperPlayerKickListener.class
            .getMethod("record", PlayerKickEvent.class)
            .getAnnotation(EventHandler.class);
        Assertions.assertNotNull(annotation);
        Assertions.assertEquals(EventPriority.MONITOR, annotation.priority());
        Assertions.assertTrue(annotation.ignoreCancelled());
    }

    /**
     * This test only fixes the Kansokusha meaning when both platform callbacks occur.
     * The actual Paper 26.2 accepted-kick-to-quit sequence is verified by the real-server
     * external API integration fixture.
     */
    @Test
    void testKickAndQuitCallbacksProduceDistinctSubmissions() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var kickListener = PaperPlayerKickListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var quitListener = PaperPlayerQuitListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var player = player(PaperBlockEventTestSupport.world(), 40.2, 80.8, 50.6);
        var kickReason = Component.text("policy");
        var kick = new PlayerKickEvent(
            player,
            kickReason,
            Component.empty(),
            PlayerKickEvent.Cause.KICKED
        );
        var quit = Mockito.mock(PlayerQuitEvent.class);
        Mockito.when(quit.getPlayer()).thenReturn(player);
        Mockito.when(quit.getReason()).thenReturn(PlayerQuitEvent.QuitReason.KICKED);

        kickListener.record(kick);
        quitListener.record(quit);

        Assertions.assertEquals(2, api.submissions.size());
        var kickSubmission = api.submissions.remove();
        var quitSubmission = api.submissions.remove();

        Assertions.assertEquals(PaperPlayerKickListener.EVENT_TYPE, kickSubmission.eventType());
        Assertions.assertEquals(PaperPlayerQuitListener.EVENT_TYPE, quitSubmission.eventType());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), kickSubmission.subject());
        Assertions.assertEquals(kickSubmission.subject(), quitSubmission.subject());
        Assertions.assertEquals(kickSubmission.worldKey(), quitSubmission.worldKey());
        Assertions.assertEquals(kickSubmission.position(), quitSubmission.position());
        Assertions.assertEquals(
            kickPayload(PlayerKickEvent.Cause.KICKED, kickReason),
            PaperPayloadNbtCodec.decode(kickSubmission.payload())
        );
        Assertions.assertEquals(
            quitPayload(PlayerQuitEvent.QuitReason.KICKED),
            PaperPayloadNbtCodec.decode(quitSubmission.payload())
        );
    }

    private static Clock fixedClock() {
        return Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
    }

    private static Player player(World world, double x, double y, double z) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getLocation()).thenReturn(new Location(world, x, y, z));
        return player;
    }

    private static EventSubmission onlySubmission(PaperBlockEventTestSupport.RecordingApi api) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.remove();
    }

    private static void assertCommon(
        EventSubmission submission,
        net.kyori.adventure.key.Key eventType,
        BlockPosition position
    ) {
        Assertions.assertEquals(eventType, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(PaperBlockEventTestSupport.SERVER_KEY, submission.serverKey());
        Assertions.assertEquals(net.kyori.adventure.key.Key.key("example", "world"), submission.worldKey());
        Assertions.assertEquals(position, submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
    }

    private static CompoundTag quitPayload(PlayerQuitEvent.QuitReason reason) {
        var payload = new CompoundTag();
        payload.putString("reason", reason.name().toLowerCase(Locale.ROOT));
        return payload;
    }

    private static CompoundTag kickPayload(PlayerKickEvent.Cause cause, Component reason) {
        var payload = new CompoundTag();
        payload.putString("cause", cause.name().toLowerCase(Locale.ROOT));
        payload.putString("reason", PaperComponentPayloadCodec.encode(reason));
        return payload;
    }
}
