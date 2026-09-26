package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

class PaperSignChangeListenerTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testNonCancelledSignChangeSubmitsBeforeAndAfterLines() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var before = new ArrayList<Component>(List.of(
            Component.text("before-0"),
            Component.text("before-1"),
            Component.text("before-2"),
            Component.text("before-3")
        ));
        var after = new ArrayList<Component>(List.of(
            Component.text("draft-0"),
            Component.text("draft-1"),
            Component.text("draft-2"),
            Component.text("draft-3")
        ));
        var fixture = event(12, before, after, false);

        PaperListenerTestSupport.fire(listener, fixture.event());

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperSignChangeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(12, submission.position().x());
        Assertions.assertEquals(new PlayerActor(PLAYER_ID), submission.actor());
        Assertions.assertEquals(Key.key("minecraft", "oak_sign"), submission.targetType());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("front", string(payload, "side"));
        Assertions.assertEquals(
            Component.text("before-0"),
            decodedLine(payload, "before", 0)
        );
        Assertions.assertEquals(
            Component.text("draft-0"),
            decodedLine(payload, "after", 0)
        );
    }

    @Test
    void testCancelledSignChangeIsNotSubmitted() {
        var api = new RecordingApi();
        var listener = listener(api);
        var fixture = event(
            1,
            new ArrayList<>(fourLines("before")),
            new ArrayList<>(fourLines("after")),
            true
        );

        PaperListenerTestSupport.fire(listener, fixture.event());

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static PaperSignChangeListener listener(RecordingApi api) {
        return PaperSignChangeListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static Fixture event(
        int x,
        ArrayList<Component> before,
        ArrayList<Component> after,
        boolean cancelled
    ) {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(64);
        Mockito.when(block.getZ()).thenReturn(-x);
        var blockData = Mockito.mock(BlockData.class);
        Mockito.when(blockData.getMaterial()).thenReturn(Material.OAK_SIGN);
        Mockito.when(block.getBlockData()).thenReturn(blockData);

        var sign = Mockito.mock(Sign.class);
        var signSide = Mockito.mock(SignSide.class);
        Mockito.when(sign.getSide(Side.FRONT)).thenReturn(signSide);
        Mockito.when(signSide.lines()).thenReturn(before);
        Mockito.when(block.getState()).thenReturn(sign);

        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        var event = Mockito.mock(SignChangeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getSide()).thenReturn(Side.FRONT);
        Mockito.when(event.lines()).thenReturn(after);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        return new Fixture(event, block);
    }

    private static List<Component> fourLines(String prefix) {
        return List.of(
            Component.text(prefix + "-0"),
            Component.text(prefix + "-1"),
            Component.text(prefix + "-2"),
            Component.text(prefix + "-3")
        );
    }

    private static Component decodedLine(CompoundTag payload, String key, int index) {
        var entry = (CompoundTag) payload.getListOrEmpty(key).get(index);
        Optional<String> serialized = entry.getString("component");
        return serialized.map(PaperComponentPayloadCodec::decode).orElse(null);
    }

    private static String string(CompoundTag tag, String key) {
        return tag.getString(key).orElseThrow();
    }

    private static EventSubmission onlySubmission(RecordingApi api) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.element();
    }

    private record Fixture(SignChangeEvent event, Block block) {
    }

    private static final class RecordingApi implements KansokushaApi {

        private final ConcurrentLinkedQueue<EventSubmission> submissions =
            new ConcurrentLinkedQueue<>();

        @Override
        public Optional<Key> localServerKey() {
            return Optional.of(SERVER_KEY);
        }

        @Override
        public void registerEventType(EventTypeDefinition definition) {
        }

        @Override
        public boolean submit(EventSubmission submission) {
            this.submissions.add(submission);
            return true;
        }
    }
}
