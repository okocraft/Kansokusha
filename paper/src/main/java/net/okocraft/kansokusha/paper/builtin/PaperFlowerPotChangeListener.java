package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperFlowerPotChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "flower_pot_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperPlayerItemAuditListener playerItemAuditListener;
    private final PaperInFlightMap<PlayerFlowerPotManipulateEvent, Snapshot> inFlight = new PaperInFlightMap<>();

    private PaperFlowerPotChangeListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock,
        PaperPlayerItemAuditListener playerItemAuditListener
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.playerItemAuditListener = Objects.requireNonNull(
            playerItemAuditListener,
            "playerItemAuditListener"
        );
    }

    public static PaperFlowerPotChangeListener register(KansokushaApi api, Key serverKey) {
        var clock = Clock.systemUTC();
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        var playerItemAuditListener = PaperPlayerItemAuditListener.register(api, serverKey);
        return new PaperFlowerPotChangeListener(api, serverKey, clock, playerItemAuditListener);
    }

    static PaperFlowerPotChangeListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        var playerItemAuditListener = PaperPlayerItemAuditListener.register(api, serverKey, clock);
        return new PaperFlowerPotChangeListener(api, serverKey, clock, playerItemAuditListener);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(PlayerFlowerPotManipulateEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getFlowerpot();
        var snapshot = new Snapshot(
            Instant.now(this.clock),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            new BlockPosition(block.getX(), block.getY(), block.getZ()),
            new PlayerSubject(event.getPlayer().getUniqueId()),
            PaperAdditionalBuiltInPayloadCodec.encodeFlowerPot(
                event.isPlacing(),
                event.getItem()
            )
        );
        this.inFlight.put(event, snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(PlayerFlowerPotManipulateEvent event) {
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
                snapshot.subject(),
                snapshot.payload()
            )
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureItemDrop(PlayerDropItemEvent event) {
        this.playerItemAuditListener.captureDrop(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeItemDrop(PlayerDropItemEvent event) {
        this.playerItemAuditListener.finalizeDrop(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureItemPickup(EntityPickupItemEvent event) {
        this.playerItemAuditListener.capturePickup(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeItemPickup(EntityPickupItemEvent event) {
        this.playerItemAuditListener.finalizePickup(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureBookEdit(PlayerEditBookEvent event) {
        this.playerItemAuditListener.captureBookEdit(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeBookEdit(PlayerEditBookEvent event) {
        this.playerItemAuditListener.finalizeBookEdit(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureLecternInsert(PlayerInsertLecternBookEvent event) {
        this.playerItemAuditListener.captureLecternInsert(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeLecternInsert(PlayerInsertLecternBookEvent event) {
        this.playerItemAuditListener.finalizeLecternInsert(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureLecternTake(PlayerTakeLecternBookEvent event) {
        this.playerItemAuditListener.captureLecternTake(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeLecternTake(PlayerTakeLecternBookEvent event) {
        this.playerItemAuditListener.finalizeLecternTake(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capturePurchase(PlayerPurchaseEvent event) {
        this.playerItemAuditListener.capturePurchase(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizePurchase(PlayerPurchaseEvent event) {
        this.playerItemAuditListener.finalizePurchase(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void confirmTrade(PlayerStatisticIncrementEvent event) {
        this.playerItemAuditListener.confirmTrade(event);
    }

    int inFlightCount() {
        return this.inFlight.size();
    }


    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        PlayerSubject subject,
        EventPayload payload
    ) {
    }
}
