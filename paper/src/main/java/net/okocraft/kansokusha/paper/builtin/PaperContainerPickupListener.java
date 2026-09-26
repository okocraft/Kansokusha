package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperContainerPickupListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "container_pickup");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(InventoryPickupItemEvent event) {
        Objects.requireNonNull(event, "event");

        var inventory = PaperContainerPayloadCodec.snapshotInventory(event.getInventory());
        var itemEntity = event.getItem();
        var origin = Objects.requireNonNull(
            PaperContainerPayloadCodec.snapshotLocation(itemEntity.getLocation()),
            "item origin"
        );

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                inventory.worldKey() != null ? inventory.worldKey() : origin.worldKey(),
                inventory.position() != null ? inventory.position() : origin.position(),
                null,
                PaperContainerPayloadCodec.encodePickup(
                    inventory,
                    itemEntity.getUniqueId().toString(),
                    PaperContainerPayloadCodec.snapshotItem(itemEntity.getItemStack()),
                    origin
                )
            )
        );
    }
}
