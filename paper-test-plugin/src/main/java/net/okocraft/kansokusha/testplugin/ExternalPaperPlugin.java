package net.okocraft.kansokusha.testplugin;

import com.mojang.authlib.GameProfile;
import io.papermc.paper.event.block.PlayerShearBlockEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public final class ExternalPaperPlugin extends JavaPlugin {

    private static final NamespacedKey EVENT_TYPE =
        new NamespacedKey("fixture", "custom_event");

    private Path resultFile;

    @Override
    public void onEnable() {
        this.resultFile = Path.of(System.getProperty("kansokusha.external-api-fixture.result"));

        try {
            var result = registerAndSubmit();
            verifyBuiltInPlatformSemantics();
            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> verifyAfterShutdown(result), "kansokusha-external-api-fixture")
            );
        } catch (Throwable failure) {
            writeFailure(failure);
        } finally {
            Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
        }
    }

    private Result registerAndSubmit() {
        var api = Kansokusha.api();
        var definition = PaperKansokusha.eventType(EVENT_TYPE, PayloadGeneration.FIRST);
        if (api.registerEventType(definition) != RegistrationOutcome.REGISTERED) {
            throw new AssertionError("External event type registration did not succeed.");
        }

        var submission = new EventSubmission(
            PaperKansokusha.key(EVENT_TYPE),
            PayloadGeneration.FIRST,
            Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MILLIS),
            api.localServerKey().orElseThrow(),
            null,
            null,
            null,
            EventPayload.copyOf(new byte[]{1, 2, 3})
        );

        if (api.submit(submission) != SubmissionOutcome.ACCEPTED) {
            throw new AssertionError("External event submission was not accepted.");
        }
        if (!EVENT_TYPE.equals(PaperKansokusha.namespacedKey(submission.eventType()))) {
            throw new AssertionError("NamespacedKey conversion was not lossless.");
        }

        return new Result(api, definition, submission);
    }

    private void verifyBuiltInPlatformSemantics() {
        var craftServer = (CraftServer) this.getServer();
        var level = ((CraftWorld) Bukkit.getWorlds().getFirst()).getHandle();
        var player = new ServerPlayer(
            craftServer.getServer(),
            level,
            new GameProfile(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174001"),
                "KansokushaFixture"
            ),
            ClientInformation.createDefault()
        );
        var probe = new PlatformEventProbe();
        this.getServer().getPluginManager().registerEvents(probe, this);

        var base = new BlockPos(128, 80, 128);
        player.setPos(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);

        var breakPos = base;
        level.setBlockAndUpdate(breakPos, Blocks.STONE.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var beforeBreak = probe.snapshot();
        if (!player.gameMode.destroyBlock(breakPos)) {
            throw new AssertionError("Fixture normal block break did not succeed.");
        }
        probe.assertDelta("normal break", beforeBreak, 1, 0, 0);

        var harvestPos = base.offset(1, 0, 0);
        level.setBlockAndUpdate(
            harvestPos,
            Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                .setValue(SweetBerryBushBlock.AGE, SweetBerryBushBlock.MAX_AGE)
        );
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var beforeHarvest = probe.snapshot();
        player.gameMode.useItemOn(
            player,
            level,
            ItemStack.EMPTY,
            InteractionHand.MAIN_HAND,
            hit(harvestPos)
        );
        probe.assertDelta("berry harvest", beforeHarvest, 0, 1, 0);

        var shearPos = base.offset(2, 0, 0);
        level.setBlockAndUpdate(shearPos, Blocks.PUMPKIN.defaultBlockState());
        var shears = new ItemStack(Items.SHEARS);
        player.setItemInHand(InteractionHand.MAIN_HAND, shears);
        var beforeShear = probe.snapshot();
        player.gameMode.useItemOn(
            player,
            level,
            shears,
            InteractionHand.MAIN_HAND,
            hit(shearPos)
        );
        probe.assertDelta("pumpkin shear", beforeShear, 0, 0, 1);
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(
            Vec3.atCenterOf(pos),
            Direction.UP,
            pos,
            false
        );
    }

    private void verifyAfterShutdown(Result result) {
        try {
            try {
                Kansokusha.api();
                throw new AssertionError("Kansokusha.api() remained available after shutdown.");
            } catch (IllegalStateException expected) {
            }

            if (result.api().submit(result.submission()) != SubmissionOutcome.CLOSED) {
                throw new AssertionError("Previously acquired API did not return CLOSED after shutdown.");
            }
            if (result.api().registerEventType(result.definition()) != RegistrationOutcome.CLOSED) {
                throw new AssertionError("Previously acquired API registration did not return CLOSED after shutdown.");
            }

            Files.writeString(this.resultFile, "success");
        } catch (Throwable failure) {
            writeFailure(failure);
        }
    }

    private void writeFailure(Throwable failure) {
        try {
            Files.createDirectories(this.resultFile.getParent());
            var stackTrace = new StringWriter();
            failure.printStackTrace(new PrintWriter(stackTrace));
            Files.writeString(this.resultFile, "failure\n" + stackTrace);
        } catch (Exception writeFailure) {
            failure.addSuppressed(writeFailure);
            failure.printStackTrace();
        }
    }

    private record Result(
        KansokushaApi api,
        net.okocraft.kansokusha.api.event.EventTypeDefinition definition,
        EventSubmission submission
    ) {
    }

    private static final class PlatformEventProbe implements Listener {

        private int blockBreak;
        private int harvest;
        private int shear;

        @EventHandler
        public void onBlockBreak(BlockBreakEvent event) {
            this.blockBreak++;
        }

        @EventHandler
        public void onHarvest(PlayerHarvestBlockEvent event) {
            this.harvest++;
        }

        @EventHandler
        public void onShear(PlayerShearBlockEvent event) {
            this.shear++;
        }

        Snapshot snapshot() {
            return new Snapshot(this.blockBreak, this.harvest, this.shear);
        }

        void assertDelta(
            String action,
            Snapshot before,
            int expectedBlockBreak,
            int expectedHarvest,
            int expectedShear
        ) {
            var actualBlockBreak = this.blockBreak - before.blockBreak();
            var actualHarvest = this.harvest - before.harvest();
            var actualShear = this.shear - before.shear();
            if (
                actualBlockBreak != expectedBlockBreak
                    || actualHarvest != expectedHarvest
                    || actualShear != expectedShear
            ) {
                throw new AssertionError(
                    action
                        + " emitted unexpected platform events: blockBreak="
                        + actualBlockBreak
                        + ", harvest="
                        + actualHarvest
                        + ", shear="
                        + actualShear
                );
            }
        }

        private record Snapshot(int blockBreak, int harvest, int shear) {
        }
    }
}
