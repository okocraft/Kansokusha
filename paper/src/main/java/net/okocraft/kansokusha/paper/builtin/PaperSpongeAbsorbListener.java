package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperSpongeAbsorbListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "sponge_absorb");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<SpongeAbsorbEvent, Capture> inFlight = new PaperInFlightMap<>();

    private PaperSpongeAbsorbListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperSpongeAbsorbListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperSpongeAbsorbListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperSpongeAbsorbListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(SpongeAbsorbEvent event) {
        Objects.requireNonNull(event, "event");

        var preStates = new LinkedHashMap<BlockKey, BlockData>();
        for (var state : event.getBlocks()) {
            var key = blockKey(state);
            if (!preStates.containsKey(key)) {
                preStates.put(key, state.getBlock().getBlockData().clone());
            }
        }

        this.inFlight.put(
                event,
                new Capture(
                    this.clock.instant(),
                    position(event.getBlock()),
                    Map.copyOf(preStates)
                )
            );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(SpongeAbsorbEvent event) {
        Objects.requireNonNull(event, "event");

        var capture = this.inFlight.remove(event);

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

            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                capture.occurredAt(),
                this.serverKey,
                key.worldKey(),
                key.position(),
                null,
                PaperBlockEventPayloadCodec.encodeSpongeAbsorb(
                    preState,
                    capture.spongeOrigin()
                )
            ));
        }
    }

    @Override
    public void clearInFlightState() {
        this.inFlight.clear();
    }

    int inFlightCount() {
        return this.inFlight.size();
    }


    private static BlockKey blockKey(BlockState state) {
        return new BlockKey(
            PaperKansokusha.key(state.getWorld().getKey()),
            position(state)
        );
    }

    private record Capture(
        Instant occurredAt,
        BlockPosition spongeOrigin,
        Map<BlockKey, BlockData> preStates
    ) {
    }

}
