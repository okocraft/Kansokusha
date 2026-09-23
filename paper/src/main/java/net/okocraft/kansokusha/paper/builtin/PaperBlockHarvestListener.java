package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
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
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockHarvestListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_harvest");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<Event, Snapshot> inFlight = new IdentityHashMap<>();

    private PaperBlockHarvestListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockHarvestListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockHarvestListener register(KansokushaApi api, Key serverKey, Clock clock) {
        requireRegistration(api, DEFINITION);
        return new PaperBlockHarvestListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureHarvest(PlayerHarvestBlockEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getHarvestedBlock();
        this.capture(
            event,
            block,
            event.getPlayer(),
            PaperAdditionalBuiltInPayloadCodec.encodeHarvest(
                block.getBlockData(),
                event.getHand(),
                event.getItemsHarvested()
            )
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureShear(PlayerShearBlockEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getBlock();
        this.capture(
            event,
            block,
            event.getPlayer(),
            PaperAdditionalBuiltInPayloadCodec.encodeShear(
                block.getBlockData(),
                event.getItem(),
                event.getHand(),
                event.getDrops()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeHarvest(PlayerHarvestBlockEvent event) {
        this.finalizeEvent(event, event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeShear(PlayerShearBlockEvent event) {
        this.finalizeEvent(event, event.isCancelled());
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

    private void capture(Event event, Block block, Player player, EventPayload payload) {
        var snapshot = new Snapshot(
            Instant.now(this.clock),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            new BlockPosition(block.getX(), block.getY(), block.getZ()),
            new PlayerSubject(player.getUniqueId()),
            payload
        );
        synchronized (this.inFlight) {
            this.inFlight.put(event, snapshot);
        }
    }

    private void finalizeEvent(Event event, boolean cancelled) {
        Objects.requireNonNull(event, "event");

        Snapshot snapshot;
        synchronized (this.inFlight) {
            snapshot = this.inFlight.remove(event);
        }
        if (snapshot == null || cancelled) {
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

    private static void requireRegistration(KansokushaApi api, EventTypeDefinition definition) {
        Objects.requireNonNull(api, "api");
        var outcome = api.registerEventType(definition);
        if (
            outcome != RegistrationOutcome.REGISTERED
                && outcome != RegistrationOutcome.ALREADY_REGISTERED
        ) {
            throw new IllegalStateException(
                "Could not register built-in event type " + definition.key() + ": " + outcome
            );
        }
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        PlayerSubject subject,
        EventPayload payload
    ) {
    }
}
