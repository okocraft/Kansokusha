package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockIgniteListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_ignite");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperBlockIgniteListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockIgniteListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockIgniteListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperBlockIgniteListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(BlockIgniteEvent event) {
        Objects.requireNonNull(event, "event");

        var cause = event.getCause();
        // Fire spread is recorded as natural_block_change via BlockSpreadEvent.
        if (cause == BlockIgniteEvent.IgniteCause.SPREAD) {
            return;
        }

        var block = event.getBlock();
        var source = event.getIgnitingBlock();
        var entity = event.getIgnitingEntity();
        var player = event.getPlayer();
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            player == null ? null : new PlayerSubject(player.getUniqueId()),
            PaperBlockEventPayloadCodec.encodeIgnite(
                block.getBlockData(),
                cause.name(),
                source == null ? null : position(source),
                source == null ? null : source.getBlockData(),
                entity == null ? null : entity.getUniqueId(),
                entity == null ? null : entity.getType().name()
            )
        ));
    }
}
