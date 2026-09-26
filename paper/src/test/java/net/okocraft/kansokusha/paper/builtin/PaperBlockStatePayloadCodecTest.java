package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PaperBlockStatePayloadCodecTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
    }

    @Test
    void testBlockBreakPayloadUsesMinecraftBlockStateNbt() throws Exception {
        var state = Blocks.CAKE.defaultBlockState()
            .setValue(CakeBlock.BITES, 3);

        var payload = PaperBlockStatePayloadCodec.encodeBlockBreak(state.asBlockData());

        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockProperties(state.asBlockData()),
            PaperPayloadNbtCodec.decode(payload)
        );
    }

    @Test
    void testBlockPlacePayloadPreservesBothMinecraftStates() throws Exception {
        var replaced = Blocks.WATER.defaultBlockState();
        var placed = Blocks.OAK_LOG.defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);

        var expected = new CompoundTag();
        expected.put("replaced", NbtUtils.writeBlockState(replaced));
        expected.put("placed", PaperBlockStatePayloadCodec.blockProperties(placed.asBlockData()));

        var payload = PaperBlockStatePayloadCodec.encodeBlockPlace(
            replaced.asBlockData(),
            placed.asBlockData()
        );

        Assertions.assertEquals(
            expected,
            PaperPayloadNbtCodec.decode(payload)
        );
    }
}
