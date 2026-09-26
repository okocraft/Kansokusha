package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockFertilizeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_fertilize");
    static final String SOURCE_EVENT = "block_fertilize";

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperBlockFertilizeListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockFertilizeListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockFertilizeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperBlockFertilizeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(BlockFertilizeEvent event) {
        Objects.requireNonNull(event, "event");

        var occurredAt = this.clock.instant();
        var player = event.getPlayer();
        var subject = player == null ? null : new PlayerSubject(player.getUniqueId());
        var sourcePosition = position(event.getBlock());

        var changedStates = new LinkedHashMap<BlockKey, BlockState>();
        for (var state : event.getBlocks()) {
            changedStates.put(blockKey(state), state);
        }

        for (var entry : changedStates.entrySet()) {
            var key = entry.getKey();
            var state = entry.getValue();
            var preState = state.getBlock().getBlockData();
            var postState = state.getBlockData();
            if (sameBlockData(preState, postState)) {
                continue;
            }

            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                this.serverKey,
                key.worldKey(),
                key.position(),
                subject,
                PaperBlockEventPayloadCodec.encodeFertilize(
                    preState,
                    postState,
                    SOURCE_EVENT,
                    sourcePosition
                )
            ));
        }
    }

    private static BlockKey blockKey(BlockState state) {
        return new BlockKey(
            PaperKansokusha.key(state.getWorld().getKey()),
            position(state)
        );
    }

    private static boolean sameBlockData(BlockData first, BlockData second) {
        return first.getAsString().equals(second.getAsString());
    }
}
