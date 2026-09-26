package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperContainerTransferListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "container_transfer");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

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

        var sourceInventory = event.getSource();
        var destinationInventory = event.getDestination();
        var initiatorInventory = event.getInitiator();
        var source = PaperContainerPayloadCodec.snapshotInventory(sourceInventory);
        var destination = PaperContainerPayloadCodec.snapshotInventory(destinationInventory);
        var initiator = PaperContainerPayloadCodec.snapshotInventory(initiatorInventory);
        var common = firstLocated(initiator, source, destination);

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                common == null ? null : common.worldKey(),
                common == null ? null : common.position(),
                initiator.holder(),
                PaperBuiltInSupport.itemType(event.getItem()),
                PaperContainerPayloadCodec.encodeTransfer(
                    source,
                    destination,
                    initiator,
                    initiatorRole(initiatorInventory, sourceInventory, destinationInventory),
                    PaperContainerPayloadCodec.snapshotItem(event.getItem())
                )
            )
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
}
