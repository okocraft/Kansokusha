package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.view.AnvilView;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerWorkstationAuditListener implements Listener {

    static final Key CRAFT_ITEM = Key.key("kansokusha", "craft_item");
    static final Key ANVIL_USE = Key.key("kansokusha", "anvil_use");
    static final Key SMITH_ITEM = Key.key("kansokusha", "smith_item");
    static final Key ENCHANT_ITEM = Key.key("kansokusha", "enchant_item");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperPlayerWorkstationAuditListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerWorkstationAuditListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerWorkstationAuditListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, CRAFT_ITEM);
        PaperBuiltInSupport.register(api, ANVIL_USE);
        PaperBuiltInSupport.register(api, SMITH_ITEM);
        PaperBuiltInSupport.register(api, ENCHANT_ITEM);
        return new PaperPlayerWorkstationAuditListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordCraft(CraftItemEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        var inventory = event.getInventory();
        var result = nonEmpty(event.getCurrentItem());
        if (result == null) {
            result = nonEmpty(inventory.getResult());
        }
        if (result == null) {
            return;
        }

        this.submitAtInventory(
            CRAFT_ITEM,
            player,
            inventory,
            PaperBuiltInSupport.itemType(result),
            PaperWorkstationPayloadCodec.encodeCraft(
                event.getRecipe(),
                inventory.getMatrix(),
                result,
                event.getClick().name(),
                event.getAction().name()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordAnvil(InventoryClickEvent event) {
        Objects.requireNonNull(event, "event");
        if (
            !(event.getWhoClicked() instanceof Player player)
                || !(event.getView() instanceof AnvilView view)
                || event.getSlotType() != InventoryType.SlotType.RESULT
                || event.getClickedInventory() != view.getTopInventory()
                || event.getAction() == InventoryAction.NOTHING
        ) {
            return;
        }

        var result = nonEmpty(event.getCurrentItem());
        if (result == null) {
            return;
        }

        AnvilInventory inventory = view.getTopInventory();
        this.submitAtInventory(
            ANVIL_USE,
            player,
            inventory,
            PaperBuiltInSupport.itemType(result),
            PaperWorkstationPayloadCodec.encodeAnvil(
                inventory.getFirstItem(),
                inventory.getSecondItem(),
                result,
                view.getRenameText(),
                view.getRepairItemCountCost(),
                view.getRepairCost(),
                event.getClick().name(),
                event.getAction().name()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordSmith(SmithItemEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        SmithingInventory inventory = event.getInventory();
        var result = nonEmpty(event.getCurrentItem());
        if (result == null) {
            result = nonEmpty(inventory.getResult());
        }
        if (result == null) {
            return;
        }

        this.submitAtInventory(
            SMITH_ITEM,
            player,
            inventory,
            PaperBuiltInSupport.itemType(result),
            PaperWorkstationPayloadCodec.encodeSmith(
                inventory.getRecipe(),
                inventory.getInputTemplate(),
                inventory.getInputEquipment(),
                inventory.getInputMineral(),
                result,
                event.getClick().name(),
                event.getAction().name()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordEnchant(EnchantItemEvent event) {
        Objects.requireNonNull(event, "event");
        var player = event.getEnchanter();
        var item = event.getItem();

        this.api.submit(new EventSubmission(
            ENCHANT_ITEM,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(event.getEnchantBlock().getWorld().getKey()),
            PaperBuiltInSupport.position(event.getEnchantBlock()),
            new PlayerActor(player.getUniqueId()),
            PaperBuiltInSupport.itemType(item),
            PaperWorkstationPayloadCodec.encodeEnchant(
                item,
                event.getExpLevelCost(),
                event.whichButton(),
                event.getEnchantsToAdd()
            )
        ));
    }

    private void submitAtInventory(
        Key eventType,
        Player player,
        Inventory inventory,
        @Nullable Key targetType,
        EventPayload payload
    ) {
        var location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            location = player.getLocation();
        }

        this.api.submit(new EventSubmission(
            eventType,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(Objects.requireNonNull(location.getWorld()).getKey()),
            blockPosition(location),
            new PlayerActor(player.getUniqueId()),
            targetType,
            payload
        ));
    }

    private static BlockPosition blockPosition(Location location) {
        return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static @Nullable ItemStack nonEmpty(@Nullable ItemStack item) {
        return item == null || item.isEmpty() ? null : item;
    }
}
