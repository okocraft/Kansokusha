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
import org.bukkit.block.BlockState;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperNaturalBlockChangeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "natural_block_change");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<Event, List<Snapshot>> inFlight = new IdentityHashMap<>();

    private PaperNaturalBlockChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperNaturalBlockChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperNaturalBlockChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        Objects.requireNonNull(api, "api");
        var outcome = api.registerEventType(DEFINITION);
        if (outcome != RegistrationOutcome.REGISTERED && outcome != RegistrationOutcome.ALREADY_REGISTERED) {
            throw new IllegalStateException(
                "Could not register built-in event type " + EVENT_TYPE + ": " + outcome
            );
        }
        return new PaperNaturalBlockChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockFadeEvent event) {
        captureTransition(event, event.getBlock(), event.getNewState(), "block_fade", null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockFadeEvent event) {
        finalizeEvent((Event) event, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockFormEvent event) {
        if (event instanceof BlockSpreadEvent) {
            return;
        }
        captureTransition(event, event.getBlock(), event.getNewState(), "block_form", null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockFormEvent event) {
        if (event instanceof BlockSpreadEvent) {
            return;
        }
        finalizeEvent((Event) event, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockGrowEvent event) {
        if (event instanceof BlockFormEvent) {
            return;
        }
        captureTransition(event, event.getBlock(), event.getNewState(), "block_grow", null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockGrowEvent event) {
        if (event instanceof BlockFormEvent) {
            return;
        }
        finalizeEvent((Event) event, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockSpreadEvent event) {
        var source = event.getSource();
        captureTransition(
            event,
            event.getBlock(),
            event.getNewState(),
            "block_spread",
            source.getType().name().toLowerCase(Locale.ROOT),
            position(source)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockSpreadEvent event) {
        finalizeEvent((Event) event, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(LeavesDecayEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getBlock();
        var snapshot = new Snapshot(
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            PaperBlockEventPayloadCodec.encodeLeavesDecay(block.getBlockData())
        );
        put(event, List.of(snapshot));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(LeavesDecayEvent event) {
        finalizeEvent((Event) event, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(MoistureChangeEvent event) {
        captureTransition(
            event,
            event.getBlock(),
            event.getNewState(),
            "moisture_change",
            null,
            null
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(MoistureChangeEvent event) {
        finalizeEvent((Event) event, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(StructureGrowEvent event) {
        Objects.requireNonNull(event, "event");

        if (event.isFromBonemeal()) {
            return;
        }

        var occurredAt = this.clock.instant();
        var cause = event.getSpecies().name().toLowerCase(Locale.ROOT);
        var captured = new ArrayList<Snapshot>(event.getBlocks().size());
        for (var state : event.getBlocks()) {
            var block = state.getBlock();
            captured.add(new Snapshot(
                occurredAt,
                this.serverKey,
                PaperKansokusha.key(state.getWorld().getKey()),
                new BlockPosition(state.getX(), state.getY(), state.getZ()),
                PaperBlockEventPayloadCodec.encodeNaturalChange(
                    block.getBlockData(),
                    state.getBlockData(),
                    "structure_grow",
                    cause,
                    null
                )
            ));
        }
        put(event, List.copyOf(captured));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(StructureGrowEvent event) {
        finalizeEvent((Event) event, event);
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

    private void captureTransition(
        Event event,
        Block block,
        BlockState postState,
        String sourceEvent,
        @Nullable String cause,
        @Nullable BlockPosition sourcePosition
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(postState, "postState");
        var snapshot = new Snapshot(
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            PaperBlockEventPayloadCodec.encodeNaturalChange(
                block.getBlockData(),
                postState.getBlockData(),
                sourceEvent,
                cause,
                sourcePosition
            )
        );
        put(event, List.of(snapshot));
    }

    private void finalizeEvent(Event event, Cancellable cancellable) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(cancellable, "cancellable");
        List<Snapshot> snapshots;
        synchronized (this.inFlight) {
            snapshots = this.inFlight.remove(event);
        }
        if (snapshots == null || cancellable.isCancelled()) {
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

    private void put(Event event, List<Snapshot> snapshots) {
        synchronized (this.inFlight) {
            this.inFlight.put(event, snapshots);
        }
    }

    private static BlockPosition position(Block block) {
        return new BlockPosition(block.getX(), block.getY(), block.getZ());
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
