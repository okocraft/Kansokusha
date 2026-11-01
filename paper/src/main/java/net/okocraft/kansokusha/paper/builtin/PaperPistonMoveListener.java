package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

/**
 * Records the blocks moved by pistons.
 *
 * <p>Piston machines repeat the same moves many times a minute. Equal moves (same piston, action,
 * direction, block and positions) are summed per {@link #AGGREGATION_WINDOW} into one event that
 * carries the number of moves and is stamped with the start of its window.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPistonMoveListener implements Listener, PaperBufferedListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "piston_move");
    static final Duration AGGREGATION_WINDOW = Duration.ofMinutes(1);

    // Bounds the memory used by the aggregation; moves beyond it are submitted one by one.
    static final int MAX_PENDING = 100_000;

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final ConcurrentHashMap<Move, Pending> pending = new ConcurrentHashMap<>();

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
            var move = new Move(
                PaperKansokusha.key(block.getWorld().getKey()),
                to,
                pistonActor,
                blockData,
                from,
                pistonWorldKey,
                pistonOrigin,
                direction.name(),
                action
            );
            this.record(occurredAt, move);
        }
    }

    private void record(Instant occurredAt, Move move) {
        while (true) {
            var existing = this.pending.get(move);
            if (existing != null) {
                if (!existing.isDue(occurredAt) && existing.add()) {
                    return;
                }
                this.complete(move, existing);
                continue;
            }

            var created = new Pending(occurredAt);
            if (this.pending.size() >= MAX_PENDING) {
                this.submit(occurredAt, move, 1);
                return;
            }
            if (this.pending.putIfAbsent(move, created) == null) {
                return;
            }
        }
    }

    @Override
    public void flushDue() {
        var now = this.clock.instant();
        this.pending.forEach((move, entry) -> {
            if (entry.isDue(now)) {
                this.complete(move, entry);
            }
        });
    }

    @Override
    public void flushAll() {
        this.pending.forEach(this::complete);
    }

    private void complete(Move move, Pending entry) {
        if (entry.close()) {
            this.submit(entry.start, move, entry.moves);
        }
        this.pending.remove(move, entry);
    }

    private void submit(Instant occurredAt, Move move, int moves) {
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            occurredAt,
            this.serverKey,
            move.worldKey,
            move.to,
            move.pistonActor,
            PaperBuiltInSupport.blockType(move.blockData),
            PaperWorldMutationPayloadCodec.encodePistonMove(
                move.from,
                move.to,
                move.blockData,
                move.pistonWorldKey,
                move.pistonOrigin,
                move.direction,
                move.action,
                moves
            )
        ));
    }

    /**
     * One block moved by a piston. Equal moves are repeated runs of the same piston machine and are
     * summed; the block data is a copy owned by this object, so it can be kept as a map key.
     */
    private record Move(
        Key worldKey,
        BlockPosition to,
        BlockActor pistonActor,
        BlockData blockData,
        BlockPosition from,
        Key pistonWorldKey,
        BlockPosition pistonOrigin,
        String direction,
        String action
    ) {
    }

    private static final class Pending {

        private final Instant start;
        private int moves = 1;
        private boolean closed;

        private Pending(Instant start) {
            this.start = start;
        }

        private boolean isDue(Instant now) {
            return !now.isBefore(this.start.plus(AGGREGATION_WINDOW));
        }

        private synchronized boolean add() {
            if (this.closed) {
                return false;
            }
            this.moves++;
            return true;
        }

        /** Returns whether this call closed the entry; only the caller that did may submit it. */
        private synchronized boolean close() {
            if (this.closed) {
                return false;
            }
            this.closed = true;
            return true;
        }
    }
}
