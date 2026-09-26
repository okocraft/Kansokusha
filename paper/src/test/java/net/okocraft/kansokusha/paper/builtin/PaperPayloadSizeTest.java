package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.Direction;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class PaperPayloadSizeTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testRepresentativePayloadSizesAgainstLegacyNbt() throws Exception {
        var samples = samples();

        long legacyTotal = 0;
        long compactTotal = 0;
        for (var sample : samples) {
            var legacy = legacyNbtSize(sample.legacy());
            var compact = sample.compact().copyBytes().length;
            legacyTotal += legacy;
            compactTotal += compact;

            Assertions.assertTrue(
                compact < legacy,
                () -> sample.name() + " did not shrink: legacy=" + legacy + ", compact=" + compact
            );
            System.out.printf(
                "payload-size %s legacy=%d compact=%d reduction=%.1f%%%n",
                sample.name(),
                legacy,
                compact,
                reductionPercent(legacy, compact)
            );
        }

        var totalReduction = reductionPercent(legacyTotal, compactTotal);
        System.out.printf(
            "payload-size total legacy=%d compact=%d reduction=%.1f%%%n",
            legacyTotal,
            compactTotal,
            totalReduction
        );
        Assertions.assertTrue(
            compactTotal * 100 <= legacyTotal * 70,
            "Expected representative total reduction >= 30%, legacy="
                + legacyTotal + ", compact=" + compactTotal
        );
    }

    private static List<Sample> samples() throws Exception {
        var result = new ArrayList<Sample>();

        add(
            result,
            "block_break",
            PaperBlockStatePayloadCodec.encodeBlockBreak(
                Blocks.CAKE.defaultBlockState()
                    .setValue(CakeBlock.BITES, 3)
                    .asBlockData()
            )
        );

        add(
            result,
            "block_place",
            PaperBlockStatePayloadCodec.encodeBlockPlace(
                Blocks.WATER.defaultBlockState().asBlockData(),
                Blocks.OAK_LOG.defaultBlockState()
                    .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X)
                    .asBlockData()
            )
        );

        add(
            result,
            "natural_block_change",
            PaperBlockEventPayloadCodec.encodeNaturalChange(
                Blocks.ICE.defaultBlockState().asBlockData(),
                Blocks.WATER.defaultBlockState().asBlockData(),
                "block_fade",
                null,
                null
            )
        );

        add(
            result,
            "explosion_block_change",
            PaperWorldMutationPayloadCodec.encodeExplosionBlockChange(
                Blocks.STONE.defaultBlockState().asBlockData(),
                "entity",
                10.5,
                64.5,
                -2.5,
                null,
                null,
                null,
                PaperEntityAttribution.capture(null)
            )
        );

        var from = new PaperPlayerStatePayloadCodec.LocationSnapshot(
            Key.key("minecraft", "overworld"),
            new BlockPosition(10, 64, -10),
            10.25,
            64.0,
            -9.75,
            90.0F,
            10.0F
        );
        var to = new PaperPlayerStatePayloadCodec.LocationSnapshot(
            Key.key("minecraft", "the_nether"),
            new BlockPosition(80, 70, -80),
            80.5,
            70.0,
            -79.5,
            180.0F,
            -5.0F
        );
        add(
            result,
            "player_teleport",
            PaperPlayerStatePayloadCodec.encodeTeleport(
                from,
                to,
                "ender_pearl",
                Set.of("x", "y_rot")
            )
        );

        add(
            result,
            "player_death",
            PaperPlayerStatePayloadCodec.encodeDeath(
                Component.text("Example player was slain by Zombie"),
                new PaperPlayerStatePayloadCodec.KillerSnapshot(
                    "123e4567-e89b-12d3-a456-426614174100",
                    "minecraft:zombie",
                    false
                ),
                "ENTITY_ATTACK",
                7,
                3,
                103,
                5,
                false,
                false
            )
        );

        var sourceTag = inventory(
            "hopper",
            5,
            "block",
            "minecraft:hopper",
            "minecraft:overworld",
            10.5,
            64.0,
            10.5
        );
        var destinationTag = inventory(
            "chest",
            27,
            "block",
            "minecraft:chest",
            "minecraft:overworld",
            11.5,
            64.0,
            10.5
        );
        var source = new PaperContainerPayloadCodec.InventorySnapshot(
            sourceTag,
            Key.key("minecraft", "overworld"),
            new BlockPosition(10, 64, 10),
            null
        );
        var destination = new PaperContainerPayloadCodec.InventorySnapshot(
            destinationTag,
            Key.key("minecraft", "overworld"),
            new BlockPosition(11, 64, 10),
            null
        );
        var item = PaperItemStackPayloadCodec.encode(ItemStack.of(Material.DIAMOND, 2));
        var transfer = PaperContainerPayloadCodec.encodeTransfer(
            source,
            destination,
            source,
            "source",
            item
        );
        var legacyTransfer = PaperPayloadNbtCodec.decode(transfer);
        legacyTransfer.putString("semantics", "non_cancelled_automated_transfer_attempt");
        legacyTransfer.put("initiator_inventory", sourceTag.copy());
        legacyTransfer.putString("transfer_direction", "push");
        result.add(new Sample("container_transfer", legacyTransfer, transfer));

        var itemSnapshot = PaperItemStackPayloadCodec.encode(ItemStack.of(Material.EMERALD, 3));
        add(
            result,
            "item_drop",
            PaperPlayerItemAuditPayloadCodec.encodeItemEntityChange(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174101"),
                itemSnapshot,
                100.25,
                65.0,
                -20.75
            )
        );
        add(
            result,
            "item_pickup",
            PaperPlayerItemAuditPayloadCodec.encodeItemEntityChange(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174102"),
                itemSnapshot,
                100.25,
                65.0,
                -20.75,
                1
            )
        );

        add(
            result,
            "chat",
            PaperCommunicationPayloadCodec.encodeChat(
                Component.text("Hello from Kansokusha payload size test")
            )
        );
        add(
            result,
            "command",
            PaperCommunicationPayloadCodec.encodePlayerCommand(
                "/give @s minecraft:diamond 64"
            )
        );

        return result;
    }

    private static CompoundTag inventory(
        String type,
        int size,
        String holderKind,
        String holderBlockType,
        String world,
        double x,
        double y,
        double z
    ) {
        var result = new CompoundTag();
        result.putString("type", type);
        result.putInt("size", size);
        result.putString("holder_kind", holderKind);
        result.putString("holder_block_type", holderBlockType);

        var location = new CompoundTag();
        location.putString("world", world);
        location.putDouble("x", x);
        location.putDouble("y", y);
        location.putDouble("z", z);
        result.put("location", location);
        return result;
    }

    private static void add(List<Sample> samples, String name, EventPayload payload) throws Exception {
        samples.add(new Sample(name, PaperPayloadNbtCodec.decode(payload), payload));
    }

    private static int legacyNbtSize(CompoundTag tag) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
        }
        return bytes.size();
    }

    private static double reductionPercent(long before, long after) {
        return (before - after) * 100.0 / before;
    }

    private record Sample(String name, CompoundTag legacy, EventPayload compact) {
    }
}
