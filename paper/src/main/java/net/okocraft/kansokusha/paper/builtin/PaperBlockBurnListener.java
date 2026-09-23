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
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockBurnListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_burn");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<BlockBurnEvent, Snapshot> inFlight = new IdentityHashMap<>();
    private final Map<BlockKey, Snapshot> pendingTntBurns = new HashMap<>();
    private final Map<com.destroystokyo.paper.event.block.TNTPrimeEvent, LegacyTntPrimeCapture>
        legacyTntPrimeCaptures = new IdentityHashMap<>();

    private PaperBlockBurnListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockBurnListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockBurnListener register(KansokushaApi api, Key serverKey, Clock clock) {
        registerEventType(api, DEFINITION);
        return new PaperBlockBurnListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockBurnEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getBlock();
        var source = source(event.getIgnitingBlock());
        var snapshot = new Snapshot(
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            block.getType() == Material.TNT,
            PaperBlockEventPayloadCodec.encodeBurn(
                block.getBlockData(),
                source == null ? null : source.position(),
                source == null ? null : source.state()
            )
        );
        synchronized (this.inFlight) {
            this.inFlight.put(event, snapshot);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockBurnEvent event) {
        Objects.requireNonNull(event, "event");
        Snapshot snapshot;
        synchronized (this.inFlight) {
            snapshot = this.inFlight.remove(event);
        }
        if (snapshot == null || event.isCancelled()) {
            return;
        }
        if (snapshot.awaitTntPrime()) {
            synchronized (this.inFlight) {
                this.pendingTntBurns.put(
                    new BlockKey(snapshot.worldKey(), snapshot.position()),
                    snapshot
                );
            }
            return;
        }
        submit(snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeTntPrime(TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getCause() != TNTPrimeEvent.PrimeCause.FIRE || !event.isCancelled()) {
            return;
        }
        removePendingTntBurn(event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings({"deprecation", "removal"})
    public void captureTntPrime(com.destroystokyo.paper.event.block.TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getReason() != com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.FIRE) {
            return;
        }
        synchronized (this.inFlight) {
            this.legacyTntPrimeCaptures.put(
                event,
                new LegacyTntPrimeCapture(event.getBlock().getType() == Material.FIRE)
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings({"deprecation", "removal"})
    public void finalizeTntPrime(com.destroystokyo.paper.event.block.TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getReason() != com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.FIRE) {
            return;
        }
        LegacyTntPrimeCapture capture;
        synchronized (this.inFlight) {
            capture = this.legacyTntPrimeCaptures.remove(event);
        }
        var snapshot = removePendingTntBurn(event.getBlock());
        if (snapshot == null) {
            return;
        }
        if (event.isCancelled()) {
            if (
                capture == null
                    || !capture.alreadyBurned()
                    || event.getBlock().getType() != Material.FIRE
            ) {
                return;
            }
        }
        submit(snapshot);
    }

    @Override
    public void clearInFlightState() {
        synchronized (this.inFlight) {
            this.inFlight.clear();
            this.pendingTntBurns.clear();
            this.legacyTntPrimeCaptures.clear();
        }
    }

    int inFlightCount() {
        synchronized (this.inFlight) {
            return this.inFlight.size()
                + this.pendingTntBurns.size()
                + this.legacyTntPrimeCaptures.size();
        }
    }

    private void submit(Snapshot snapshot) {
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

    private @Nullable Snapshot removePendingTntBurn(Block block) {
        synchronized (this.inFlight) {
            return this.pendingTntBurns.remove(
                new BlockKey(PaperKansokusha.key(block.getWorld().getKey()), position(block))
            );
        }
    }

    private static @Nullable SourceBlock source(@Nullable Block block) {
        return block == null ? null : new SourceBlock(position(block), block.getBlockData());
    }

    private static BlockPosition position(Block block) {
        return new BlockPosition(block.getX(), block.getY(), block.getZ());
    }

    private static void registerEventType(KansokushaApi api, EventTypeDefinition definition) {
        Objects.requireNonNull(api, "api");
        var outcome = api.registerEventType(definition);
        if (outcome != RegistrationOutcome.REGISTERED && outcome != RegistrationOutcome.ALREADY_REGISTERED) {
            throw new IllegalStateException(
                "Could not register built-in event type " + definition.key() + ": " + outcome
            );
        }
    }

    private record SourceBlock(BlockPosition position, BlockData state) {
    }

    private record BlockKey(Key worldKey, BlockPosition position) {
    }

    private record LegacyTntPrimeCapture(boolean alreadyBurned) {
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        boolean awaitTntPrime,
        EventPayload payload
    ) {
    }
}
