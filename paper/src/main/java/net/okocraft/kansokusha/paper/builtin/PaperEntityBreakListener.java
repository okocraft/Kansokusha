package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperEntityBreakListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "entity_break");
    static final String HANGING_SOURCE_EVENT =
        "org.bukkit.event.hanging.HangingBreakByEntityEvent";

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperEntityBreakListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperEntityBreakListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperEntityBreakListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperEntityBreakListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordHanging(HangingBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var brokenEntity = PaperEntityEventPayloadCodec.snapshotEntity(event.getEntity());
        var damageSource = event.getDamageSource();
        var remover = event.getRemover();
        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                brokenEntity.worldKey(),
                new BlockPosition(
                    (int) Math.floor(brokenEntity.x()),
                    (int) Math.floor(brokenEntity.y()),
                    (int) Math.floor(brokenEntity.z())
                ),
                remover instanceof Player player ? new PlayerSubject(player.getUniqueId()) : null,
                PaperEntityEventPayloadCodec.encodeBreak(
                    brokenEntity,
                    PaperEntityEventPayloadCodec.snapshotEntity(remover),
                    event.getCause().name().toLowerCase(Locale.ROOT),
                    damageSource.getDamageType().getKey().toString(),
                    damageSource.isIndirect(),
                    HANGING_SOURCE_EVENT,
                    true
                )
            )
        );
    }
}
