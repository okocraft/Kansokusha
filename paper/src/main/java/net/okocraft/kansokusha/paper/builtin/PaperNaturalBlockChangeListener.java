package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Scaffolding;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperNaturalBlockChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "natural_block_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperNaturalBlockChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperNaturalBlockChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperNaturalBlockChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperNaturalBlockChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordFade(BlockFadeEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        var preState = block.getBlockData();
        // Scaffolding at the maximum distance falls; entity_block_change records the falling block.
        if (preState instanceof Scaffolding scaffolding
            && scaffolding.getDistance() == scaffolding.getMaximumDistance()) {
            return;
        }

        this.submitIfChanged(
            block,
            preState,
            event.getNewState().getBlockData(),
            "block_fade",
            null,
            null
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordForm(BlockFormEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof BlockSpreadEvent || event instanceof EntityBlockFormEvent) {
            return;
        }

        var block = event.getBlock();
        this.submitIfChanged(
            block,
            block.getBlockData(),
            event.getNewState().getBlockData(),
            "block_form",
            null,
            null
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordGrow(BlockGrowEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof BlockFormEvent) {
            return;
        }

        var block = event.getBlock();
        this.submitIfChanged(
            block,
            block.getBlockData(),
            event.getNewState().getBlockData(),
            "block_grow",
            null,
            null
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordSpread(BlockSpreadEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        var source = event.getSource();
        this.submitIfChanged(
            block,
            block.getBlockData(),
            event.getNewState().getBlockData(),
            "block_spread",
            source.getType().name().toLowerCase(Locale.ROOT),
            position(source)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordLeavesDecay(LeavesDecayEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        this.submit(
            this.clock.instant(),
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            PaperBlockEventPayloadCodec.encodeLeavesDecay(block.getBlockData())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordMoistureChange(MoistureChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        this.submitIfChanged(
            block,
            block.getBlockData(),
            event.getNewState().getBlockData(),
            "moisture_change",
            null,
            null
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordStructureGrow(StructureGrowEvent event) {
        Objects.requireNonNull(event, "event");
        // Bone meal growth is recorded as block_fertilize.
        if (event.isFromBonemeal()) {
            return;
        }

        var occurredAt = this.clock.instant();
        var cause = event.getSpecies().name().toLowerCase(Locale.ROOT);
        var changedStates = new LinkedHashMap<BlockKey, BlockState>();
        for (var state : event.getBlocks()) {
            changedStates.put(
                new BlockKey(PaperKansokusha.key(state.getWorld().getKey()), position(state)),
                state
            );
        }

        for (var entry : changedStates.entrySet()) {
            var key = entry.getKey();
            var state = entry.getValue();
            var preState = state.getBlock().getBlockData();
            var postState = state.getBlockData();
            if (sameBlockData(preState, postState)) {
                continue;
            }
            this.submit(
                occurredAt,
                key.worldKey(),
                key.position(),
                PaperBlockEventPayloadCodec.encodeNaturalChange(
                    preState,
                    postState,
                    "structure_grow",
                    cause,
                    null
                )
            );
        }
    }

    private void submitIfChanged(
        Block block,
        BlockData preState,
        BlockData postState,
        String sourceEvent,
        @Nullable String cause,
        @Nullable BlockPosition sourcePosition
    ) {
        if (sameBlockData(preState, postState)) {
            return;
        }
        this.submit(
            this.clock.instant(),
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            PaperBlockEventPayloadCodec.encodeNaturalChange(
                preState,
                postState,
                sourceEvent,
                cause,
                sourcePosition
            )
        );
    }

    private void submit(Instant occurredAt, Key worldKey, BlockPosition position, EventPayload payload) {
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            occurredAt,
            this.serverKey,
            worldKey,
            position,
            null,
            payload
        ));
    }

    private static boolean sameBlockData(BlockData first, BlockData second) {
        return first.getAsString().equals(second.getAsString());
    }
}
