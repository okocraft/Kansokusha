package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records hopper-style item transfers.
 *
 * <p>Hoppers and hopper minecarts fire the event for every move attempt, which dominates the database
 * (tens of millions of events per hour on a busy server). Attempts with the same source, destination,
 * initiator and item are therefore summed into one event that carries the number of attempts and the
 * total item amount. The event is stamped with the start of its window.</p>
 *
 * <p>Moves from one hopper to another only pass items along a chain, and the moves into and out of the
 * chain are recorded as well, so they are summed over the long {@link #CHAIN_WINDOW}. Moves between a
 * hopper and any other container change what that container holds, so they use the short
 * {@link #CONTAINER_WINDOW} to keep their times precise.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperContainerTransferListener implements Listener, PaperBufferedListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "container_transfer");
    static final Duration CHAIN_WINDOW = Duration.ofMinutes(10);
    static final Duration CONTAINER_WINDOW = Duration.ofSeconds(15);

    // Bounds the memory used by the aggregation; transfers beyond it are submitted one by one.
    static final int MAX_PENDING = 100_000;

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final ConcurrentHashMap<TransferKey, Pending> pending = new ConcurrentHashMap<>();

    private PaperContainerTransferListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperContainerTransferListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperContainerTransferListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperContainerTransferListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(InventoryMoveItemEvent event) {
        Objects.requireNonNull(event, "event");

        var item = event.getItem();
        var amount = item.getAmount();
        var key = key(event);
        var now = this.clock.instant();

        while (true) {
            var existing = this.pending.get(key);
            if (existing != null) {
                if (!existing.isDue(now) && existing.add(amount)) {
                    return;
                }
                this.complete(key, existing);
                continue;
            }

            var created = this.create(event, now, amount);
            if (this.pending.size() >= MAX_PENDING) {
                this.submit(created);
                return;
            }
            // The stored key must not reference the live item of the event.
            var stored = new TransferKey(
                key.source(), key.destination(), key.initiator(), key.initiatorRole(), new SimilarItem(created.item)
            );
            if (this.pending.putIfAbsent(stored, created) == null) {
                return;
            }
        }
    }

    @Override
    public void flushDue() {
        var now = this.clock.instant();
        this.pending.forEach((key, entry) -> {
            if (entry.isDue(now)) {
                this.complete(key, entry);
            }
        });
    }

    @Override
    public void flushAll() {
        this.pending.forEach(this::complete);
    }

    private void complete(TransferKey key, Pending entry) {
        if (entry.close()) {
            this.submit(entry);
        }
        this.pending.remove(key, entry);
    }

    private Pending create(InventoryMoveItemEvent event, Instant now, int amount) {
        var sourceInventory = event.getSource();
        var destinationInventory = event.getDestination();
        var initiatorInventory = event.getInitiator();
        var source = PaperContainerPayloadCodec.snapshotInventory(sourceInventory);
        var destination = PaperContainerPayloadCodec.snapshotInventory(destinationInventory);
        var initiator = PaperContainerPayloadCodec.snapshotInventory(initiatorInventory);
        var item = event.getItem().clone();
        item.setAmount(1);
        var chain = sourceInventory.getType() == InventoryType.HOPPER
            && destinationInventory.getType() == InventoryType.HOPPER;
        return new Pending(
            now,
            chain ? CHAIN_WINDOW : CONTAINER_WINDOW,
            source,
            destination,
            initiator,
            initiatorRole(initiatorInventory, sourceInventory, destinationInventory),
            item,
            amount
        );
    }

    private void submit(Pending entry) {
        var common = firstLocated(entry.initiator, entry.source, entry.destination);
        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                entry.start,
                this.serverKey,
                common == null ? null : common.worldKey(),
                common == null ? null : common.position(),
                entry.initiator.holder(),
                PaperBuiltInSupport.itemType(entry.item),
                PaperContainerPayloadCodec.encodeTransfer(
                    entry.source,
                    entry.destination,
                    entry.initiator,
                    entry.initiatorRole,
                    PaperContainerPayloadCodec.snapshotItem(entry.item),
                    entry.attempts,
                    entry.amount
                )
            )
        );
    }

    private static TransferKey key(InventoryMoveItemEvent event) {
        var source = event.getSource();
        var destination = event.getDestination();
        var initiator = event.getInitiator();
        return new TransferKey(
            InventoryKey.of(source),
            InventoryKey.of(destination),
            InventoryKey.of(initiator),
            initiatorRole(initiator, source, destination),
            new SimilarItem(event.getItem())
        );
    }

    private static String initiatorRole(
        Inventory initiator,
        Inventory source,
        Inventory destination
    ) {
        if (initiator == source) {
            return "source";
        }
        if (initiator == destination) {
            return "destination";
        }
        return "other";
    }

    private static @Nullable PaperContainerPayloadCodec.InventorySnapshot firstLocated(
        PaperContainerPayloadCodec.InventorySnapshot... inventories
    ) {
        for (var inventory : inventories) {
            if (inventory.worldKey() != null && inventory.position() != null) {
                return inventory;
            }
        }
        return null;
    }

    private record TransferKey(
        InventoryKey source,
        InventoryKey destination,
        InventoryKey initiator,
        String initiatorRole,
        SimilarItem item
    ) {
    }

    /**
     * Identifies an inventory by what is recorded about it: its type, holder entity and block position.
     */
    private record InventoryKey(
        InventoryType type,
        @Nullable UUID entityId,
        @Nullable UUID worldId,
        int x,
        int y,
        int z
    ) {

        private static InventoryKey of(Inventory inventory) {
            // getHolder(false) avoids copying the block state of the holder.
            var holder = inventory.getHolder(false);
            var entityId = holder instanceof Entity entity ? entity.getUniqueId() : null;
            var location = inventory.getLocation();
            if (location == null || location.getWorld() == null) {
                return new InventoryKey(inventory.getType(), entityId, null, 0, 0, 0);
            }
            return new InventoryKey(
                inventory.getType(),
                entityId,
                location.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
            );
        }
    }

    private static final class Pending {

        private final Instant start;
        private final Duration window;
        private final PaperContainerPayloadCodec.InventorySnapshot source;
        private final PaperContainerPayloadCodec.InventorySnapshot destination;
        private final PaperContainerPayloadCodec.InventorySnapshot initiator;
        private final String initiatorRole;
        private final ItemStack item;
        private int attempts = 1;
        private long amount;
        private boolean closed;

        private Pending(
            Instant start,
            Duration window,
            PaperContainerPayloadCodec.InventorySnapshot source,
            PaperContainerPayloadCodec.InventorySnapshot destination,
            PaperContainerPayloadCodec.InventorySnapshot initiator,
            String initiatorRole,
            ItemStack item,
            long amount
        ) {
            this.start = start;
            this.window = window;
            this.source = source;
            this.destination = destination;
            this.initiator = initiator;
            this.initiatorRole = initiatorRole;
            this.item = item;
            this.amount = amount;
        }

        private boolean isDue(Instant now) {
            return !now.isBefore(this.start.plus(this.window));
        }

        private synchronized boolean add(long amount) {
            if (this.closed) {
                return false;
            }
            this.attempts++;
            this.amount += amount;
            return true;
        }

        /** Returns whether this call closed the entry; only the caller that did may submit it. */
        private synchronized boolean close() {
            if (this.closed) {
                return false;
            }
            this.closed = true;
            return true;
        }
    }
}
