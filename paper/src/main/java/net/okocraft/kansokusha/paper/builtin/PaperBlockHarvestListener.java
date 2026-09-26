package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockHarvestListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_harvest");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperBlockHarvestListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockHarvestListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockHarvestListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperBlockHarvestListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordHarvest(PlayerHarvestBlockEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getHarvestedBlock();
        this.submit(
            block,
            event.getPlayer(),
            PaperAdditionalBuiltInPayloadCodec.encodeHarvest(
                block.getBlockData(),
                event.getHand(),
                event.getItemsHarvested()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordShear(PlayerShearBlockEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getBlock();
        this.submit(
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

    private void submit(Block block, Player player, EventPayload payload) {
        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                PaperKansokusha.key(block.getWorld().getKey()),
                PaperBuiltInSupport.position(block),
                new PlayerSubject(player.getUniqueId()),
                payload
            )
        );
    }
}
