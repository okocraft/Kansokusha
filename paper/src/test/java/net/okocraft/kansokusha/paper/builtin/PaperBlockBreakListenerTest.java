package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class PaperBlockBreakListenerTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-22T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
    }

    @Test
    void testNonCancelledBreakSubmitsBlockState() throws Exception {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.submit(Mockito.any())).thenReturn(true);

        var listener = PaperBlockBreakListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var state = Blocks.DIAMOND_ORE.defaultBlockState();
        var event = event(state.asBlockData(), false);

        PaperListenerTestSupport.fire(listener, event);

        var captor = ArgumentCaptor.forClass(EventSubmission.class);
        Mockito.verify(api).submit(captor.capture());

        var submission = captor.getValue();
        Assertions.assertEquals(PaperBlockBreakListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(SERVER_KEY, submission.serverKey());
        Assertions.assertEquals(Key.key("example", "world"), submission.worldKey());
        Assertions.assertEquals(new BlockPosition(12, 64, -7), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        Assertions.assertEquals(
            NbtUtils.writeBlockState(state),
            PaperPayloadNbtCodec.decode(submission.payload())
        );
    }

    @Test
    void testCancelledBreakIsNotSubmitted() {
        var api = Mockito.mock(KansokushaApi.class);

        var listener = PaperBlockBreakListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var event = event(Blocks.STONE.defaultBlockState().asBlockData(), true);

        PaperListenerTestSupport.fire(listener, event);

        Mockito.verify(api, Mockito.never()).submit(Mockito.any());
    }

    private static BlockBreakEvent event(
        org.bukkit.block.data.BlockData blockData,
        boolean cancelled
    ) {
        return event(blockData, cancelled, 12, 64, -7);
    }

    private static BlockBreakEvent event(
        org.bukkit.block.data.BlockData blockData,
        boolean cancelled,
        int x,
        int y,
        int z
    ) {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));

        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(y);
        Mockito.when(block.getZ()).thenReturn(z);
        Mockito.when(block.getBlockData()).thenReturn(blockData);

        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);

        var event = Mockito.mock(BlockBreakEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        return event;
    }
}
