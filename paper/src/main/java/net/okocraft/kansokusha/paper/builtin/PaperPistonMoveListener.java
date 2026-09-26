package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPistonMoveListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "piston_move");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperPistonMoveListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPistonMoveListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPistonMoveListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPistonMoveListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordExtend(BlockPistonExtendEvent event) {
        Objects.requireNonNull(event, "event");
        this.record(event.getBlock(), event.getBlocks(), event.getDirection(), "extend");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordRetract(BlockPistonRetractEvent event) {
        Objects.requireNonNull(event, "event");
        this.record(event.getBlock(), event.getBlocks(), event.getDirection(), "retract");
    }

    private void record(
        Block piston,
        List<Block> movedBlocks,
        BlockFace direction,
        String action
    ) {
        var occurredAt = this.clock.instant();
        var pistonWorldKey = PaperKansokusha.key(piston.getWorld().getKey());
        var pistonOrigin = position(piston);
        var pistonActor = PaperBuiltInSupport.actor(piston.getBlockData());

        for (var block : movedBlocks) {
            if (block.getPistonMoveReaction() == PistonMoveReaction.BREAK) {
                continue;
            }

            var blockData = block.getBlockData();
            var from = position(block);
            var to = new BlockPosition(
                from.x() + direction.getModX(),
                from.y() + direction.getModY(),
                from.z() + direction.getModZ()
            );
            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                this.serverKey,
                PaperKansokusha.key(block.getWorld().getKey()),
                to,
                pistonActor,
                PaperBuiltInSupport.blockType(blockData),
                PaperWorldMutationPayloadCodec.encodePistonMove(
                    from,
                    to,
                    blockData,
                    pistonWorldKey,
                    pistonOrigin,
                    direction.name(),
                    action
                )
            ));
        }
    }
}
