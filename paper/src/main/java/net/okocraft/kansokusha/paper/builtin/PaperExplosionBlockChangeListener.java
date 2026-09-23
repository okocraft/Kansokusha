package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.ExplosionResult;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperExplosionBlockChangeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "explosion_block_change");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<Event, Capture> inFlight = new IdentityHashMap<>();
    private final PaperTntTransitionTracker<EventSubmission> pendingTntChanges =
        new PaperTntTransitionTracker<>();
    private final PaperTntTransitionTracker<EventSubmission> pendingDragonChanges =
        new PaperTntTransitionTracker<>();

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
        registerEventType(api);
        return new PaperExplosionBlockChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockExplodeEvent event) {
        Objects.requireNonNull(event, "event");
        var source = event.getExplodedBlockState();
        var world = source.getWorld();
        var worldKey = PaperKansokusha.key(world.getKey());
        put(
            event,
            new Capture(
                this.clock.instant(),
                this.serverKey,
                world,
                worldKey,
                snapshotPreStates(worldKey, event.blockList()),
                event.getExplosionResult(),
                false,
                new ExplosionSource(
                    "block",
                    source.getX() + 0.5D,
                    source.getY() + 0.5D,
                    source.getZ() + 0.5D,
                    worldKey,
                    new BlockPosition(source.getX(), source.getY(), source.getZ()),
                    source.getBlockData().clone(),
                    PaperEntityAttribution.capture(null)
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockExplodeEvent event) {
        Objects.requireNonNull(event, "event");
        finalizeExplosion(event, event.isCancelled(), event.blockList(), event.getYield());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(EntityExplodeEvent event) {
        Objects.requireNonNull(event, "event");
        var location = event.getLocation();
        var world = Objects.requireNonNull(location.getWorld(), "event.location.world");
        var worldKey = PaperKansokusha.key(world.getKey());
        put(
            event,
            new Capture(
                this.clock.instant(),
                this.serverKey,
                world,
                worldKey,
                snapshotPreStates(worldKey, event.blockList()),
                event.getExplosionResult(),
                event.getEntity() instanceof EnderDragon,
                new ExplosionSource(
                    "entity",
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    null,
                    null,
                    null,
                    PaperEntityAttribution.capture(event.getEntity())
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(EntityExplodeEvent event) {
        Objects.requireNonNull(event, "event");
        finalizeExplosion(event, event.isCancelled(), event.blockList(), event.getYield());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeTntPrime(TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getCause() != TNTPrimeEvent.PrimeCause.EXPLOSION) {
            return;
        }
        EventSubmission submission;
        synchronized (this.inFlight) {
            submission = this.pendingTntChanges.remove(event.getBlock());
        }
        if (submission == null || event.isCancelled()) {
            return;
        }
        this.api.submit(submission);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings({"deprecation", "removal"})
    public void finalizeTntPrime(com.destroystokyo.paper.event.block.TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (
            event.getReason()
                != com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION
        ) {
            return;
        }
        EventSubmission submission;
        synchronized (this.inFlight) {
            submission = this.pendingDragonChanges.remove(event.getBlock());
        }
        if (submission == null || event.isCancelled()) {
            return;
        }
        this.api.submit(submission);
    }

    @Override
    public void clearInFlightState() {
        synchronized (this.inFlight) {
            this.inFlight.clear();
            this.pendingTntChanges.clear();
            this.pendingDragonChanges.clear();
        }
        PaperTntPrimeSuppression.clear(this.api);
    }

    int inFlightCount() {
        synchronized (this.inFlight) {
            return this.inFlight.size()
                + this.pendingTntChanges.size()
                + this.pendingDragonChanges.size();
        }
    }

    private void finalizeExplosion(
        Event event,
        boolean cancelled,
        List<Block> finalBlocks,
        float yield
    ) {
        var capture = remove(event);
        if (capture == null || cancelled) {
            return;
        }

        var result = capture.result();
        if (result == ExplosionResult.TRIGGER_BLOCK) {
            suppressTriggerTntPrimes(capture, finalBlocks);
            return;
        }
        if (
            result != null
                && result != ExplosionResult.DESTROY
                && result != ExplosionResult.DESTROY_WITH_DECAY
        ) {
            return;
        }

        var uniqueBlocks = uniqueFinalBlocks(capture, finalBlocks);
        for (var entry : uniqueBlocks.entrySet()) {
            var key = entry.getKey();
            var liveBlock = normalizedBlock(capture, entry.getValue());
            if (liveBlock.getType().isAir()) {
                continue;
            }

            var preState = capture.preStates().get(key);
            if (preState == null) {
                // A plugin may add a block after LOWEST. Snapshot the block in the explosion
                // world at MONITOR rather than retaining the mutable list entry as payload data.
                preState = liveBlock.getBlockData().clone();
            }
            var submission = submission(capture, key, preState);

            if (capture.enderDragon()) {
                if (yield == 0.0F) {
                    this.api.submit(submission);
                } else {
                    synchronized (this.inFlight) {
                        this.pendingDragonChanges.add(
                            capture.worldKey(),
                            key.position(),
                            submission
                        );
                    }
                }
                continue;
            }

            if (
                liveBlock.getType() == Material.TNT
                    && Boolean.TRUE.equals(
                        capture.world().getGameRuleValue(GameRules.TNT_EXPLODES)
                    )
            ) {
                synchronized (this.inFlight) {
                    this.pendingTntChanges.add(capture.worldKey(), key.position(), submission);
                }
                continue;
            }
            this.api.submit(submission);
        }
    }

    private void suppressTriggerTntPrimes(Capture capture, List<Block> finalBlocks) {
        if (
            !Boolean.TRUE.equals(
                capture.world().getGameRuleValue(GameRules.TNT_EXPLODES)
            )
        ) {
            return;
        }

        // Paper processes raw target entries one by one. TRIGGER_BLOCK keeps TNT in place, so
        // duplicate coordinates can produce duplicate modern prime events and each one must be
        // suppressed even though #115 itself deduplicates changed-block submissions.
        for (var listedBlock : List.copyOf(finalBlocks)) {
            var liveBlock = normalizedBlock(capture, listedBlock);
            if (liveBlock.getType() == Material.TNT) {
                PaperTntPrimeSuppression.suppressExplosion(
                    this.api,
                    capture.worldKey(),
                    position(listedBlock)
                );
            }
        }
    }

    private EventSubmission submission(Capture capture, BlockKey key, BlockData preState) {
        var source = capture.source();
        return new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            capture.occurredAt(),
            capture.serverKey(),
            capture.worldKey(),
            key.position(),
            source.actor().subject(),
            PaperWorldMutationPayloadCodec.encodeExplosionBlockChange(
                preState,
                source.kind(),
                source.originX(),
                source.originY(),
                source.originZ(),
                source.blockWorldKey(),
                source.blockPosition(),
                source.blockState(),
                source.actor()
            )
        );
    }

    private void put(Event event, Capture capture) {
        synchronized (this.inFlight) {
            this.inFlight.put(event, capture);
        }
    }

    private @Nullable Capture remove(Event event) {
        synchronized (this.inFlight) {
            return this.inFlight.remove(event);
        }
    }

    private static Map<BlockKey, BlockData> snapshotPreStates(
        Key worldKey,
        List<Block> blocks
    ) {
        var result = new LinkedHashMap<BlockKey, BlockData>();
        for (var block : blocks) {
            result.putIfAbsent(
                new BlockKey(worldKey, position(block)),
                block.getBlockData().clone()
            );
        }
        return Map.copyOf(result);
    }

    private static Map<BlockKey, Block> uniqueFinalBlocks(
        Capture capture,
        List<Block> blocks
    ) {
        var result = new LinkedHashMap<BlockKey, Block>();
        for (var block : List.copyOf(blocks)) {
            result.putIfAbsent(
                new BlockKey(capture.worldKey(), position(block)),
                block
            );
        }
        return result;
    }

    private static Block normalizedBlock(Capture capture, Block listedBlock) {
        if (
            PaperKansokusha.key(listedBlock.getWorld().getKey()).equals(capture.worldKey())
        ) {
            return listedBlock;
        }
        var position = position(listedBlock);
        return capture.world().getBlockAt(position.x(), position.y(), position.z());
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

    private record BlockKey(Key worldKey, BlockPosition position) {
    }

    private record ExplosionSource(
        String kind,
        double originX,
        double originY,
        double originZ,
        @Nullable Key blockWorldKey,
        @Nullable BlockPosition blockPosition,
        @Nullable BlockData blockState,
        PaperEntityAttribution actor
    ) {
    }

    private record Capture(
        Instant occurredAt,
        Key serverKey,
        World world,
        Key worldKey,
        Map<BlockKey, BlockData> preStates,
        @Nullable ExplosionResult result,
        boolean enderDragon,
        ExplosionSource source
    ) {
    }
}
