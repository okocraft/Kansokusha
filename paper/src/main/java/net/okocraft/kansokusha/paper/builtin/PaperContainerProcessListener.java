package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
import java.util.List;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperContainerProcessListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "container_process");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordFurnace(FurnaceSmeltEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        var inventory = blockInventory(block);
        ItemStack fuel = null;
        if (inventory instanceof org.bukkit.inventory.FurnaceInventory furnaceInventory) {
            fuel = furnaceInventory.getFuel();
        }

        this.submit(
            block,
            PaperBuiltInSupport.itemType(event.getResult()),
            "furnace_smelt",
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getSource())),
            PaperContainerPayloadCodec.snapshotItem(null),
            PaperContainerPayloadCodec.snapshotItem(fuel),
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getResult())),
            inventory
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordBrew(BrewEvent event) {
        Objects.requireNonNull(event, "event");

        var contents = event.getContents();
        this.submit(
            event.getBlock(),
            PaperBuiltInSupport.itemType(contents.getIngredient()),
            "brew",
            PaperContainerPayloadCodec.snapshotItems(
                new ItemStack[]{contents.getItem(0), contents.getItem(1), contents.getItem(2)}
            ),
            PaperContainerPayloadCodec.snapshotItem(contents.getIngredient()),
            PaperContainerPayloadCodec.snapshotItem(contents.getFuel()),
            PaperContainerPayloadCodec.snapshotItems(event.getResults()),
            contents
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordCook(BlockCookEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof FurnaceSmeltEvent) {
            return;
        }

        this.submit(
            event.getBlock(),
            PaperBuiltInSupport.itemType(event.getResult()),
            "campfire_cook",
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getSource())),
            PaperContainerPayloadCodec.snapshotItem(null),
            PaperContainerPayloadCodec.snapshotItem(null),
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getResult())),
            blockInventory(event.getBlock())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordCrafter(CrafterCraftEvent event) {
        Objects.requireNonNull(event, "event");

        var inventory = blockInventory(event.getBlock());
        var inputs = inventory == null
            ? new ItemStack[0]
            : inventory.getContents();
        this.submit(
            event.getBlock(),
            PaperBuiltInSupport.itemType(event.getResult()),
            "crafter_craft",
            PaperContainerPayloadCodec.snapshotItems(inputs),
            PaperContainerPayloadCodec.snapshotItem(null),
            PaperContainerPayloadCodec.snapshotItem(null),
            PaperContainerPayloadCodec.snapshotItems(List.of(event.getResult())),
            inventory
        );
    }

    private void submit(
        Block block,
        @Nullable Key targetType,
        String processKind,
        ListTag inputItems,
        CompoundTag ingredient,
        CompoundTag fuel,
        ListTag results,
        @Nullable Inventory inventory
    ) {
        var container = PaperContainerPayloadCodec.snapshotBlockContainer(block, inventory);
        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                PaperKansokusha.key(block.getWorld().getKey()),
                PaperBuiltInSupport.position(block),
                container.holder(),
                targetType,
                PaperContainerPayloadCodec.encodeProcess(
                    processKind,
                    container,
                    inputItems,
                    ingredient,
                    fuel,
                    results
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
}
