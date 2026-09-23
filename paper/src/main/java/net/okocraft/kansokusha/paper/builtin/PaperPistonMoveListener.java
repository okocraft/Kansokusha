package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPistonMoveListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "piston_move");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<Event, List<Snapshot>> inFlight = new IdentityHashMap<>();

    private PaperPistonMoveListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPistonMoveListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPistonMoveListener register(KansokushaApi api, Key serverKey, Clock clock) {
        registerEventType(api);
        return new PaperPistonMoveListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockPistonExtendEvent event) {
        Objects.requireNonNull(event, "event");
        capture(event, event.getBlock(), event.getBlocks(), event.getDirection(), "extend");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockPistonExtendEvent event) {
        Objects.requireNonNull(event, "event");
        finalizeMovement(event, event.isCancelled());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockPistonRetractEvent event) {
        Objects.requireNonNull(event, "event");
        capture(event, event.getBlock(), event.getBlocks(), event.getDirection(), "retract");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockPistonRetractEvent event) {
        Objects.requireNonNull(event, "event");
        finalizeMovement(event, event.isCancelled());
    }

    @Override
    public void clearInFlightState() {
        synchronized (this.inFlight) {
            this.inFlight.clear();
        }
    }

    int inFlightCount() {
        synchronized (this.inFlight) {
            return this.inFlight.size();
        }
    }

    private void capture(
        Event event,
        Block piston,
        List<Block> movedBlocks,
        BlockFace direction,
        String action
    ) {
        var occurredAt = this.clock.instant();
        var pistonWorldKey = PaperKansokusha.key(piston.getWorld().getKey());
        var pistonOrigin = position(piston);
        var snapshots = new ArrayList<Snapshot>(movedBlocks.size());

        for (var block : movedBlocks) {
            if (block.getPistonMoveReaction() == PistonMoveReaction.BREAK) {
                continue;
            }

            var from = position(block);
            var to = new BlockPosition(
                from.x() + direction.getModX(),
                from.y() + direction.getModY(),
                from.z() + direction.getModZ()
            );
            snapshots.add(new Snapshot(
                occurredAt,
                this.serverKey,
                PaperKansokusha.key(block.getWorld().getKey()),
                to,
                PaperWorldMutationPayloadCodec.encodePistonMove(
                    from,
                    to,
                    block.getBlockData().clone(),
                    pistonWorldKey,
                    pistonOrigin,
                    direction.name(),
                    action
                )
            ));
        }

        synchronized (this.inFlight) {
            this.inFlight.put(event, List.copyOf(snapshots));
        }
    }

    private void finalizeMovement(Event event, boolean cancelled) {
        List<Snapshot> snapshots;
        synchronized (this.inFlight) {
            snapshots = this.inFlight.remove(event);
        }
        if (snapshots == null || cancelled) {
            return;
        }

        for (var snapshot : snapshots) {
            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                snapshot.occurredAt(),
                snapshot.serverKey(),
                snapshot.worldKey(),
                snapshot.position(),
                null,
                snapshot.payload()
            ));
        }
    }

    private static BlockPosition position(Block block) {
        return new BlockPosition(block.getX(), block.getY(), block.getZ());
    }

    private static void registerEventType(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        var outcome = api.registerEventType(DEFINITION);
        if (outcome != RegistrationOutcome.REGISTERED && outcome != RegistrationOutcome.ALREADY_REGISTERED) {
            throw new IllegalStateException(
                "Could not register built-in event type " + EVENT_TYPE + ": " + outcome
            );
        }
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        EventPayload payload
    ) {
    }
}
