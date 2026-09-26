package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Location;
import org.bukkit.Statistic;
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
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

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
    private final BiConsumer<Player, Runnable> nextTickExecutor;
    private final Map<UUID, PendingPurchase> pendingPurchases = new HashMap<>();

    private PaperPlayerItemAuditListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock,
        BiConsumer<Player, Runnable> nextTickExecutor
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nextTickExecutor = Objects.requireNonNull(nextTickExecutor, "nextTickExecutor");
    }

    public static PaperPlayerItemAuditListener register(KansokushaApi api, Key serverKey) {
        return register(
            api,
            serverKey,
            Clock.systemUTC(),
            PaperPlayerItemAuditListener::scheduleNextTick
        );
    }

    static PaperPlayerItemAuditListener register(KansokushaApi api, Key serverKey, Clock clock) {
        return register(api, serverKey, clock, (player, task) -> task.run());
    }

    static PaperPlayerItemAuditListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock,
        BiConsumer<Player, Runnable> nextTickExecutor
    ) {
        PaperBuiltInSupport.register(
            api,
            ITEM_DROP_EVENT_TYPE,
            ITEM_PICKUP_EVENT_TYPE,
            BOOK_EDIT_EVENT_TYPE,
            LECTERN_CHANGE_EVENT_TYPE,
            PLAYER_TRADE_EVENT_TYPE
        );
        return new PaperPlayerItemAuditListener(api, serverKey, clock, nextTickExecutor);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordDrop(PlayerDropItemEvent event) {
        Objects.requireNonNull(event, "event");

        var item = event.getItemDrop();
        var location = item.getLocation();
        this.submit(
            ITEM_DROP_EVENT_TYPE,
            this.common(event.getPlayer(), location),
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
            PaperPlayerItemAuditPayloadCodec.encodeLecternChange(
                "take",
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(event.getBook()),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(null)
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordPurchase(PlayerPurchaseEvent event) {
        Objects.requireNonNull(event, "event");

        var player = event.getPlayer();
        var merchant = event.getMerchant();
        var location =
            merchant instanceof Entity entity ? entity.getLocation() : player.getLocation();
        var pending = new PendingPurchase(
            this.common(player, location),
            PaperPlayerItemAuditPayloadCodec.encodePlayerTrade(
                event instanceof PlayerTradeEvent ? PLAYER_TRADE_SOURCE : PLAYER_PURCHASE_SOURCE,
                merchant,
                event.getTrade(),
                event.isRewardingExp(),
                event.willIncreaseTradeUses()
            )
        );
        synchronized (this.pendingPurchases) {
            this.pendingPurchases.put(player.getUniqueId(), pending);
        }
        this.nextTickExecutor.accept(
            player,
            () -> this.discardPendingPurchase(player.getUniqueId(), pending)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void confirmTrade(PlayerStatisticIncrementEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getStatistic() != Statistic.TRADED_WITH_VILLAGER) {
            return;
        }

        PendingPurchase pending;
        synchronized (this.pendingPurchases) {
            pending = this.pendingPurchases.remove(event.getPlayer().getUniqueId());
        }
        if (pending != null) {
            this.submit(PLAYER_TRADE_EVENT_TYPE, pending.common(), pending.payload());
        }
    }

    private CommonSnapshot common(Player player, Location location) {
        Objects.requireNonNull(location, "location");
        var world = Objects.requireNonNull(location.getWorld(), "location.world");
        return new CommonSnapshot(
            this.clock.instant(),
            PaperKansokusha.key(world.getKey()),
            new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
            new PlayerSubject(player.getUniqueId())
        );
    }

    private CommonSnapshot common(Player player, Block block) {
        return new CommonSnapshot(
            this.clock.instant(),
            PaperKansokusha.key(block.getWorld().getKey()),
            PaperBuiltInSupport.position(block),
            new PlayerSubject(player.getUniqueId())
        );
    }

    private void discardPendingPurchase(UUID playerId, PendingPurchase expected) {
        synchronized (this.pendingPurchases) {
            if (this.pendingPurchases.get(playerId) == expected) {
                this.pendingPurchases.remove(playerId);
            }
        }
    }

    private static void scheduleNextTick(Player player, Runnable task) {
        var plugin = JavaPlugin.getProvidingPlugin(PaperPlayerItemAuditListener.class);
        if (!player.getScheduler().execute(plugin, task, task, 1L)) {
            task.run();
        }
    }

    private void submit(Key eventType, CommonSnapshot common, EventPayload payload) {
        this.api.submit(
            new EventSubmission(
                eventType,
                PayloadGeneration.FIRST,
                common.occurredAt(),
                this.serverKey,
                common.worldKey(),
                common.position(),
                common.subject(),
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
        PlayerSubject subject
    ) {
    }

    private record PendingPurchase(CommonSnapshot common, EventPayload payload) {
    }
}
