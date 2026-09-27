package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.HashedStack;
import net.minecraft.world.inventory.RemoteSlot;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerContainerTransactionListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "container_transaction");

    private static final String PACKET_LISTENER_CLASS =
        "net.minecraft.server.network.ServerGamePacketListenerImpl";
    private static final String CONTAINER_MENU_CLASS =
        "net.minecraft.world.inventory.AbstractContainerMenu";
    private static final Field REMOTE_CARRIED_FIELD = findRemoteCarriedField();

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Tracking> activeTracking = new ConcurrentHashMap<>();

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
        if (container == null || event.getClickedInventory() == null) {
            return;
        }

        var clickedScope = event.getClickedInventory() == top ? "container" : "player";
        this.arm(
            player,
            top,
            container,
            new OperationContext(
                "click",
                event.getAction().name(),
                event.getClick().name(),
                clickedScope,
                event.getSlot(),
                event.getRawSlot(),
                event.getHotbarButton()
            )
        );
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

        this.arm(
            player,
            top,
            container,
            new OperationContext(
                "drag",
                event.getType().name(),
                null,
                null,
                -1,
                -1,
                -1
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void cleanup(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            this.detach(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void cleanup(PlayerQuitEvent event) {
        this.detach(event.getPlayer().getUniqueId());
    }

    private void arm(
        Player player,
        Inventory top,
        PaperContainerPayloadCodec.InventorySnapshot container,
        OperationContext context
    ) {
        if (!(player instanceof CraftPlayer craftPlayer)) {
            return;
        }

        var playerId = player.getUniqueId();
        this.detach(playerId);

        var menu = craftPlayer.getHandle().containerMenu;
        var pending = new PendingTransaction(
            playerId,
            container,
            top,
            snapshotContents(top),
            context,
            this.clock.instant(),
            UUID.randomUUID()
        );
        var tracking = new Tracking(this, menu, pending, remoteCarried(menu));
        this.activeTracking.put(playerId, tracking);
        try {
            setRemoteCarried(menu, tracking);
        } catch (RuntimeException exception) {
            this.activeTracking.remove(playerId, tracking);
            throw exception;
        }
    }

    private void detach(UUID playerId) {
        var tracking = this.activeTracking.remove(playerId);
        if (tracking != null) {
            tracking.detach();
        }
    }

    private void resolve(PendingTransaction pending) {
        var after = snapshotContents(pending.inventory());
        for (var delta : diffContents(pending.before(), after)) {
            var direction = delta.amountDelta() > 0
                ? "added_to_container"
                : "removed_from_container";
            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                pending.occurredAt(),
                this.serverKey,
                pending.container().worldKey(),
                pending.container().position(),
                new PlayerActor(pending.playerId()),
                PaperBuiltInSupport.itemType(delta.item()),
                PaperAuditGapPayloadCodec.encodeContainerDelta(
                    pending.container(),
                    pending.transactionId(),
                    pending.context().operation(),
                    pending.context().action(),
                    pending.context().click(),
                    pending.context().clickedScope(),
                    pending.context().slot(),
                    pending.context().rawSlot(),
                    pending.context().hotbarButton(),
                    direction,
                    delta.item(),
                    delta.amountDelta()
                )
            ));
        }
    }

    static List<ContainerDelta> diffContents(ItemStack[] before, ItemStack[] after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");

        var deltas = new ArrayList<MutableDelta>();
        for (var item : before) {
            merge(deltas, item, -1);
        }
        for (var item : after) {
            merge(deltas, item, 1);
        }

        var result = new ArrayList<ContainerDelta>(deltas.size());
        for (var delta : deltas) {
            if (delta.amount == 0) {
                continue;
            }
            result.add(new ContainerDelta(delta.item.clone(), delta.amount));
        }
        return List.copyOf(result);
    }

    private static void merge(
        List<MutableDelta> deltas,
        @Nullable ItemStack item,
        int direction
    ) {
        if (item == null || item.isEmpty()) {
            return;
        }

        for (var delta : deltas) {
            if (delta.item.isSimilar(item)) {
                delta.amount += direction * item.getAmount();
                return;
            }
        }

        var template = item.clone();
        template.setAmount(1);
        deltas.add(new MutableDelta(template, direction * item.getAmount()));
    }

    private static ItemStack[] snapshotContents(Inventory inventory) {
        var contents = inventory.getContents();
        var snapshot = new ItemStack[contents.length];
        for (var index = 0; index < contents.length; index++) {
            var item = contents[index];
            snapshot[index] = item == null ? null : item.clone();
        }
        return snapshot;
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

    private static boolean isPostVanillaContainerClick() {
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
                    className.equals(CONTAINER_MENU_CLASS)
                        && methodName.equals("clicked")
                ) {
                    return false;
                }

                if (
                    className.equals(PACKET_LISTENER_CLASS)
                        && methodName.equals("handleContainerClick")
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

    record ContainerDelta(ItemStack item, int amountDelta) {
        ContainerDelta {
            Objects.requireNonNull(item, "item");
            if (amountDelta == 0) {
                throw new IllegalArgumentException("amountDelta must not be zero");
            }
        }
    }

    private record OperationContext(
        String operation,
        String action,
        @Nullable String click,
        @Nullable String clickedScope,
        int slot,
        int rawSlot,
        int hotbarButton
    ) {
    }

    private record PendingTransaction(
        UUID playerId,
        PaperContainerPayloadCodec.InventorySnapshot container,
        Inventory inventory,
        ItemStack[] before,
        OperationContext context,
        Instant occurredAt,
        UUID transactionId
    ) {
    }

    private static final class MutableDelta {

        private final ItemStack item;
        private int amount;

        private MutableDelta(ItemStack item, int amount) {
            this.item = item;
            this.amount = amount;
        }
    }

    private static final class Tracking implements RemoteSlot {

        private final PaperPlayerContainerTransactionListener owner;
        private final AbstractContainerMenu menu;
        private final PendingTransaction pending;
        private final RemoteSlot delegate;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Tracking(
            PaperPlayerContainerTransactionListener owner,
            AbstractContainerMenu menu,
            PendingTransaction pending,
            RemoteSlot delegate
        ) {
            this.owner = owner;
            this.menu = menu;
            this.pending = pending;
            this.delegate = delegate;
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
                    || !isPostVanillaContainerClick()
                    || !this.completed.compareAndSet(false, true)
            ) {
                return;
            }

            this.owner.activeTracking.remove(this.pending.playerId(), this);
            this.detach();
            this.owner.resolve(this.pending);
        }

        private void detach() {
            if (remoteCarried(this.menu) == this) {
                setRemoteCarried(this.menu, this.delegate);
            }
        }
    }

}
