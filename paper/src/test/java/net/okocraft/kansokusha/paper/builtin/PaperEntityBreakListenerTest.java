package net.okocraft.kansokusha.paper.builtin;

import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class PaperEntityBreakListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID ENTITY_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174110");
    private static final UUID BREAKER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174111");

    @Test
    void testHangingBreakUsesPlayerSubjectAndHangingContext() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var target = hanging(world, ENTITY_ID, 15.5, 70, 9.5);
        var breaker = player(world);
        var source = damageSource(DamageType.PLAYER_EXPLOSION, true);
        var event = Mockito.mock(HangingBreakByEntityEvent.class);
        Mockito.when(event.getEntity()).thenReturn(target);
        Mockito.when(event.getRemover()).thenReturn(breaker);
        Mockito.when(event.getCause()).thenReturn(HangingBreakEvent.RemoveCause.EXPLOSION);
        Mockito.when(event.getDamageSource()).thenReturn(source);

        PaperListenerTestSupport.fire(listener, event);

        var submission = onlySubmission(api);
        Assertions.assertEquals(new PlayerSubject(BREAKER_ID), submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("explosion", payload.getString("cause").orElseThrow());
        Assertions.assertEquals(
            "minecraft:player_explosion",
            payload.getString("damage_type").orElseThrow()
        );
        Assertions.assertTrue(payload.getBoolean("indirect_damage").orElseThrow());
        Assertions.assertEquals(
            PaperEntityBreakListener.HANGING_SOURCE_EVENT,
            payload.getString("source_event").orElseThrow()
        );
        Assertions.assertFalse(payload.contains("hanging"));
    }

    @Test
    void testCancelledBreaksAreDropped() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var hangingEntity = hanging(world, ENTITY_ID, 3, 4, 5);
        var hangingRemover = player(world);
        var hangingSource = damageSource(DamageType.PLAYER_ATTACK, false);
        var hanging = Mockito.mock(HangingBreakByEntityEvent.class);
        Mockito.when(hanging.getEntity()).thenReturn(hangingEntity);
        Mockito.when(hanging.getRemover()).thenReturn(hangingRemover);
        Mockito.when(hanging.getCause()).thenReturn(HangingBreakEvent.RemoveCause.ENTITY);
        Mockito.when(hanging.getDamageSource()).thenReturn(hangingSource);
        Mockito.when(hanging.isCancelled()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, hanging);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static PaperEntityBreakListener listener(PaperBlockEventTestSupport.RecordingApi api) {
        return PaperEntityBreakListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static Hanging hanging(World world, UUID id, double x, double y, double z) {
        var hanging = Mockito.mock(Hanging.class);
        Mockito.when(hanging.getUniqueId()).thenReturn(id);
        Mockito.when(hanging.getType()).thenReturn(EntityType.ITEM_FRAME);
        Mockito.when(hanging.getWorld()).thenReturn(world);
        Mockito.when(hanging.getLocation()).thenReturn(new Location(world, x, y, z));
        return hanging;
    }

    private static Player player(World world) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(BREAKER_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);
        Mockito.when(player.getWorld()).thenReturn(world);
        Mockito.when(player.getLocation()).thenReturn(new Location(world, 16, 70, 10));
        return player;
    }

    private static DamageSource damageSource(DamageType type, boolean indirect) {
        var source = Mockito.mock(DamageSource.class);
        Mockito.when(source.getDamageType()).thenReturn(type);
        Mockito.when(source.isIndirect()).thenReturn(indirect);
        return source;
    }

    private static EventSubmission onlySubmission(PaperBlockEventTestSupport.RecordingApi api) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.remove();
    }
}
