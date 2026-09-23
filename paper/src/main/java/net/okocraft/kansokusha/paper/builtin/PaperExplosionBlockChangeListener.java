package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockExplodeEvent;
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
        put(
            event,
            new Capture(
                this.clock.instant(),
                this.serverKey,
                snapshotPreStates(event.blockList()),
                new ExplosionSource(
                    "block",
                    source.getX() + 0.5D,
                    source.getY() + 0.5D,
                    source.getZ() + 0.5D,
                    PaperKansokusha.key(source.getWorld().getKey()),
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
        finalizeExplosion(event, event.isCancelled(), event.blockList());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(EntityExplodeEvent event) {
        Objects.requireNonNull(event, "event");
        var location = event.getLocation();
        put(
            event,
            new Capture(
                this.clock.instant(),
                this.serverKey,
                snapshotPreStates(event.blockList()),
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
        finalizeExplosion(event, event.isCancelled(), event.blockList());
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

    private void finalizeExplosion(Event event, boolean cancelled, List<Block> finalBlocks) {
        var capture = remove(event);
        if (capture == null || cancelled) {
            return;
        }

        for (var block : List.copyOf(finalBlocks)) {
            var key = blockKey(block);
            var preState = capture.preStates().get(key);
            if (preState == null) {
                // A plugin may add a block after LOWEST. Snapshot it immediately at MONITOR
                // rather than retaining the mutable Block as a payload source.
                preState = block.getBlockData().clone();
            }
            var source = capture.source();
            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                capture.occurredAt(),
                capture.serverKey(),
                key.worldKey(),
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
            ));
        }
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

    private static Map<BlockKey, BlockData> snapshotPreStates(List<Block> blocks) {
        var result = new LinkedHashMap<BlockKey, BlockData>();
        for (var block : blocks) {
            result.putIfAbsent(blockKey(block), block.getBlockData().clone());
        }
        return Map.copyOf(result);
    }

    private static BlockKey blockKey(Block block) {
        return new BlockKey(
            PaperKansokusha.key(block.getWorld().getKey()),
            new BlockPosition(block.getX(), block.getY(), block.getZ())
        );
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
        Map<BlockKey, BlockData> preStates,
        ExplosionSource source
    ) {
    }
}
