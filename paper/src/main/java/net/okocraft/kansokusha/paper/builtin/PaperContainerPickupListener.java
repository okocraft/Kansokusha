package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperContainerPickupListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "container_pickup");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<InventoryPickupItemEvent, Snapshot> inFlight =
        new PaperInFlightMap<>();

    private PaperContainerPickupListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperContainerPickupListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperContainerPickupListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperContainerPickupListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(InventoryPickupItemEvent event) {
        Objects.requireNonNull(event, "event");

        var inventory = PaperContainerPayloadCodec.snapshotInventory(event.getInventory());
        var itemEntity = event.getItem();
        var origin = Objects.requireNonNull(
            PaperContainerPayloadCodec.snapshotLocation(itemEntity.getLocation()),
            "item origin"
        );

        var worldKey = inventory.worldKey() != null ? inventory.worldKey() : origin.worldKey();
        var position = inventory.position() != null ? inventory.position() : origin.position();

        this.inFlight.put(
            event,
            new Snapshot(
                Instant.now(this.clock),
                this.serverKey,
                worldKey,
                position,
                inventory,
                itemEntity.getUniqueId().toString(),
                PaperContainerPayloadCodec.snapshotItem(itemEntity.getItemStack()),
                origin
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(InventoryPickupItemEvent event) {
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
                PaperContainerPayloadCodec.encodePickup(
                    snapshot.inventory(),
                    snapshot.itemEntityId(),
                    snapshot.item(),
                    snapshot.itemOrigin()
                )
            )
        );
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        @Nullable Key worldKey,
        @Nullable BlockPosition position,
        PaperContainerPayloadCodec.InventorySnapshot inventory,
        String itemEntityId,
        net.minecraft.nbt.CompoundTag item,
        PaperContainerPayloadCodec.LocationSnapshot itemOrigin
    ) {
    }
}
