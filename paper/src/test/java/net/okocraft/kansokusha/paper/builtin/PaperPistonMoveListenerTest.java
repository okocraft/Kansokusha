package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;

class PaperPistonMoveListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testExtendRecordsEachMovedBlockFromToStateAndPiston() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPistonMoveListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var piston = PaperBlockEventTestSupport.block(
            world, 5, 64, 5, Blocks.PISTON.defaultBlockState(), Material.PISTON
        );
        var first = PaperBlockEventTestSupport.block(
            world, 6, 64, 5, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        var second = PaperBlockEventTestSupport.block(
            world, 7, 64, 5, Blocks.SLIME_BLOCK.defaultBlockState(), Material.SLIME_BLOCK
        );
        var event = Mockito.mock(BlockPistonExtendEvent.class);
        Mockito.when(event.getBlock()).thenReturn(piston);
        Mockito.when(event.getBlocks()).thenReturn(List.of(first, second));
        Mockito.when(event.getDirection()).thenReturn(BlockFace.EAST);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(2, api.submissions.size());
        var byX = submissionsByX(api);
        Assertions.assertEquals(OCCURRED_AT, byX.get(7).occurredAt());
        Assertions.assertEquals(OCCURRED_AT, byX.get(8).occurredAt());
        assertMove(
            byX.get(7),
            new BlockPosition(6, 64, 5),
            new BlockPosition(7, 64, 5),
            Blocks.STONE.defaultBlockState().asBlockData(),
            "east",
            "extend"
        );
        assertMove(
            byX.get(8),
            new BlockPosition(7, 64, 5),
            new BlockPosition(8, 64, 5),
            Blocks.SLIME_BLOCK.defaultBlockState().asBlockData(),
            "east",
            "extend"
        );
    }

    @Test
    void testExtendExcludesBlocksDestroyedByPiston() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPistonMoveListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var piston = PaperBlockEventTestSupport.block(
            world, 20, 64, 20, Blocks.PISTON.defaultBlockState(), Material.PISTON
        );
        var moved = PaperBlockEventTestSupport.block(
            world, 21, 64, 20, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        Mockito.when(moved.getPistonMoveReaction()).thenReturn(PistonMoveReaction.MOVE);
        var broken = PaperBlockEventTestSupport.block(
            world, 22, 64, 20, Blocks.TORCH.defaultBlockState(), Material.TORCH
        );
        Mockito.when(broken.getPistonMoveReaction()).thenReturn(PistonMoveReaction.BREAK);
        var event = Mockito.mock(BlockPistonExtendEvent.class);
        Mockito.when(event.getBlock()).thenReturn(piston);
        Mockito.when(event.getBlocks()).thenReturn(List.of(moved, broken));
        Mockito.when(event.getDirection()).thenReturn(BlockFace.EAST);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(new BlockPosition(22, 64, 20), submission.position());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            PaperBlockEventTestSupport.position(new BlockPosition(21, 64, 20)),
            payload.get("from")
        );
        Assertions.assertEquals(
            PaperBlockEventTestSupport.position(new BlockPosition(22, 64, 20)),
            payload.get("to")
        );
        Mockito.verify(broken, Mockito.never()).getBlockData();
    }

    @Test
    void testRetractUsesEventMovementDirection() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPistonMoveListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var piston = PaperBlockEventTestSupport.block(
            world, 10, 64, 10, Blocks.STICKY_PISTON.defaultBlockState(), Material.STICKY_PISTON
        );
        var moved = PaperBlockEventTestSupport.block(
            world, 12, 64, 10, Blocks.IRON_BLOCK.defaultBlockState(), Material.IRON_BLOCK
        );
        var event = Mockito.mock(BlockPistonRetractEvent.class);
        Mockito.when(event.getBlock()).thenReturn(piston);
        Mockito.when(event.getBlocks()).thenReturn(List.of(moved));
        Mockito.when(event.getDirection()).thenReturn(BlockFace.WEST);

        PaperListenerTestSupport.fire(listener, event);

        var submission = api.submissions.remove();
        assertMove(
            submission,
            new BlockPosition(12, 64, 10),
            new BlockPosition(11, 64, 10),
            Blocks.IRON_BLOCK.defaultBlockState().asBlockData(),
            "west",
            "retract"
        );
    }

    @Test
    void testCancelledPistonMoveDoesNotSubmit() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPistonMoveListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var piston = PaperBlockEventTestSupport.block(
            world, 0, 64, 0, Blocks.PISTON.defaultBlockState(), Material.PISTON
        );
        var moved = PaperBlockEventTestSupport.block(
            world, 1, 64, 0, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        var event = Mockito.mock(BlockPistonExtendEvent.class);
        Mockito.when(event.getBlock()).thenReturn(piston);
        Mockito.when(event.getBlocks()).thenReturn(List.of(moved));
        Mockito.when(event.getDirection()).thenReturn(BlockFace.EAST);
        Mockito.when(event.isCancelled()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static void assertMove(
        EventSubmission submission,
        BlockPosition from,
        BlockPosition to,
        org.bukkit.block.data.BlockData state,
        String direction,
        String action
    ) throws Exception {
        Assertions.assertEquals(to, submission.position());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(PaperBlockEventTestSupport.position(from), payload.get("from"));
        Assertions.assertEquals(PaperBlockEventTestSupport.position(to), payload.get("to"));
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(state),
            payload.get("state")
        );
        Assertions.assertEquals(
            PaperBlockEventTestSupport.position(new BlockPosition(
                action.equals("extend") ? 5 : 10,
                64,
                action.equals("extend") ? 5 : 10
            )),
            payload.get("piston_origin")
        );
        Assertions.assertEquals(direction, payload.getString("direction").orElseThrow());
        Assertions.assertEquals(action, payload.getString("action").orElseThrow());
    }

    private static HashMap<Integer, EventSubmission> submissionsByX(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        var result = new HashMap<Integer, EventSubmission>();
        for (var submission : api.submissions) {
            Assertions.assertNull(result.put(submission.position().x(), submission));
        }
        return result;
    }
}
