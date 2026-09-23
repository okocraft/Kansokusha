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
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SignChangeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperSignChangeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "sign_change");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<SignChangeEvent, Snapshot> inFlight = new IdentityHashMap<>();

    private PaperSignChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperSignChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperSignChangeListener register(KansokushaApi api, Key serverKey, Clock clock) {
        requireRegistration(api, DEFINITION);
        return new PaperSignChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(SignChangeEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getBlock();
        var blockState = block.getState();
        if (!(blockState instanceof Sign sign)) {
            throw new IllegalStateException(
                "SignChangeEvent block state is not a Sign: " + blockState.getClass().getName()
            );
        }

        var side = event.getSide();
        var snapshot = new Snapshot(
            Instant.now(this.clock),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            new BlockPosition(block.getX(), block.getY(), block.getZ()),
            new PlayerSubject(event.getPlayer().getUniqueId()),
            side,
            PaperAdditionalBuiltInPayloadCodec.snapshotLines(sign.getSide(side).lines())
        );

        synchronized (this.inFlight) {
            this.inFlight.put(event, snapshot);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(SignChangeEvent event) {
        Objects.requireNonNull(event, "event");

        Snapshot snapshot;
        synchronized (this.inFlight) {
            snapshot = this.inFlight.remove(event);
        }
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var payload = PaperAdditionalBuiltInPayloadCodec.encodeSignChange(
            snapshot.side(),
            snapshot.beforeLines(),
            PaperAdditionalBuiltInPayloadCodec.snapshotLines(event.lines())
        );
        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                snapshot.occurredAt(),
                snapshot.serverKey(),
                snapshot.worldKey(),
                snapshot.position(),
                snapshot.subject(),
                payload
            )
        );
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
        Side side,
        List<Optional<String>> beforeLines
    ) {
    }
}
