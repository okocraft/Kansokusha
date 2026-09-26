package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperSignChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "sign_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperSignChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperSignChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperSignChangeListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperSignChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(SignChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        var blockState = block.getState();
        if (!(blockState instanceof Sign sign)) {
            throw new IllegalStateException(
                "SignChangeEvent block state is not a Sign: " + blockState.getClass().getName()
            );
        }

        var side = event.getSide();
        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                PaperKansokusha.key(block.getWorld().getKey()),
                PaperBuiltInSupport.position(block),
                new PlayerActor(event.getPlayer().getUniqueId()),
                PaperBuiltInSupport.blockType(block.getBlockData()),
                PaperAdditionalBuiltInPayloadCodec.encodeSignChange(
                    side,
                    PaperAdditionalBuiltInPayloadCodec.snapshotLines(sign.getSide(side).lines()),
                    PaperAdditionalBuiltInPayloadCodec.snapshotLines(event.lines())
                )
            )
        );
    }
}
