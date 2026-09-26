package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockBurnListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_burn");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperBlockBurnListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockBurnListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockBurnListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperBlockBurnListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(BlockBurnEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        // Burning TNT is primed instead of destroyed; tnt_prime records it.
        if (block.getType() == Material.TNT && PaperBuiltInSupport.tntExplodes(block.getWorld())) {
            return;
        }

        var source = event.getIgnitingBlock();
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            source == null ? null : PaperBuiltInSupport.actor(source.getBlockData()),
            PaperBuiltInSupport.blockType(block.getBlockData()),
            PaperBlockEventPayloadCodec.encodeBurn(
                block.getBlockData(),
                source == null ? null : position(source),
                source == null ? null : source.getBlockData()
            )
        ));
    }
}
