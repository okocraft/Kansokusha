package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperFlowerPotChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "flower_pot_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperFlowerPotChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperFlowerPotChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperFlowerPotChangeListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperFlowerPotChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(PlayerFlowerPotManipulateEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getFlowerpot();
        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                PaperKansokusha.key(block.getWorld().getKey()),
                PaperBuiltInSupport.position(block),
                new PlayerSubject(event.getPlayer().getUniqueId()),
                PaperAdditionalBuiltInPayloadCodec.encodeFlowerPot(
                    event.isPlacing(),
                    event.getItem()
                )
            )
        );
    }
}
