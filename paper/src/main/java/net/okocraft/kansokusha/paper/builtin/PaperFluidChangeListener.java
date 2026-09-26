package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.tags.FluidTags;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperFluidChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "fluid_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<BlockFromToEvent, Snapshot> inFlight = new PaperInFlightMap<>();

    private PaperFluidChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperFluidChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperFluidChangeListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperFluidChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockFromToEvent event) {
        Objects.requireNonNull(event, "event");
        var source = event.getBlock();
        var fluidKind = fluidKind(source);
        if (fluidKind == null) {
            return;
        }

        var destination = event.getToBlock();
        var sourcePosition = position(source);
        var destinationPosition = position(destination);
        var snapshot = new Snapshot(
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(destination.getWorld().getKey()),
            destinationPosition,
            PaperBlockEventPayloadCodec.encodeFluidChange(
                fluidKind,
                sourcePosition,
                destinationPosition
            )
        );
        this.inFlight.put(event, snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockFromToEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }
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

    int inFlightCount() {
        return this.inFlight.size();
    }

    private static @Nullable String fluidKind(Block block) {
        if (!(block instanceof CraftBlock craftBlock)) {
            return null;
        }

        var fluidState = craftBlock.getBlockState().getFluidState();
        if (fluidState.is(FluidTags.WATER)) {
            return "water";
        }
        if (fluidState.is(FluidTags.LAVA)) {
            return "lava";
        }
        return null;
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
