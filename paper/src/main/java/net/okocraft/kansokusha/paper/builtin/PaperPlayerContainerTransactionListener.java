package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerContainerTransactionListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "container_transaction");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperPlayerContainerTransactionListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerContainerTransactionListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerContainerTransactionListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPlayerContainerTransactionListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordClick(InventoryClickEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        var top = event.getView().getTopInventory();
        var container = locatedContainer(top);
        if (container == null) {
            return;
        }

        var clicked = event.getClickedInventory();
        if (clicked == null || event.getAction() == InventoryAction.NOTHING) {
            return;
        }

        var clickedScope = clicked == top ? "container" : "player";
        var direction = direction(event.getAction(), clickedScope);
        if (direction == null) {
            return;
        }

        var current = event.getCurrentItem();
        var cursor = event.getCursor();
        var exchangeItem = exchangeItem(event, player);
        var targetItem = nonEmpty(current)
            ? current
            : nonEmpty(cursor)
                ? cursor
                : exchangeItem;
        var target = PaperBuiltInSupport.itemType(targetItem);
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            container.worldKey(),
            container.position(),
            new PlayerActor(player.getUniqueId()),
            target,
            PaperAuditGapPayloadCodec.encodeContainerClick(
                container,
                event.getAction().name(),
                event.getClick().name(),
                clickedScope,
                event.getSlot(),
                event.getRawSlot(),
                direction,
                current,
                cursor,
                exchangeItem,
                event.getHotbarButton()
            )
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordDrag(InventoryDragEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        var top = event.getView().getTopInventory();
        var container = locatedContainer(top);
        if (container == null) {
            return;
        }

        var topSize = top.getSize();
        if (event.getRawSlots().stream().noneMatch(slot -> slot >= 0 && slot < topSize)) {
            return;
        }

        var oldCursor = event.getOldCursor();
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            container.worldKey(),
            container.position(),
            new PlayerActor(player.getUniqueId()),
            PaperBuiltInSupport.itemType(oldCursor),
            PaperAuditGapPayloadCodec.encodeContainerDrag(
                container,
                event.getType().name(),
                oldCursor,
                event.getNewItems(),
                topSize
            )
        ));
    }

    private static @Nullable PaperContainerPayloadCodec.InventorySnapshot locatedContainer(
        Inventory inventory
    ) {
        if (inventory.getHolder() instanceof Player) {
            return null;
        }
        var snapshot = PaperContainerPayloadCodec.snapshotInventory(inventory);
        return snapshot.worldKey() == null || snapshot.position() == null ? null : snapshot;
    }

    private static @Nullable String direction(InventoryAction action, String clickedScope) {
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            return "mixed";
        }
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return clickedScope.equals("container")
                ? "container_to_player"
                : "player_to_container";
        }
        if (!clickedScope.equals("container")) {
            return null;
        }

        var name = action.name();
        if (name.startsWith("PICKUP")) {
            return "container_to_player";
        }
        if (name.startsWith("PLACE")) {
            return "player_to_container";
        }
        if (name.startsWith("DROP")) {
            return "container_to_world";
        }
        if (
            action == InventoryAction.SWAP_WITH_CURSOR
                || action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.HOTBAR_MOVE_AND_READD
        ) {
            return "exchange";
        }
        return null;
    }

    private static @Nullable ItemStack exchangeItem(InventoryClickEvent event, Player player) {
        var action = event.getAction();
        if (
            action != InventoryAction.HOTBAR_SWAP
                && action != InventoryAction.HOTBAR_MOVE_AND_READD
        ) {
            return null;
        }

        var hotbarButton = event.getHotbarButton();
        if (hotbarButton >= 0) {
            return player.getInventory().getItem(hotbarButton);
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return player.getInventory().getItemInOffHand();
        }
        return null;
    }

    private static boolean nonEmpty(@Nullable ItemStack item) {
        return item != null && !item.isEmpty();
    }
}
