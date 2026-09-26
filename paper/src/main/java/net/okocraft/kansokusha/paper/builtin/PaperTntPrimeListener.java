package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.TNTPrimeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperTntPrimeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "tnt_prime");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");

        var tnt = event.getBlock();
        // Fire removes TNT without priming it when the game rule is disabled; block_burn records it.
        if (!PaperBuiltInSupport.tntExplodes(tnt.getWorld())) {
            return;
        }

        var primingEntity = event.getPrimingEntity();
        var actor = PaperEntityAttribution.capture(primingEntity);
        var primingBlock = event.getPrimingBlock();
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(tnt.getWorld().getKey()),
            position(tnt),
            primingEntity != null
                ? PaperBuiltInSupport.actor(primingEntity)
                : primingBlock == null ? null : PaperBuiltInSupport.actor(primingBlock.getBlockData()),
            PaperBuiltInSupport.blockType(tnt.getBlockData()),
            PaperWorldMutationPayloadCodec.encodeTntPrime(
                event.getCause().name(),
                actor,
                primingBlock == null ? null : PaperKansokusha.key(primingBlock.getWorld().getKey()),
                primingBlock == null ? null : position(primingBlock),
                primingBlock == null ? null : primingBlock.getBlockData()
            )
        ));
    }
}
