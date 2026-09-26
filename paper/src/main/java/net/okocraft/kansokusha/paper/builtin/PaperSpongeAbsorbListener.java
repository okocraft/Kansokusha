package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperSpongeAbsorbListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "sponge_absorb");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperSpongeAbsorbListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperSpongeAbsorbListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperSpongeAbsorbListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperSpongeAbsorbListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(SpongeAbsorbEvent event) {
        Objects.requireNonNull(event, "event");

        var occurredAt = this.clock.instant();
        var spongeOrigin = position(event.getBlock());
        var absorbedBlocks = new LinkedHashMap<BlockKey, BlockData>();
        for (var state : event.getBlocks()) {
            absorbedBlocks.putIfAbsent(blockKey(state), state.getBlock().getBlockData());
        }

        for (var entry : absorbedBlocks.entrySet()) {
            var key = entry.getKey();
            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                this.serverKey,
                key.worldKey(),
                key.position(),
                null,
                PaperBlockEventPayloadCodec.encodeSpongeAbsorb(entry.getValue(), spongeOrigin)
            ));
        }
    }

    private static BlockKey blockKey(BlockState state) {
        return new BlockKey(
            PaperKansokusha.key(state.getWorld().getKey()),
            position(state)
        );
    }
}
