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
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

/**
 * Records accepted Paper kicks after other handlers have had an opportunity to cancel or
 * replace the reason.
 *
 * <p>The Paper 26.2 real-server integration fixture verifies that an accepted kick continues
 * into a subsequent {@link org.bukkit.event.player.PlayerQuitEvent} whose quit reason is
 * {@link org.bukkit.event.player.PlayerQuitEvent.QuitReason#KICKED}. The kick and quit are
 * intentionally distinct: this event records cause/reason at the kick boundary, while quit
 * records the session end.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerKickListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "paper_kick");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperPlayerKickListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerKickListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerKickListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPlayerKickListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(PlayerKickEvent event) {
        Objects.requireNonNull(event, "event");

        if (event.isCancelled()) {
            return;
        }

        var player = event.getPlayer();
        var location = player.getLocation();
        var world = Objects.requireNonNull(location.getWorld(), "player.location.world");

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                PaperKansokusha.key(world.getKey()),
                new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                new PlayerSubject(player.getUniqueId()),
                PaperPlayerSessionPayloadCodec.encodeKick(event.getCause(), event.reason())
            )
        );
    }
}
