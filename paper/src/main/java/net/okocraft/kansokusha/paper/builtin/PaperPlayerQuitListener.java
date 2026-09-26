package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

/**
 * Records the end of a Paper backend session using the player's final readable location.
 *
 * <p>Paper 26.2 emits a quit with {@link PlayerQuitEvent.QuitReason#KICKED} after an accepted
 * {@link org.bukkit.event.player.PlayerKickEvent}. The real-server integration fixture locks
 * that platform behavior down. The kick records the disconnect decision while this event
 * records the later session end, so both are intentional.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerQuitListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "paper_quit");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperPlayerQuitListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerQuitListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerQuitListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPlayerQuitListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(PlayerQuitEvent event) {
        Objects.requireNonNull(event, "event");

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
                new PlayerActor(player.getUniqueId()),
                null,
                PaperPlayerSessionPayloadCodec.encodeQuit(event.getReason())
            )
        );
    }
}
