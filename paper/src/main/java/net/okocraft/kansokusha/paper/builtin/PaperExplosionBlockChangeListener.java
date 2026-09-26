package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.ExplosionResult;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperExplosionBlockChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "explosion_block_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperExplosionBlockChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperExplosionBlockChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperExplosionBlockChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperExplosionBlockChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordBlockExplosion(BlockExplodeEvent event) {
        Objects.requireNonNull(event, "event");

        var source = event.getExplodedBlockState();
        var world = source.getWorld();
        var worldKey = PaperKansokusha.key(world.getKey());
        this.record(
            world,
            event.getExplosionResult(),
            event.blockList(),
            false,
            new ExplosionSource(
                "block",
                source.getX() + 0.5D,
                source.getY() + 0.5D,
                source.getZ() + 0.5D,
                worldKey,
                position(source),
                source.getBlockData(),
                PaperEntityAttribution.capture(null),
                PaperBuiltInSupport.actor(source.getBlockData())
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordEntityExplosion(EntityExplodeEvent event) {
        Objects.requireNonNull(event, "event");

        var location = event.getLocation();
        var world = Objects.requireNonNull(location.getWorld(), "event.location.world");
        this.record(
            world,
            event.getExplosionResult(),
            event.blockList(),
            event.getEntity() instanceof EnderDragon,
            new ExplosionSource(
                "entity",
                location.getX(),
                location.getY(),
                location.getZ(),
                null,
                null,
                null,
                PaperEntityAttribution.capture(event.getEntity()),
                PaperBuiltInSupport.actor(event.getEntity())
            )
        );
    }

    private void record(
        World world,
        @Nullable ExplosionResult result,
        List<Block> blocks,
        boolean enderDragon,
        ExplosionSource source
    ) {
        // TRIGGER_BLOCK and KEEP explosions do not destroy the listed blocks.
        if (
            result != null
                && result != ExplosionResult.DESTROY
                && result != ExplosionResult.DESTROY_WITH_DECAY
        ) {
            return;
        }

        var occurredAt = this.clock.instant();
        var worldKey = PaperKansokusha.key(world.getKey());
        // Exploded TNT is primed and recorded as tnt_prime. The Ender Dragon does not fire
        // TNTPrimeEvent, so TNT destroyed by it is recorded here.
        var skipTnt = !enderDragon && PaperBuiltInSupport.tntExplodes(world);
        var recordedPositions = new HashSet<BlockPosition>();
        for (var block : blocks) {
            var blockPosition = position(block);
            var type = block.getType();
            if (type.isAir() || (skipTnt && type == Material.TNT) || !recordedPositions.add(blockPosition)) {
                continue;
            }

            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                this.serverKey,
                worldKey,
                blockPosition,
                source.eventActor(),
                PaperBuiltInSupport.type(type),
                PaperWorldMutationPayloadCodec.encodeExplosionBlockChange(
                    block.getBlockData(),
                    source.kind(),
                    source.originX(),
                    source.originY(),
                    source.originZ(),
                    source.blockWorldKey(),
                    source.blockPosition(),
                    source.blockState(),
                    source.actor()
                )
            ));
        }
    }

    private record ExplosionSource(
        String kind,
        double originX,
        double originY,
        double originZ,
        @Nullable Key blockWorldKey,
        @Nullable BlockPosition blockPosition,
        @Nullable BlockData blockState,
        PaperEntityAttribution actor,
        EventActor eventActor
    ) {
    }
}
