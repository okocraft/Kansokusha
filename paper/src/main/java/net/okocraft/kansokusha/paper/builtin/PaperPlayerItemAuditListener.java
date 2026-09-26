package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerItemAuditListener implements Listener {

    static final Key ITEM_DROP_EVENT_TYPE = Key.key("kansokusha", "item_drop");
    static final Key ITEM_PICKUP_EVENT_TYPE = Key.key("kansokusha", "item_pickup");
    static final Key BOOK_EDIT_EVENT_TYPE = Key.key("kansokusha", "book_edit");
    static final Key LECTERN_CHANGE_EVENT_TYPE = Key.key("kansokusha", "lectern_change");
    static final Key PLAYER_TRADE_EVENT_TYPE = Key.key("kansokusha", "player_trade");

    private static final String PLAYER_TRADE_SOURCE =
        "io.papermc.paper.event.player.PlayerTradeEvent";
    private static final String PLAYER_PURCHASE_SOURCE =
        "io.papermc.paper.event.player.PlayerPurchaseEvent";

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperPlayerItemAuditListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerItemAuditListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerItemAuditListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(
            api,
            ITEM_DROP_EVENT_TYPE,
            ITEM_PICKUP_EVENT_TYPE,
            BOOK_EDIT_EVENT_TYPE,
            LECTERN_CHANGE_EVENT_TYPE,
            PLAYER_TRADE_EVENT_TYPE
        );
        return new PaperPlayerItemAuditListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordDrop(PlayerDropItemEvent event) {
        Objects.requireNonNull(event, "event");

        var item = event.getItemDrop();
        var location = item.getLocation();
        this.submit(
            ITEM_DROP_EVENT_TYPE,
            this.common(event.getPlayer(), location),
            PaperBuiltInSupport.itemType(item.getItemStack()),
            PaperPlayerItemAuditPayloadCodec.encodeItemEntityChange(
                item.getUniqueId(),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(item.getItemStack()),
                location.getX(),
                location.getY(),
                location.getZ()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordPickup(EntityPickupItemEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        var item = event.getItem();
        var location = item.getLocation();
        this.submit(
            ITEM_PICKUP_EVENT_TYPE,
            this.common(player, location),
            PaperBuiltInSupport.itemType(item.getItemStack()),
            PaperPlayerItemAuditPayloadCodec.encodeItemEntityChange(
                item.getUniqueId(),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(item.getItemStack()),
                location.getX(),
                location.getY(),
                location.getZ(),
                event.getRemaining()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordBookEdit(PlayerEditBookEvent event) {
        Objects.requireNonNull(event, "event");

        var player = event.getPlayer();
        var signing = event.isSigning();
        this.submit(
            BOOK_EDIT_EVENT_TYPE,
            this.common(player, player.getLocation()),
            PaperBuiltInSupport.type(signing ? Material.WRITTEN_BOOK : Material.WRITABLE_BOOK),
            PaperPlayerItemAuditPayloadCodec.encodeBookEdit(
                bookSlot(event),
                PaperPlayerItemAuditPayloadCodec.snapshotBookMeta(event.getPreviousBookMeta(), false),
                PaperPlayerItemAuditPayloadCodec.snapshotBookMeta(event.getNewBookMeta(), signing),
                signing
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordLecternInsert(PlayerInsertLecternBookEvent event) {
        Objects.requireNonNull(event, "event");

        this.submit(
            LECTERN_CHANGE_EVENT_TYPE,
            this.common(event.getPlayer(), event.getBlock()),
            PaperBuiltInSupport.itemType(event.getBook()),
            PaperPlayerItemAuditPayloadCodec.encodeLecternChange(
                "insert",
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(null),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(event.getBook())
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordLecternTake(PlayerTakeLecternBookEvent event) {
        Objects.requireNonNull(event, "event");

        this.submit(
            LECTERN_CHANGE_EVENT_TYPE,
            this.common(event.getPlayer(), event.getLectern().getBlock()),
            PaperBuiltInSupport.itemType(event.getBook()),
            PaperPlayerItemAuditPayloadCodec.encodeLecternChange(
                "take",
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(event.getBook()),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(null)
            )
        );
    }

    /**
     * Handles both PlayerPurchaseEvent and its subclass PlayerTradeEvent, which share one handler list.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordPurchase(PlayerPurchaseEvent event) {
        Objects.requireNonNull(event, "event");

        var player = event.getPlayer();
        var merchant = event.getMerchant();
        var location =
            merchant instanceof Entity entity ? entity.getLocation() : player.getLocation();
        this.submit(
            PLAYER_TRADE_EVENT_TYPE,
            this.common(player, location),
            PaperBuiltInSupport.itemType(event.getTrade().getResult()),
            PaperPlayerItemAuditPayloadCodec.encodePlayerTrade(
                event instanceof PlayerTradeEvent ? PLAYER_TRADE_SOURCE : PLAYER_PURCHASE_SOURCE,
                merchant,
                event.getTrade(),
                event.isRewardingExp(),
                event.willIncreaseTradeUses()
            )
        );
    }

    private CommonSnapshot common(Player player, Location location) {
        Objects.requireNonNull(location, "location");
        var world = Objects.requireNonNull(location.getWorld(), "location.world");
        return new CommonSnapshot(
            this.clock.instant(),
            PaperKansokusha.key(world.getKey()),
            new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
            new PlayerActor(player.getUniqueId())
        );
    }

    private CommonSnapshot common(Player player, Block block) {
        return new CommonSnapshot(
            this.clock.instant(),
            PaperKansokusha.key(block.getWorld().getKey()),
            PaperBuiltInSupport.position(block),
            new PlayerActor(player.getUniqueId())
        );
    }

    private void submit(
        Key eventType,
        CommonSnapshot common,
        @Nullable Key targetType,
        EventPayload payload
    ) {
        this.api.submit(
            new EventSubmission(
                eventType,
                PayloadGeneration.FIRST,
                common.occurredAt(),
                this.serverKey,
                common.worldKey(),
                common.position(),
                common.actor(),
                targetType,
                payload
            )
        );
    }

    @SuppressWarnings("removal")
    private static int bookSlot(PlayerEditBookEvent event) {
        return event.getSlot();
    }

    private record CommonSnapshot(
        Instant occurredAt,
        Key worldKey,
        BlockPosition position,
        PlayerActor actor
    ) {
    }
}
