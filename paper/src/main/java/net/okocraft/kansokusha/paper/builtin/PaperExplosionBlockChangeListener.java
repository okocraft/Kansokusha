package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperExplosionBlockChangeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "explosion_block_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<Event, Capture> inFlight = new IdentityHashMap<>();

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
                snapshotPreStates(world, worldKey, event.blockList()),
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
                snapshotPreStates(world, worldKey, event.blockList()),
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureTntPrime(TNTPrimeEvent event) {
        PaperExplosionTntCorrelation.captureModern(this.api, event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeTntPrime(TNTPrimeEvent event) {
        PaperExplosionTntCorrelation.finalizeModern(this.api, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings({"deprecation", "removal"})
    public void captureTntPrime(com.destroystokyo.paper.event.block.TNTPrimeEvent event) {
        PaperExplosionTntCorrelation.captureLegacy(this.api, event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings({"deprecation", "removal"})
    public void finalizeTntPrime(com.destroystokyo.paper.event.block.TNTPrimeEvent event) {
        PaperExplosionTntCorrelation.finalizeLegacy(this.api, event);
    }

    @Override
    public void clearInFlightState() {
        synchronized (this.inFlight) {
            this.inFlight.clear();
        }
        PaperExplosionTntCorrelation.clear(this.api);
    }

    int inFlightCount() {
        synchronized (this.inFlight) {
            return this.inFlight.size() + PaperExplosionTntCorrelation.pendingCount(this.api);
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
            beginTriggerCorrelation(capture, finalBlocks);
            return;
        }
        if (
            result != null
                && result != ExplosionResult.DESTROY
                && result != ExplosionResult.DESTROY_WITH_DECAY
        ) {
            return;
        }

        if (capture.enderDragon() && yield != 0.0F) {
            beginDragonLegacyCorrelation(capture, finalBlocks);
            return;
        }

        var rawCounts = rawCounts(capture, finalBlocks);
        var uniqueBlocks = uniqueFinalBlocks(capture, finalBlocks);
        var modernCandidates = new ArrayList<PaperExplosionTntCorrelation.Candidate>();
        var tntExplodes = Boolean.TRUE.equals(
            capture.world().getGameRuleValue(GameRules.TNT_EXPLODES)
        );
        for (var entry : uniqueBlocks.entrySet()) {
            var key = entry.getKey();
            var liveBlock = normalizedBlock(capture, entry.getValue());
            if (liveBlock.getType().isAir()) {
                continue;
            }
            var submission = submission(capture, key, preState(capture, key, liveBlock));

            if (capture.enderDragon()) {
                this.api.submit(submission);
                continue;
            }

            if (liveBlock.getType() == Material.TNT && tntExplodes) {
                modernCandidates.add(PaperExplosionTntCorrelation.candidate(
                    capture.worldKey(),
                    key.position(),
                    rawCounts.getOrDefault(key, 1),
                    submission,
                    true
                ));
                continue;
            }
            this.api.submit(submission);
        }
        PaperExplosionTntCorrelation.beginModernDestroy(this.api, modernCandidates);
    }

    private void beginDragonLegacyCorrelation(
        Capture capture,
        List<Block> finalBlocks
    ) {
        var candidates = new ArrayList<PaperExplosionTntCorrelation.Candidate>();
        var tntExplodes = Boolean.TRUE.equals(
            capture.world().getGameRuleValue(GameRules.TNT_EXPLODES)
        );
        var submissions = new LinkedHashMap<BlockKey, EventSubmission>();

        // EnderDragon's non-zero-yield path is different from ServerExplosion: Paper reads
        // type/state from each raw list entry, but applies wasExploded/removeBlock at the
        // same coordinates in the dragon's world. Preserve that raw entry order here.
        for (var listedBlock : List.copyOf(finalBlocks)) {
            var listedType = listedBlock.getType();
            if (listedType.isAir()) {
                continue;
            }

            var key = new BlockKey(capture.worldKey(), position(listedBlock));
            var liveBlock = normalizedBlock(capture, listedBlock);
            EventSubmission submission = null;
            if (!liveBlock.getType().isAir()) {
                submission = submissions.computeIfAbsent(
                    key,
                    ignored -> submission(
                        capture,
                        key,
                        preState(capture, key, liveBlock)
                    )
                );
            }

            var listedWorldKey = PaperKansokusha.key(listedBlock.getWorld().getKey());
            // Accepted legacy processing removes the target in the Dragon world. This
            // flag identifies later raw entries whose live listed block will therefore
            // become AIR; foreign-world entries remain live and must stay correlated.
            candidates.add(PaperExplosionTntCorrelation.candidate(
                capture.worldKey(),
                key.position(),
                1,
                submission,
                listedType == Material.TNT && tntExplodes,
                listedWorldKey.equals(capture.worldKey())
            ));
        }
        PaperExplosionTntCorrelation.beginLegacyDragon(this.api, candidates);
    }

    private void beginTriggerCorrelation(Capture capture, List<Block> finalBlocks) {
        if (
            !Boolean.TRUE.equals(
                capture.world().getGameRuleValue(GameRules.TNT_EXPLODES)
            )
        ) {
            return;
        }
        var rawCounts = rawCounts(capture, finalBlocks);
        var candidates = new ArrayList<PaperExplosionTntCorrelation.Candidate>();
        for (var entry : uniqueFinalBlocks(capture, finalBlocks).entrySet()) {
            var key = entry.getKey();
            var liveBlock = normalizedBlock(capture, entry.getValue());
            if (liveBlock.getType() == Material.TNT) {
                candidates.add(PaperExplosionTntCorrelation.candidate(
                    capture.worldKey(),
                    key.position(),
                    rawCounts.getOrDefault(key, 1),
                    null,
                    false
                ));
            }
        }
        PaperExplosionTntCorrelation.beginModernTrigger(this.api, candidates);
    }

    private static BlockData preState(Capture capture, BlockKey key, Block liveBlock) {
        var preState = capture.preStates().get(key);
        return preState == null ? liveBlock.getBlockData().clone() : preState;
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

    private static Map<BlockKey, Integer> rawCounts(Capture capture, List<Block> blocks) {
        var result = new LinkedHashMap<BlockKey, Integer>();
        for (var block : List.copyOf(blocks)) {
            var key = new BlockKey(capture.worldKey(), position(block));
            result.merge(key, 1, Integer::sum);
        }
        return result;
    }

    private static Map<BlockKey, BlockData> snapshotPreStates(
        World world,
        Key worldKey,
        List<Block> blocks
    ) {
        var result = new LinkedHashMap<BlockKey, BlockData>();
        for (var block : blocks) {
            var position = position(block);
            var liveBlock = normalizedBlock(world, worldKey, block);
            result.putIfAbsent(
                new BlockKey(worldKey, position),
                liveBlock.getBlockData().clone()
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
        return normalizedBlock(capture.world(), capture.worldKey(), listedBlock);
    }

    private static Block normalizedBlock(
        World world,
        Key worldKey,
        Block listedBlock
    ) {
        if (PaperKansokusha.key(listedBlock.getWorld().getKey()).equals(worldKey)) {
            return listedBlock;
        }
        var position = position(listedBlock);
        return world.getBlockAt(position.x(), position.y(), position.z());
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
