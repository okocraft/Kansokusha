package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.inventory.ItemCraftedEvent;
import net.kyori.adventure.key.Key;
import net.minecraft.network.HashedStack;
import net.minecraft.stats.Stats;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.RemoteSlot;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.view.AnvilView;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerWorkstationAuditListener implements Listener {

    static final Key CRAFT_ITEM = Key.key("kansokusha", "craft_item");
    static final Key ANVIL_USE = Key.key("kansokusha", "anvil_use");
    static final Key SMITH_ITEM = Key.key("kansokusha", "smith_item");
    static final Key ENCHANT_ITEM = Key.key("kansokusha", "enchant_item");

    private static final String PACKET_LISTENER_CLASS =
        "net.minecraft.server.network.ServerGamePacketListenerImpl";
    private static final String CONTAINER_MENU_CLASS =
        "net.minecraft.world.inventory.AbstractContainerMenu";
    private static final String ENCHANTMENT_MENU_CLASS =
        "net.minecraft.world.inventory.EnchantmentMenu";
    private static final Field REMOTE_CARRIED_FIELD = findRemoteCarriedField();

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, PendingCraft> pendingCrafts =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Tracking> activeTracking =
        new ConcurrentHashMap<>();

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
    public void armCraft(CraftItemEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        var playerId = player.getUniqueId();
        if (event.getAction() == InventoryAction.NOTHING) {
            this.pendingCrafts.remove(playerId);
            return;
        }

        var inventory = event.getInventory();
        this.pendingCrafts.put(
            playerId,
            new PendingCraft(
                event.getRecipe(),
                cloneItems(inventory.getMatrix()),
                event.getClick().name(),
                event.getAction().name(),
                operationLocation(player, inventory),
                this.clock.instant()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordCrafted(ItemCraftedEvent event) {
        Objects.requireNonNull(event, "event");
        var player = event.getPlayer();
        var pending = this.pendingCrafts.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }

        var result = nonEmpty(event.getCraftedItem());
        if (result == null) {
            return;
        }

        this.submitAtLocation(
            CRAFT_ITEM,
            player,
            pending.location(),
            PaperBuiltInSupport.itemType(result),
            PaperWorkstationPayloadCodec.encodeCraft(
                pending.recipe(),
                pending.matrix(),
                result,
                pending.click(),
                pending.action()
            ),
            pending.occurredAt()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void armAnvil(InventoryClickEvent event) {
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
        var pending = new PendingClick(
            ClickKind.ANVIL,
            ANVIL_USE,
            player.getUniqueId(),
            inventory,
            snapshotContents(inventory),
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
            ),
            operationLocation(player, inventory),
            this.clock.instant()
        );
        this.arm(player, Boundary.CONTAINER_CLICK, () -> this.resolveClick(pending));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void armSmith(SmithItemEvent event) {
        Objects.requireNonNull(event, "event");
        if (
            !(event.getWhoClicked() instanceof Player player)
                || event.getAction() == InventoryAction.NOTHING
        ) {
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

        var pending = new PendingClick(
            ClickKind.SMITH,
            SMITH_ITEM,
            player.getUniqueId(),
            inventory,
            snapshotContents(inventory),
            PaperBuiltInSupport.itemType(result),
            PaperWorkstationPayloadCodec.encodeSmith(
                inventory.getRecipe(),
                inventory.getInputTemplate(),
                inventory.getInputEquipment(),
                inventory.getInputMineral(),
                result,
                event.getClick().name(),
                event.getAction().name()
            ),
            operationLocation(player, inventory),
            this.clock.instant()
        );
        this.arm(player, Boundary.CONTAINER_CLICK, () -> this.resolveClick(pending));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void armEnchant(EnchantItemEvent event) {
        Objects.requireNonNull(event, "event");
        var player = event.getEnchanter();
        if (!(player instanceof CraftPlayer craftPlayer)) {
            return;
        }

        var inventory = event.getView().getTopInventory();
        var pending = new PendingEnchant(
            player.getUniqueId(),
            player,
            inventory,
            snapshotContents(inventory),
            event.getItem().clone(),
            event.getExpLevelCost(),
            player.getLevel(),
            craftPlayer.getHandle().getStats().getValue(Stats.CUSTOM.get(Stats.ENCHANT_ITEM)),
            event.whichButton(),
            Map.copyOf(event.getEnchantsToAdd()),
            event.getEnchantBlock().getLocation().clone(),
            this.clock.instant()
        );
        this.arm(player, Boundary.CONTAINER_BUTTON, () -> this.resolveEnchant(pending));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void cleanup(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            this.cleanup(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void cleanup(PlayerQuitEvent event) {
        this.cleanup(event.getPlayer().getUniqueId());
    }

    private void resolveClick(PendingClick pending) {
        var after = snapshotContents(pending.inventory());
        var applied = switch (pending.kind()) {
            case ANVIL -> anvilApplied(pending.before(), after);
            case SMITH -> smithApplied(pending.before(), after);
        };
        if (!applied) {
            return;
        }

        this.submitAtLocation(
            pending.eventType(),
            player(pending.playerId()),
            pending.location(),
            pending.targetType(),
            pending.payload(),
            pending.occurredAt()
        );
    }

    private void resolveEnchant(PendingEnchant pending) {
        var after = snapshotContents(pending.inventory());
        if (!(pending.player() instanceof CraftPlayer craftPlayer)) {
            return;
        }

        var enchantStatAfter = craftPlayer.getHandle()
            .getStats()
            .getValue(Stats.CUSTOM.get(Stats.ENCHANT_ITEM));
        if (
            pending.enchantments().isEmpty()
                || enchantStatAfter <= pending.enchantStatBefore()
                || !enchantApplied(pending.before(), after)
        ) {
            return;
        }

        var consumedLevels = Math.max(0, pending.levelBefore() - pending.player().getLevel());
        this.submitAtLocation(
            ENCHANT_ITEM,
            pending.player(),
            pending.location(),
            PaperBuiltInSupport.itemType(pending.item()),
            PaperWorkstationPayloadCodec.encodeEnchant(
                pending.item(),
                pending.requiredLevel(),
                consumedLevels,
                pending.button(),
                pending.enchantments()
            ),
            pending.occurredAt()
        );
    }

    private Player player(UUID playerId) {
        var player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player == null) {
            throw new IllegalStateException("Workstation player disconnected before confirmation");
        }
        return player;
    }

    private void arm(Player player, Boundary boundary, Runnable confirmed) {
        if (!(player instanceof CraftPlayer craftPlayer)) {
            return;
        }

        var playerId = player.getUniqueId();
        this.detach(playerId);

        var menu = craftPlayer.getHandle().containerMenu;
        var tracking = new Tracking(
            this,
            playerId,
            menu,
            remoteCarried(menu),
            boundary,
            confirmed
        );
        this.activeTracking.put(playerId, tracking);
        try {
            setRemoteCarried(menu, tracking);
        } catch (RuntimeException exception) {
            this.activeTracking.remove(playerId, tracking);
            throw exception;
        }
    }

    private void cleanup(UUID playerId) {
        this.pendingCrafts.remove(playerId);
        this.detach(playerId);
    }

    private void detach(UUID playerId) {
        var tracking = this.activeTracking.remove(playerId);
        if (tracking != null) {
            tracking.detach();
        }
    }

    private void submitAtLocation(
        Key eventType,
        Player player,
        Location location,
        @Nullable Key targetType,
        EventPayload payload,
        Instant occurredAt
    ) {
        var world = Objects.requireNonNull(location.getWorld(), "workstation world");
        this.api.submit(new EventSubmission(
            eventType,
            PayloadGeneration.FIRST,
            occurredAt,
            this.serverKey,
            PaperKansokusha.key(world.getKey()),
            blockPosition(location),
            new PlayerActor(player.getUniqueId()),
            targetType,
            payload
        ));
    }

    static boolean anvilApplied(ItemStack[] before, ItemStack[] after) {
        return before.length >= 1
            && after.length >= 1
            && nonEmpty(before[0]) != null
            && nonEmpty(after[0]) == null;
    }

    static boolean smithApplied(ItemStack[] before, ItemStack[] after) {
        if (before.length < 3 || after.length < 3) {
            return false;
        }
        for (var slot = 0; slot < 3; slot++) {
            if (!decreasedByOne(before[slot], after[slot])) {
                return false;
            }
        }
        return true;
    }

    static boolean enchantApplied(ItemStack[] before, ItemStack[] after) {
        return before.length >= 1
            && after.length >= 1
            && !sameStack(before[0], after[0]);
    }

    private static boolean decreasedByOne(
        @Nullable ItemStack before,
        @Nullable ItemStack after
    ) {
        var previous = nonEmpty(before);
        if (previous == null) {
            return false;
        }
        var remaining = nonEmpty(after);
        if (previous.getAmount() == 1) {
            return remaining == null;
        }
        return remaining != null
            && previous.isSimilar(remaining)
            && remaining.getAmount() == previous.getAmount() - 1;
    }

    private static boolean sameStack(
        @Nullable ItemStack first,
        @Nullable ItemStack second
    ) {
        var left = nonEmpty(first);
        var right = nonEmpty(second);
        if (left == null || right == null) {
            return left == right;
        }
        return left.getAmount() == right.getAmount() && left.isSimilar(right);
    }

    private static ItemStack[] snapshotContents(Inventory inventory) {
        return cloneItems(inventory.getContents());
    }

    private static ItemStack[] cloneItems(ItemStack[] contents) {
        var snapshot = new ItemStack[contents.length];
        for (var index = 0; index < contents.length; index++) {
            var item = contents[index];
            snapshot[index] = item == null ? null : item.clone();
        }
        return snapshot;
    }

    private static Location operationLocation(Player player, Inventory inventory) {
        var location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            location = player.getLocation();
        }
        return location.clone();
    }

    private static BlockPosition blockPosition(Location location) {
        return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static @Nullable ItemStack nonEmpty(@Nullable ItemStack item) {
        return item == null || item.isEmpty() ? null : item;
    }

    private static boolean isPostBoundary(Boundary boundary) {
        return StackWalker.getInstance().walk(frames -> {
            var packetHandlerPresent = false;
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                var frame = iterator.next();
                var className = frame.getClassName();
                var methodName = frame.getMethodName();

                if (
                    methodName.equals("callEvent")
                        && (
                            className.equals("org.bukkit.plugin.RegisteredListener")
                                || className.equals("org.bukkit.plugin.SimplePluginManager")
                                || className.endsWith(".PaperEventManager")
                        )
                ) {
                    return false;
                }

                if (
                    boundary == Boundary.CONTAINER_CLICK
                        && className.equals(CONTAINER_MENU_CLASS)
                        && methodName.equals("clicked")
                ) {
                    return false;
                }

                if (
                    boundary == Boundary.CONTAINER_BUTTON
                        && className.equals(ENCHANTMENT_MENU_CLASS)
                        && methodName.equals("clickMenuButton")
                ) {
                    return false;
                }

                if (
                    className.equals(PACKET_LISTENER_CLASS)
                        && methodName.equals(boundary.packetMethod)
                ) {
                    packetHandlerPresent = true;
                }
            }
            return packetHandlerPresent;
        });
    }

    private static Field findRemoteCarriedField() {
        for (var field : AbstractContainerMenu.class.getDeclaredFields()) {
            if (field.getType() != RemoteSlot.class) {
                continue;
            }
            if (!field.trySetAccessible()) {
                throw new IllegalStateException(
                    "Cannot access AbstractContainerMenu RemoteSlot field"
                );
            }
            return field;
        }
        throw new IllegalStateException(
            "AbstractContainerMenu RemoteSlot field was not found"
        );
    }

    private static RemoteSlot remoteCarried(AbstractContainerMenu menu) {
        try {
            return (RemoteSlot) REMOTE_CARRIED_FIELD.get(menu);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                "Cannot read AbstractContainerMenu remote carried state",
                exception
            );
        }
    }

    private static void setRemoteCarried(AbstractContainerMenu menu, RemoteSlot remoteSlot) {
        try {
            REMOTE_CARRIED_FIELD.set(menu, remoteSlot);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                "Cannot replace AbstractContainerMenu remote carried state",
                exception
            );
        }
    }

    private enum ClickKind {
        ANVIL,
        SMITH
    }

    private enum Boundary {
        CONTAINER_CLICK("handleContainerClick"),
        CONTAINER_BUTTON("handleContainerButtonClick");

        private final String packetMethod;

        Boundary(String packetMethod) {
            this.packetMethod = packetMethod;
        }
    }

    private record PendingCraft(
        org.bukkit.inventory.Recipe recipe,
        ItemStack[] matrix,
        String click,
        String action,
        Location location,
        Instant occurredAt
    ) {
    }

    private record PendingClick(
        ClickKind kind,
        Key eventType,
        UUID playerId,
        Inventory inventory,
        ItemStack[] before,
        @Nullable Key targetType,
        EventPayload payload,
        Location location,
        Instant occurredAt
    ) {
    }

    private record PendingEnchant(
        UUID playerId,
        Player player,
        Inventory inventory,
        ItemStack[] before,
        ItemStack item,
        int requiredLevel,
        int levelBefore,
        int enchantStatBefore,
        int button,
        Map<org.bukkit.enchantments.Enchantment, Integer> enchantments,
        Location location,
        Instant occurredAt
    ) {
    }

    private static final class Tracking implements RemoteSlot {

        private final PaperPlayerWorkstationAuditListener owner;
        private final UUID playerId;
        private final AbstractContainerMenu menu;
        private final RemoteSlot delegate;
        private final Boundary boundary;
        private final Runnable confirmed;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Tracking(
            PaperPlayerWorkstationAuditListener owner,
            UUID playerId,
            AbstractContainerMenu menu,
            RemoteSlot delegate,
            Boundary boundary,
            Runnable confirmed
        ) {
            this.owner = owner;
            this.playerId = playerId;
            this.menu = menu;
            this.delegate = delegate;
            this.boundary = boundary;
            this.confirmed = confirmed;
        }

        @Override
        public void force(net.minecraft.world.item.ItemStack outgoing) {
            this.delegate.force(outgoing);
            this.complete();
        }

        @Override
        public void receive(HashedStack incoming) {
            this.delegate.receive(incoming);
        }

        @Override
        public boolean matches(net.minecraft.world.item.ItemStack local) {
            var matches = this.delegate.matches(local);
            this.complete();
            return matches;
        }

        private void complete() {
            if (
                this.completed.get()
                    || !isPostBoundary(this.boundary)
                    || !this.completed.compareAndSet(false, true)
            ) {
                return;
            }

            this.owner.activeTracking.remove(this.playerId, this);
            this.detach();
            this.confirmed.run();
        }

        private void detach() {
            if (remoteCarried(this.menu) == this) {
                setRemoteCarried(this.menu, this.delegate);
            }
        }
    }
}
