package net.okocraft.kansokusha.paper.builtin;

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
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockPlaceListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_place");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<BlockPlaceEvent, List<Snapshot>> inFlight = new IdentityHashMap<>();

    private PaperBlockPlaceListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockPlaceListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockPlaceListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
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
        return new PaperBlockPlaceListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockPlaceEvent event) {
        Objects.requireNonNull(event, "event");

        var occurredAt = Instant.now(this.clock);
        var subject = new PlayerSubject(event.getPlayer().getUniqueId());
        List<Snapshot> snapshots;

        if (event instanceof BlockMultiPlaceEvent multiPlaceEvent) {
            var captured = new ArrayList<Snapshot>(multiPlaceEvent.getReplacedBlockStates().size());
            for (var replacedState : multiPlaceEvent.getReplacedBlockStates()) {
                captured.add(this.snapshot(replacedState, subject, occurredAt));
            }
            snapshots = List.copyOf(captured);
        } else {
            snapshots = List.of(
                this.snapshot(event.getBlockReplacedState(), subject, occurredAt)
            );
        }

        synchronized (this.inFlight) {
            this.inFlight.put(event, snapshots);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockPlaceEvent event) {
        Objects.requireNonNull(event, "event");

        List<Snapshot> snapshots;
        synchronized (this.inFlight) {
            snapshots = this.inFlight.remove(event);
        }

        if (snapshots == null || event.isCancelled() || !event.canBuild()) {
            return;
        }

        for (var snapshot : snapshots) {
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
    }

    public void clear() {
        synchronized (this.inFlight) {
            this.inFlight.clear();
        }
    }

    int inFlightCount() {
        synchronized (this.inFlight) {
            return this.inFlight.size();
        }
    }

    private Snapshot snapshot(
        BlockState replacedState,
        PlayerSubject subject,
        Instant occurredAt
    ) {
        var placedBlock = replacedState.getBlock();
        return new Snapshot(
            occurredAt,
            this.serverKey,
            PaperKansokusha.key(replacedState.getWorld().getKey()),
            new BlockPosition(
                replacedState.getX(),
                replacedState.getY(),
                replacedState.getZ()
            ),
            subject,
            PaperBlockStatePayloadCodec.encodeBlockPlace(
                replacedState.getBlockData(),
                placedBlock.getBlockData()
            )
        );
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
