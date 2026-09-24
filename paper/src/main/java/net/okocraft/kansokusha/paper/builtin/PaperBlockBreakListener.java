package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockBreakListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_break");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<BlockBreakEvent, Snapshot> inFlight = new PaperInFlightMap<>();

    private PaperBlockBreakListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockBreakListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockBreakListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperBlockBreakListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockBreakEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        var snapshot = new Snapshot(
            Instant.now(this.clock),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            new BlockPosition(block.getX(), block.getY(), block.getZ()),
            new PlayerSubject(event.getPlayer().getUniqueId()),
            PaperBlockStatePayloadCodec.encodeBlockBreak(block.getBlockData())
        );

        this.inFlight.put(event, snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockBreakEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.inFlight.remove(event);

        if (snapshot == null || event.isCancelled()) {
            return;
        }

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                snapshot.occurredAt(),
                snapshot.serverKey(),
                snapshot.worldKey(),
                snapshot.position(),
                snapshot.subject(),
                snapshot.payload()
            )
        );
    }

    @Override
    public void clearInFlightState() {
        this.inFlight.clear();
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        PlayerSubject subject,
        net.okocraft.kansokusha.api.event.EventPayload payload
    ) {
    }
}
