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
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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

        var preStates = new LinkedHashMap<BlockKey, BlockData>();
        for (var state : event.getBlocks()) {
            preStates.putIfAbsent(
                blockKey(state),
                state.getBlock().getBlockData().clone()
            );
        }

        var player = event.getPlayer();
        synchronized (this.inFlight) {
            this.inFlight.put(
                event,
                new Capture(
                    this.clock.instant(),
                    player == null ? null : new PlayerSubject(player.getUniqueId()),
                    position(event.getBlock()),
                    Map.copyOf(preStates)
                )
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

        var finalStatesByBlock = new LinkedHashMap<BlockKey, BlockState>();
        for (var state : event.getBlocks()) {
            finalStatesByBlock.put(blockKey(state), state);
        }

        for (var entry : finalStatesByBlock.entrySet()) {
            var key = entry.getKey();
            var state = entry.getValue();
            var preState = capture.preStates().get(key);
            if (preState == null) {
                preState = state.getBlock().getBlockData().clone();
            }
            var postState = state.getBlockData().clone();
            if (sameBlockData(preState, postState)) {
                continue;
            }

            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                capture.occurredAt(),
                this.serverKey,
                key.worldKey(),
                key.position(),
                capture.subject(),
                PaperBlockEventPayloadCodec.encodeFertilize(
                    preState,
                    postState,
                    SOURCE_EVENT,
                    capture.sourcePosition()
                )
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

    private static BlockKey blockKey(BlockState state) {
        return new BlockKey(
            PaperKansokusha.key(state.getWorld().getKey()),
            position(state)
        );
    }

    private static boolean sameBlockData(BlockData first, BlockData second) {
        return first.getAsString().equals(second.getAsString());
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
        BlockPosition sourcePosition,
        Map<BlockKey, BlockData> preStates
    ) {
    }

    private record BlockKey(
        Key worldKey,
        BlockPosition position
    ) {
    }
}
