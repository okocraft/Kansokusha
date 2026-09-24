package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperContainerProcessListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "container_process");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<Event, Snapshot> inFlight = new PaperInFlightMap<>();

    private PaperContainerProcessListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperContainerProcessListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperContainerProcessListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperContainerProcessListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureFurnace(FurnaceSmeltEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        var inventory = blockInventory(block);
        ItemStack fuel = null;
        if (inventory instanceof org.bukkit.inventory.FurnaceInventory furnaceInventory) {
            fuel = furnaceInventory.getFuel();
        }

        this.capture(
            event,
            block,
            "furnace_smelt",
            "org.bukkit.event.inventory.FurnaceSmeltEvent",
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getSource())),
            PaperContainerPayloadCodec.snapshotItem(null),
            PaperContainerPayloadCodec.snapshotItem(fuel),
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getResult())),
            inventory
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureBrew(BrewEvent event) {
        Objects.requireNonNull(event, "event");

        var contents = event.getContents();
        this.capture(
            event,
            event.getBlock(),
            "brew",
            "org.bukkit.event.inventory.BrewEvent",
            PaperContainerPayloadCodec.snapshotItems(
                new ItemStack[]{contents.getItem(0), contents.getItem(1), contents.getItem(2)}
            ),
            PaperContainerPayloadCodec.snapshotItem(contents.getIngredient()),
            PaperContainerPayloadCodec.snapshotItem(contents.getFuel()),
            PaperContainerPayloadCodec.snapshotItems(event.getResults()),
            contents
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureCook(BlockCookEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof FurnaceSmeltEvent) {
            return;
        }

        this.capture(
            event,
            event.getBlock(),
            "campfire_cook",
            "org.bukkit.event.block.BlockCookEvent",
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getSource())),
            PaperContainerPayloadCodec.snapshotItem(null),
            PaperContainerPayloadCodec.snapshotItem(null),
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getResult())),
            blockInventory(event.getBlock())
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureCrafter(CrafterCraftEvent event) {
        Objects.requireNonNull(event, "event");

        var inventory = blockInventory(event.getBlock());
        var inputs = inventory == null
            ? new ItemStack[0]
            : inventory.getContents();
        this.capture(
            event,
            event.getBlock(),
            "crafter_craft",
            "org.bukkit.event.block.CrafterCraftEvent",
            PaperContainerPayloadCodec.snapshotItems(inputs),
            PaperContainerPayloadCodec.snapshotItem(null),
            PaperContainerPayloadCodec.snapshotItem(null),
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getResult())),
            inventory
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeFurnace(FurnaceSmeltEvent event) {
        this.finalizeEvent(
            event,
            event.isCancelled(),
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getResult()))
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeBrew(BrewEvent event) {
        this.finalizeEvent(
            event,
            event.isCancelled(),
            PaperContainerPayloadCodec.snapshotItems(event.getResults())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeCook(BlockCookEvent event) {
        if (event instanceof FurnaceSmeltEvent) {
            return;
        }
        this.finalizeEvent(
            event,
            event.isCancelled(),
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getResult()))
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeCrafter(CrafterCraftEvent event) {
        this.finalizeEvent(
            event,
            event.isCancelled(),
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getResult()))
        );
    }

    @Override
    public void clearInFlightState() {
        this.inFlight.clear();
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    private void capture(
        Event event,
        Block block,
        String processKind,
        String sourceEvent,
        ListTag inputItems,
        CompoundTag ingredient,
        CompoundTag fuel,
        ListTag initialResults,
        @Nullable Inventory inventory
    ) {
        this.inFlight.put(
            event,
            new Snapshot(
                Instant.now(this.clock),
                this.serverKey,
                PaperKansokusha.key(block.getWorld().getKey()),
                PaperBuiltInSupport.position(block),
                processKind,
                sourceEvent,
                PaperContainerPayloadCodec.snapshotBlockContainer(block, inventory),
                inputItems,
                ingredient,
                fuel,
                initialResults
            )
        );
    }

    private void finalizeEvent(Event event, boolean cancelled, ListTag finalResults) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || cancelled) {
            return;
        }

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                snapshot.occurredAt(),
                snapshot.serverKey(),
                snapshot.worldKey(),
                snapshot.position(),
                null,
                PaperContainerPayloadCodec.encodeProcess(
                    snapshot.processKind(),
                    snapshot.sourceEvent(),
                    snapshot.container(),
                    snapshot.inputItems(),
                    snapshot.ingredient(),
                    snapshot.fuel(),
                    snapshot.initialResults(),
                    finalResults
                )
            )
        );
    }

    private static @Nullable Inventory blockInventory(Block block) {
        var state = block.getState();
        if (state instanceof InventoryHolder holder) {
            return holder.getInventory();
        }
        return null;
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        String processKind,
        String sourceEvent,
        PaperContainerPayloadCodec.InventorySnapshot container,
        ListTag inputItems,
        CompoundTag ingredient,
        CompoundTag fuel,
        ListTag initialResults
    ) {
    }
}
