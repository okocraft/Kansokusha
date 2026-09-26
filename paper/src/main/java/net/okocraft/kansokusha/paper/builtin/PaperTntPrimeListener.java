package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.GameRules;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.TNTPrimeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperTntPrimeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "tnt_prime");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<TNTPrimeEvent, Snapshot> inFlight = new IdentityHashMap<>();
    @SuppressWarnings({"deprecation", "removal"})
    private final Map<com.destroystokyo.paper.event.block.TNTPrimeEvent, Snapshot>
        legacyExplosionInFlight = new IdentityHashMap<>();
    private final PaperTntTransitionTracker<Snapshot> pendingFire =
        new PaperTntTransitionTracker<>();

    private PaperTntPrimeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperTntPrimeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperTntPrimeListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperTntPrimeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getCause() == TNTPrimeEvent.PrimeCause.EXPLOSION) {
            PaperExplosionTntCorrelation.captureModern(this.api, event);
        }

        var tnt = event.getBlock();
        var actor = PaperEntityAttribution.capture(event.getPrimingEntity());
        var primingBlock = immutableBlock(event.getPrimingBlock());
        var snapshot = snapshot(
            event.getCause().name(),
            tnt,
            actor,
            primingBlock,
            event.getCause() == TNTPrimeEvent.PrimeCause.FIRE
        );

        synchronized (this.inFlight) {
            this.inFlight.put(event, snapshot);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        Snapshot snapshot;
        synchronized (this.inFlight) {
            snapshot = this.inFlight.remove(event);
        }
        if (snapshot == null) {
            return;
        }

        if (event.getCause() == TNTPrimeEvent.PrimeCause.EXPLOSION) {
            var decision = PaperExplosionTntCorrelation.finalizeModern(this.api, event);
            if (decision.tracked() && !decision.recordTntPrime()) {
                return;
            }
        }
        if (event.isCancelled()) {
            return;
        }

        if (snapshot.awaitLegacyFire()) {
            synchronized (this.inFlight) {
                this.pendingFire.add(snapshot.worldKey(), snapshot.position(), snapshot);
            }
            return;
        }
        submit(snapshot);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings({"deprecation", "removal"})
    public void captureTntPrime(com.destroystokyo.paper.event.block.TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (
            event.getReason()
                != com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION
        ) {
            return;
        }
        if (!PaperExplosionTntCorrelation.captureLegacy(this.api, event)) {
            return;
        }

        var tnt = event.getBlock();
        var actor = PaperEntityAttribution.capture(event.getPrimerEntity());
        var snapshot = snapshot("EXPLOSION", tnt, actor, null, false);
        synchronized (this.inFlight) {
            this.legacyExplosionInFlight.put(event, snapshot);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings({"deprecation", "removal"})
    public void finalizeTntPrime(com.destroystokyo.paper.event.block.TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (
            event.getReason()
                == com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION
        ) {
            Snapshot snapshot;
            synchronized (this.inFlight) {
                snapshot = this.legacyExplosionInFlight.remove(event);
            }
            var decision = PaperExplosionTntCorrelation.finalizeLegacy(this.api, event);
            if (snapshot != null && decision.recordTntPrime()) {
                submit(snapshot);
            }
            return;
        }

        if (
            event.getReason()
                != com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.FIRE
        ) {
            return;
        }
        Snapshot snapshot;
        synchronized (this.inFlight) {
            snapshot = this.pendingFire.remove(event.getBlock());
        }
        if (
            snapshot == null
                || event.isCancelled()
                || !Boolean.TRUE.equals(
                    event.getBlock().getWorld().getGameRuleValue(GameRules.TNT_EXPLODES)
                )
        ) {
            return;
        }
        submit(snapshot);
    }

    int inFlightCount() {
        synchronized (this.inFlight) {
            return this.inFlight.size()
                + this.legacyExplosionInFlight.size()
                + this.pendingFire.size();
        }
    }

    private Snapshot snapshot(
        String cause,
        Block tnt,
        PaperEntityAttribution actor,
        @Nullable ImmutableBlock primingBlock,
        boolean awaitLegacyFire
    ) {
        return new Snapshot(
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(tnt.getWorld().getKey()),
            position(tnt),
            awaitLegacyFire,
            actor.subject(),
            PaperWorldMutationPayloadCodec.encodeTntPrime(
                cause,
                actor,
                primingBlock == null ? null : primingBlock.worldKey(),
                primingBlock == null ? null : primingBlock.position(),
                primingBlock == null ? null : primingBlock.state()
            )
        );
    }

    private void submit(Snapshot snapshot) {
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            snapshot.serverKey(),
            snapshot.worldKey(),
            snapshot.position(),
            snapshot.subject(),
            snapshot.payload()
        ));
    }

    private static @Nullable ImmutableBlock immutableBlock(@Nullable Block block) {
        if (block == null) {
            return null;
        }
        return new ImmutableBlock(
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            block.getBlockData().clone()
        );
    }


    private record ImmutableBlock(Key worldKey, BlockPosition position, BlockData state) {
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        boolean awaitLegacyFire,
        @Nullable PlayerSubject subject,
        EventPayload payload
    ) {
    }
}
