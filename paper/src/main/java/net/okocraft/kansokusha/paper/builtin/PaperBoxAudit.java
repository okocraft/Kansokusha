package net.okocraft.kansokusha.paper.builtin;

import dev.siroshun.event4j.api.priority.Priority;
import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.box.api.BoxAPI;
import net.okocraft.box.api.event.stockholder.StockHolderResetEvent;
import net.okocraft.box.api.event.stockholder.stock.StockActor;
import net.okocraft.box.api.event.stockholder.stock.StockDecreaseEvent;
import net.okocraft.box.api.event.stockholder.stock.StockEvent;
import net.okocraft.box.api.event.stockholder.stock.StockIncreaseEvent;
import net.okocraft.box.api.event.stockholder.stock.StockSetEvent;
import net.okocraft.box.api.model.item.BoxDefaultItem;
import net.okocraft.box.api.model.item.BoxItem;
import net.okocraft.box.api.model.stock.StockHolder;
import net.okocraft.box.feature.autostore.event.AutoStoreItemEvent;
import net.okocraft.box.feature.stick.event.StickContainerChangeEvent;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBoxAudit {

    private final KansokushaApi api;
    private final Key serverKey;
    private final Method containerType;

    private PaperBoxAudit(KansokushaApi api, Key serverKey, Method containerType) {
        this.api = api;
        this.serverKey = serverKey;
        this.containerType = containerType;
    }

    public static @Nullable Runnable subscribe(Plugin plugin, KansokushaApi api, Key serverKey) {
        final Method containerType;
        try {
            // rc.8 still records through the external API. The new snapshot identifies the paired Box release.
            containerType = StickContainerChangeEvent.class.getMethod("getContainerType");
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("Box audit subscriptions require the Box release with container type snapshots.");
            return null;
        }
        var audit = new PaperBoxAudit(api, serverKey, containerType);
        var subscriber = BoxAPI.api().getListenerSubscriber();
        var key = Key.key("kansokusha", "box_audit");
        var listeners = subscriber.bulkSubscriber()
            .add(StockEvent.class, key, audit::stockChange, Priority.NORMAL)
            .add(StockHolderResetEvent.class, key, audit::reset, Priority.NORMAL)
            .add(AutoStoreItemEvent.class, key, audit::autoStore, Priority.NORMAL)
            .add(StickContainerChangeEvent.class, key, audit::stick, Priority.NORMAL)
            .subscribe();
        return () -> subscriber.unsubscribeAll(listeners);
    }

    private void stockChange(StockEvent event) {
        var occurredAt = Instant.now();
        int after = event.getAmount();
        int before = switch (event) {
            case StockIncreaseEvent increase -> after - increase.getIncrements();
            case StockDecreaseEvent decrease -> after + decrease.getDecrements();
            case StockSetEvent set -> set.getPreviousAmount();
            default -> after;
        };
        var item = event.getItem();
        this.recordStock(occurredAt, event.getStockHolder(), item.getInternalId(), item, before, after, event.getCause());
    }

    private void reset(StockHolderResetEvent event) {
        var occurredAt = Instant.now();
        var items = BoxAPI.api().getItemManager();
        for (var stock : event.getStockDataBeforeReset()) {
            this.recordStock(occurredAt, event.getStockHolder(), stock.itemId(), items.getBoxItemOrNull(stock.itemId()),
                stock.amount(), 0, event.getCause());
        }
    }

    private void recordStock(Instant occurredAt, StockHolder holder, int itemId, @Nullable BoxItem item,
                             int before, int after, StockEvent.Cause cause) {
        if (before == after) {
            return;
        }
        var payload = new CompoundTag();
        payload.putString("stock_uuid", holder.getUUID().toString());
        payload.putString("stock_name", holder.getName());
        payload.putInt("item_id", itemId);
        if (item != null) {
            payload.putString("item_name", item.getPlainName());
        }
        payload.putInt("before", before);
        payload.putInt("after", after);
        payload.putLong("amount_delta", (long) after - before);
        payload.putString("cause", cause.name());

        var actor = cause.actor();
        if (actor != null) {
            payload.putString("actor_name", actor.name());
            if (actor == StockActor.CONSOLE) {
                payload.putString("actor_kind", "console");
            }
        }
        this.api.submit(new EventSubmission(
            PaperBoxEventView.STOCK_CHANGE, PayloadGeneration.FIRST, occurredAt, this.serverKey, null, null,
            actor instanceof StockActor.Player player ? new PlayerActor(player.uuid()) : null,
            item instanceof BoxDefaultItem ? PaperBuiltInSupport.itemType(item.getOriginal()) : null,
            PaperPayloadNbtCodec.encode(payload)
        ));
    }

    private void autoStore(AutoStoreItemEvent event) {
        var occurredAt = Instant.now();
        var location = event.getLocation();
        var world = location.getWorld();
        var item = event.getItem();
        if (world == null || item.isEmpty()) {
            return;
        }
        this.api.submit(new EventSubmission(PaperPlayerItemAuditListener.ITEM_PICKUP_EVENT_TYPE,
            PayloadGeneration.FIRST, occurredAt, this.serverKey, PaperKansokusha.key(world.getKey()),
            new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
            new PlayerActor(event.getActor().uuid()), PaperBuiltInSupport.itemType(item),
            PaperPlayerItemAuditPayloadCodec.encodeItemEntityChange(event.getSourceEntityId(),
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(item), location.getX(), location.getY(),
                location.getZ(), 0, PaperBoxEventView.AUTOSTORE)
        ));
    }

    private void stick(StickContainerChangeEvent event) {
        var occurredAt = Instant.now();
        var location = PaperContainerPayloadCodec.snapshotLocation(event.getLocation());
        if (location == null || location.worldKey() == null || location.position() == null) {
            return;
        }
        final Material type;
        try {
            type = (Material) this.containerType.invoke(event);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read the Box Stick container type snapshot", e);
        }
        var before = event.getBefore();
        var containerTag = new CompoundTag();
        containerTag.putInt("size", before.length);
        containerTag.putString("holder_kind", "block");
        containerTag.put("location", location.payload());
        if (type != null) {
            var typeKey = PaperBuiltInSupport.type(type);
            containerTag.putString("type", typeKey.value());
            containerTag.putString("holder_block_type", typeKey.asString());
        }
        var container = new PaperContainerPayloadCodec.InventorySnapshot(
            containerTag, location.worldKey(), location.position(), null);
        var transactionId = UUID.randomUUID();
        var actor = new PlayerActor(event.getActor().uuid());
        for (var delta : PaperPlayerContainerTransactionListener.diffContents(before, event.getAfter())) {
            var direction = delta.amountDelta() > 0 ? "added_to_container" : "removed_from_container";
            this.api.submit(new EventSubmission(
                PaperPlayerContainerTransactionListener.EVENT_TYPE, PayloadGeneration.FIRST, occurredAt, this.serverKey,
                location.worldKey(), location.position(), actor, PaperBuiltInSupport.itemType(delta.item()),
                PaperAuditGapPayloadCodec.encodeContainerDelta(container, transactionId, "external", "box_stick",
                    null, null, -1, -1, -1, direction, delta.item(), delta.amountDelta(), PaperBoxEventView.STICK)
            ));
        }
    }
}
