package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperContainerTransferListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "container_transfer");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<InventoryMoveItemEvent, Snapshot> inFlight =
        new PaperInFlightMap<>();

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

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(InventoryMoveItemEvent event) {
        Objects.requireNonNull(event, "event");

        var sourceInventory = event.getSource();
        var destinationInventory = event.getDestination();
        var initiatorInventory = event.getInitiator();
        var source = PaperContainerPayloadCodec.snapshotInventory(sourceInventory);
        var destination = PaperContainerPayloadCodec.snapshotInventory(destinationInventory);
        var initiator = PaperContainerPayloadCodec.snapshotInventory(initiatorInventory);
        var common = firstLocated(initiator, source, destination);
        var initiatorRole = initiatorRole(
            initiatorInventory,
            sourceInventory,
            destinationInventory
        );

        this.inFlight.put(
            event,
            new Snapshot(
                Instant.now(this.clock),
                this.serverKey,
                common == null ? null : common.worldKey(),
                common == null ? null : common.position(),
                source,
                destination,
                initiator,
                initiatorRole,
                PaperContainerPayloadCodec.snapshotItem(event.getItem())
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(InventoryMoveItemEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
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
                PaperContainerPayloadCodec.encodeTransfer(
                    snapshot.source(),
                    snapshot.destination(),
                    snapshot.initiator(),
                    snapshot.initiatorRole(),
                    snapshot.initialItem(),
                    PaperContainerPayloadCodec.snapshotItem(event.getItem())
                )
            )
        );
    }

    @Override
    public void clearInFlightState() {
        this.inFlight.clear();
    }

    int inFlightCount() {
        return this.inFlight.size();
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

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        @Nullable Key worldKey,
        @Nullable BlockPosition position,
        PaperContainerPayloadCodec.InventorySnapshot source,
        PaperContainerPayloadCodec.InventorySnapshot destination,
        PaperContainerPayloadCodec.InventorySnapshot initiator,
        String initiatorRole,
        CompoundTag initialItem
    ) {
    }
}
