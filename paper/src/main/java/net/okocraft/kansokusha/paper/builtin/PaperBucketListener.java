package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBucketListener implements Listener {

    static final Key EMPTY_EVENT_TYPE = Key.key("kansokusha", "bucket_empty");
    static final Key FILL_EVENT_TYPE = Key.key("kansokusha", "bucket_fill");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordEmpty(PlayerBucketEmptyEvent event) {
        this.record(event, EMPTY_EVENT_TYPE, "empty", PaperBuiltInSupport.type(event.getBucket()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordFill(PlayerBucketFillEvent event) {
        if (event.getBlockFace() == BlockFace.SELF) {
            return;
        }
        this.record(event, FILL_EVENT_TYPE, "fill", PaperBuiltInSupport.itemType(event.getItemStack()));
    }

    private void record(
        PlayerBucketEvent event,
        Key eventType,
        String operation,
        @Nullable Key targetType
    ) {
        Objects.requireNonNull(event, "event");

        var changedBlock = event.getBlock();
        var clickedBlock = event.getBlockClicked();
        this.api.submit(
            new EventSubmission(
                eventType,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                PaperKansokusha.key(changedBlock.getWorld().getKey()),
                PaperBuiltInSupport.position(changedBlock),
                new PlayerActor(event.getPlayer().getUniqueId()),
                targetType,
                PaperAdditionalBuiltInPayloadCodec.encodeBucket(
                    operation,
                    event.getBucket(),
                    event.getHand(),
                    event.getBlockFace(),
                    clickedBlock.getX(),
                    clickedBlock.getY(),
                    clickedBlock.getZ(),
                    PaperAdditionalBuiltInPayloadCodec.snapshotBlockState(changedBlock.getBlockData()),
                    PaperAdditionalBuiltInPayloadCodec.snapshotItem(event.getItemStack())
                )
            )
        );
    }
}
