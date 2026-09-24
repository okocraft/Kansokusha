package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBucketListener implements PaperInFlightListener {

    static final Key EMPTY_EVENT_TYPE = Key.key("kansokusha", "bucket_empty");
    static final Key FILL_EVENT_TYPE = Key.key("kansokusha", "bucket_fill");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<PlayerBucketEvent, Snapshot> inFlight = new PaperInFlightMap<>();

    private PaperBucketListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBucketListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBucketListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EMPTY_EVENT_TYPE, FILL_EVENT_TYPE);
        return new PaperBucketListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureEmpty(PlayerBucketEmptyEvent event) {
        this.capture(event, EMPTY_EVENT_TYPE, "empty");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureFill(PlayerBucketFillEvent event) {
        if (event.getBlockFace() == BlockFace.SELF) {
            return;
        }
        this.capture(event, FILL_EVENT_TYPE, "fill");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEmpty(PlayerBucketEmptyEvent event) {
        this.finalizeEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeFill(PlayerBucketFillEvent event) {
        this.finalizeEvent(event);
    }

    @Override
    public void clearInFlightState() {
        this.inFlight.clear();
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    private void capture(PlayerBucketEvent event, Key eventType, String operation) {
        Objects.requireNonNull(event, "event");
        var changedBlock = event.getBlock();
        var clickedBlock = event.getBlockClicked();
        var snapshot = new Snapshot(
            eventType,
            operation,
            Instant.now(this.clock),
            this.serverKey,
            PaperKansokusha.key(changedBlock.getWorld().getKey()),
            new BlockPosition(changedBlock.getX(), changedBlock.getY(), changedBlock.getZ()),
            new PlayerSubject(event.getPlayer().getUniqueId()),
            event.getBucket(),
            event.getHand(),
            event.getBlockFace(),
            clickedBlock.getX(),
            clickedBlock.getY(),
            clickedBlock.getZ(),
            PaperAdditionalBuiltInPayloadCodec.snapshotBlockState(changedBlock.getBlockData()),
            PaperAdditionalBuiltInPayloadCodec.snapshotItem(event.getItemStack())
        );

        this.inFlight.put(event, snapshot);
    }

    private void finalizeEvent(PlayerBucketEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var payload = PaperAdditionalBuiltInPayloadCodec.encodeBucket(
            snapshot.operation(),
            snapshot.bucket(),
            snapshot.hand(),
            snapshot.face(),
            snapshot.clickedX(),
            snapshot.clickedY(),
            snapshot.clickedZ(),
            snapshot.preState(),
            snapshot.initialResultItem(),
            PaperAdditionalBuiltInPayloadCodec.snapshotItem(event.getItemStack())
        );
        this.api.submit(
            new EventSubmission(
                snapshot.eventType(),
                PayloadGeneration.FIRST,
                snapshot.occurredAt(),
                snapshot.serverKey(),
                snapshot.worldKey(),
                snapshot.position(),
                snapshot.subject(),
                payload
            )
        );
    }


    private record Snapshot(
        Key eventType,
        String operation,
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        PlayerSubject subject,
        Material bucket,
        EquipmentSlot hand,
        BlockFace face,
        int clickedX,
        int clickedY,
        int clickedZ,
        CompoundTag preState,
        CompoundTag initialResultItem
    ) {
    }
}
