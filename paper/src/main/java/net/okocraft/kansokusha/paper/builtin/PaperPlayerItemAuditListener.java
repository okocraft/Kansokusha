package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
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
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

@ApiStatus.Internal
@NotNullByDefault
final class PaperPlayerItemAuditListener implements Listener {

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
    private final PaperInFlightMap<PlayerDropItemEvent, ItemEntitySnapshot> drops =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<EntityPickupItemEvent, PickupSnapshot> pickups =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<PlayerEditBookEvent, BookEditSnapshot> bookEdits =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<PlayerInsertLecternBookEvent, LecternSnapshot> lecternInserts =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<PlayerTakeLecternBookEvent, LecternSnapshot> lecternTakes =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<PlayerPurchaseEvent, CommonSnapshot> purchases =
        new PaperInFlightMap<>();
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

    static PaperPlayerItemAuditListener register(KansokushaApi api, Key serverKey) {
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

    void captureDrop(PlayerDropItemEvent event) {
        Objects.requireNonNull(event, "event");
        var item = event.getItemDrop();
        var location = item.getLocation();
        this.drops.put(
            event,
            new ItemEntitySnapshot(
                common(event.getPlayer(), location),
                item.getUniqueId(),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(item.getItemStack()),
                location.getX(),
                location.getY(),
                location.getZ()
            )
        );
    }

    void finalizeDrop(PlayerDropItemEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.drops.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        submit(
            ITEM_DROP_EVENT_TYPE,
            snapshot.common(),
            PaperPlayerItemAuditPayloadCodec.encodeItemEntityChange(
                snapshot.itemEntityId(),
                snapshot.stack(),
                snapshot.x(),
                snapshot.y(),
                snapshot.z()
            )
        );
    }

    void capturePickup(EntityPickupItemEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        var item = event.getItem();
        var location = item.getLocation();
        this.pickups.put(
            event,
            new PickupSnapshot(
                common(player, location),
                item.getUniqueId(),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(item.getItemStack()),
                location.getX(),
                location.getY(),
                location.getZ(),
                event.getRemaining()
            )
        );
    }

    void finalizePickup(EntityPickupItemEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.pickups.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        submit(
            ITEM_PICKUP_EVENT_TYPE,
            snapshot.common(),
            PaperPlayerItemAuditPayloadCodec.encodeItemEntityChange(
                snapshot.itemEntityId(),
                snapshot.stack(),
                snapshot.x(),
                snapshot.y(),
                snapshot.z(),
                snapshot.remaining()
            )
        );
    }

    void captureBookEdit(PlayerEditBookEvent event) {
        Objects.requireNonNull(event, "event");
        this.bookEdits.put(
            event,
            new BookEditSnapshot(
                common(event.getPlayer(), event.getPlayer().getLocation()),
                bookSlot(event),
                PaperPlayerItemAuditPayloadCodec.snapshotBookMeta(
                    event.getPreviousBookMeta(),
                    false
                )
            )
        );
    }

    void finalizeBookEdit(PlayerEditBookEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.bookEdits.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var signing = event.isSigning();
        submit(
            BOOK_EDIT_EVENT_TYPE,
            snapshot.common(),
            PaperPlayerItemAuditPayloadCodec.encodeBookEdit(
                snapshot.slot(),
                snapshot.previousBookMeta(),
                PaperPlayerItemAuditPayloadCodec.snapshotBookMeta(
                    event.getNewBookMeta(),
                    signing
                ),
                signing
            )
        );
    }

    void captureLecternInsert(PlayerInsertLecternBookEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getBlock();
        this.lecternInserts.put(
            event,
            new LecternSnapshot(
                common(event.getPlayer(), block),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(null)
            )
        );
    }

    void finalizeLecternInsert(PlayerInsertLecternBookEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.lecternInserts.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        submit(
            LECTERN_CHANGE_EVENT_TYPE,
            snapshot.common(),
            PaperPlayerItemAuditPayloadCodec.encodeLecternChange(
                "insert",
                snapshot.book(),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(event.getBook())
            )
        );
    }

    void captureLecternTake(PlayerTakeLecternBookEvent event) {
        Objects.requireNonNull(event, "event");
        var lectern = event.getLectern();
        this.lecternTakes.put(
            event,
            new LecternSnapshot(
                common(event.getPlayer(), lectern.getBlock()),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(event.getBook())
            )
        );
    }

    void finalizeLecternTake(PlayerTakeLecternBookEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.lecternTakes.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        submit(
            LECTERN_CHANGE_EVENT_TYPE,
            snapshot.common(),
            PaperPlayerItemAuditPayloadCodec.encodeLecternChange(
                "take",
                snapshot.book(),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(null)
            )
        );
    }

    void capturePurchase(PlayerPurchaseEvent event) {
        Objects.requireNonNull(event, "event");
        var player = event.getPlayer();
        discardPendingPurchase(player.getUniqueId(), null);

        var merchant = event.getMerchant();
        var location =
            merchant instanceof Entity entity ? entity.getLocation() : player.getLocation();
        this.purchases.put(event, common(player, location));
    }

    void finalizePurchase(PlayerPurchaseEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.purchases.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var player = event.getPlayer();
        var pending = new PendingPurchase(
            snapshot,
            PaperPlayerItemAuditPayloadCodec.encodePlayerTrade(
                event instanceof PlayerTradeEvent ? PLAYER_TRADE_SOURCE : PLAYER_PURCHASE_SOURCE,
                event.getMerchant(),
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
            () -> discardPendingPurchase(player.getUniqueId(), pending)
        );
    }

    void confirmTrade(PlayerStatisticIncrementEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getStatistic() != Statistic.TRADED_WITH_VILLAGER) {
            return;
        }

        var pending = removePendingPurchase(event.getPlayer().getUniqueId());
        if (pending != null) {
            submit(PLAYER_TRADE_EVENT_TYPE, pending.common(), pending.payload());
        }
    }

    int inFlightCount() {
        synchronized (this.pendingPurchases) {
            return this.drops.size()
                + this.pickups.size()
                + this.bookEdits.size()
                + this.lecternInserts.size()
                + this.lecternTakes.size()
                + this.purchases.size()
                + this.pendingPurchases.size();
        }
    }

    private CommonSnapshot common(Player player, Location location) {
        Objects.requireNonNull(location, "location");
        var world = Objects.requireNonNull(location.getWorld(), "location.world");
        return new CommonSnapshot(
            Instant.now(this.clock),
            this.serverKey,
            PaperKansokusha.key(world.getKey()),
            new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
            new PlayerSubject(player.getUniqueId())
        );
    }

    private CommonSnapshot common(Player player, Block block) {
        return new CommonSnapshot(
            Instant.now(this.clock),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            PaperBuiltInSupport.position(block),
            new PlayerSubject(player.getUniqueId())
        );
    }

    private PendingPurchase removePendingPurchase(UUID playerId) {
        synchronized (this.pendingPurchases) {
            return this.pendingPurchases.remove(playerId);
        }
    }

    private void discardPendingPurchase(UUID playerId, @Nullable PendingPurchase expected) {
        synchronized (this.pendingPurchases) {
            var current = this.pendingPurchases.get(playerId);
            if (expected == null || current == expected) {
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

    private void submit(Key eventType, CommonSnapshot snapshot, EventPayload payload) {
        this.api.submit(
            new EventSubmission(
                eventType,
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

    @SuppressWarnings("removal")
    private static int bookSlot(PlayerEditBookEvent event) {
        return event.getSlot();
    }

    private record CommonSnapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        PlayerSubject subject
    ) {
    }

    private record ItemEntitySnapshot(
        CommonSnapshot common,
        java.util.UUID itemEntityId,
        CompoundTag stack,
        double x,
        double y,
        double z
    ) {
    }

    private record PickupSnapshot(
        CommonSnapshot common,
        java.util.UUID itemEntityId,
        CompoundTag stack,
        double x,
        double y,
        double z,
        int remaining
    ) {
    }

    private record BookEditSnapshot(
        CommonSnapshot common,
        int slot,
        CompoundTag previousBookMeta
    ) {
    }

    private record LecternSnapshot(CommonSnapshot common, CompoundTag book) {
    }

    private record PendingPurchase(CommonSnapshot common, EventPayload payload) {
    }
}
