package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockFertilizeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_fertilize");
    static final String SOURCE_EVENT = "block_fertilize";
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<BlockFertilizeEvent, Capture> inFlight = new IdentityHashMap<>();

    private PaperBlockFertilizeListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockFertilizeListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockFertilizeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        registerEventType(api);
        return new PaperBlockFertilizeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockFertilizeEvent event) {
        Objects.requireNonNull(event, "event");

        var occurredAt = this.clock.instant();
        var sourcePosition = position(event.getBlock());
        var player = event.getPlayer();
        var subject = player == null ? null : new PlayerSubject(player.getUniqueId());
        var changedStates = event.getBlocks();
        var snapshots = new ArrayList<Snapshot>(changedStates.size());

        for (var state : changedStates) {
            var block = state.getBlock();
            snapshots.add(new Snapshot(
                PaperKansokusha.key(state.getWorld().getKey()),
                position(state),
                PaperBlockEventPayloadCodec.encodeFertilize(
                    block.getBlockData().clone(),
                    state.getBlockData().clone(),
                    SOURCE_EVENT,
                    sourcePosition
                )
            ));
        }

        synchronized (this.inFlight) {
            this.inFlight.put(
                event,
                new Capture(occurredAt, subject, List.copyOf(snapshots))
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockFertilizeEvent event) {
        Objects.requireNonNull(event, "event");

        Capture capture;
        synchronized (this.inFlight) {
            capture = this.inFlight.remove(event);
        }

        if (capture == null || event.isCancelled()) {
            return;
        }

        for (var snapshot : capture.snapshots()) {
            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                capture.occurredAt(),
                this.serverKey,
                snapshot.worldKey(),
                snapshot.position(),
                capture.subject(),
                snapshot.payload()
            ));
        }
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

    private static void registerEventType(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        var outcome = api.registerEventType(DEFINITION);
        if (
            outcome != RegistrationOutcome.REGISTERED
                && outcome != RegistrationOutcome.ALREADY_REGISTERED
        ) {
            throw new IllegalStateException(
                "Could not register built-in event type " + EVENT_TYPE + ": " + outcome
            );
        }
    }

    private static BlockPosition position(Block block) {
        return new BlockPosition(block.getX(), block.getY(), block.getZ());
    }

    private static BlockPosition position(BlockState state) {
        return new BlockPosition(state.getX(), state.getY(), state.getZ());
    }

    private record Capture(
        Instant occurredAt,
        @Nullable PlayerSubject subject,
        List<Snapshot> snapshots
    ) {
    }

    private record Snapshot(
        Key worldKey,
        BlockPosition position,
        EventPayload payload
    ) {
    }
}
