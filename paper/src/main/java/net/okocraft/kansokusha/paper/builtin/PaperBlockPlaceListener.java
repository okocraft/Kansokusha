package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockPlaceListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_place");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperBlockPlaceListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockPlaceListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockPlaceListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperBlockPlaceListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(BlockPlaceEvent event) {
        Objects.requireNonNull(event, "event");

        if (!event.canBuild()) {
            return;
        }

        var occurredAt = this.clock.instant();
        var actor = new PlayerActor(event.getPlayer().getUniqueId());
        var replacedStates = event instanceof BlockMultiPlaceEvent multiPlaceEvent
            ? multiPlaceEvent.getReplacedBlockStates()
            : List.of(event.getBlockReplacedState());

        for (var replacedState : replacedStates) {
            var placedState = replacedState.getBlock().getBlockData();
            this.api.submit(
                new EventSubmission(
                    EVENT_TYPE,
                    PayloadGeneration.FIRST,
                    occurredAt,
                    this.serverKey,
                    PaperKansokusha.key(replacedState.getWorld().getKey()),
                    PaperBuiltInSupport.position(replacedState),
                    actor,
                    PaperBuiltInSupport.blockType(placedState),
                    PaperBlockStatePayloadCodec.encodeBlockPlace(
                        replacedState.getBlockData(),
                        placedState
                    )
                )
            );
        }
    }
}
