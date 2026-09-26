package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.entity.Leashable;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperEntityStateChangeListener implements Listener {

    static final Key ARMOR_STAND_MANIPULATE_EVENT_TYPE =
        Key.key("kansokusha", "armor_stand_manipulate");
    static final Key ENTITY_LEASH_CHANGE_EVENT_TYPE =
        Key.key("kansokusha", "entity_leash_change");
    static final Key ITEM_FRAME_CHANGE_EVENT_TYPE =
        Key.key("kansokusha", "item_frame_change");
    static final Key ENTITY_TAME_EVENT_TYPE =
        Key.key("kansokusha", "entity_tame");
    static final Key ENTITY_NAME_CHANGE_EVENT_TYPE =
        Key.key("kansokusha", "entity_name_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperEntityStateChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperEntityStateChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperEntityStateChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(
            api,
            ARMOR_STAND_MANIPULATE_EVENT_TYPE,
            ENTITY_LEASH_CHANGE_EVENT_TYPE,
            ITEM_FRAME_CHANGE_EVENT_TYPE,
            ENTITY_TAME_EVENT_TYPE,
            ENTITY_NAME_CHANGE_EVENT_TYPE
        );
        return new PaperEntityStateChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        Objects.requireNonNull(event, "event");

        var playerItem = event.getPlayerItem();
        var armorStandItem = event.getArmorStandItem();
        if (playerItem.isEmpty() && armorStandItem.isEmpty()) {
            return;
        }

        var target = PaperEntityEventPayloadCodec.snapshotEntity(event.getRightClicked());
        this.submit(
            ARMOR_STAND_MANIPULATE_EVENT_TYPE,
            target,
            new PlayerSubject(event.getPlayer().getUniqueId()),
            PaperEntityStateChangePayloadCodec.encodeArmorStandManipulate(
                target,
                event.getSlot(),
                event.getHand(),
                PaperItemStackPayloadCodec.encode(playerItem),
                PaperItemStackPayloadCodec.encode(armorStandItem)
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordLeash(PlayerLeashEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var target = PaperEntityEventPayloadCodec.snapshotEntity(event.getEntity());
        this.submit(
            ENTITY_LEASH_CHANGE_EVENT_TYPE,
            target,
            new PlayerSubject(event.getPlayer().getUniqueId()),
            PaperEntityStateChangePayloadCodec.encodeLeashChange(
                "leash",
                target,
                PaperEntityEventPayloadCodec.snapshotEntity(event.getLeashHolder()),
                "player_leash",
                event.getHand(),
                false
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordUnleash(PlayerUnleashEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var targetEntity = event.getEntity();
        var target = PaperEntityEventPayloadCodec.snapshotEntity(targetEntity);
        this.submit(
            ENTITY_LEASH_CHANGE_EVENT_TYPE,
            target,
            new PlayerSubject(event.getPlayer().getUniqueId()),
            PaperEntityStateChangePayloadCodec.encodeLeashChange(
                "unleash",
                target,
                snapshotLeashHolder(targetEntity),
                enumName(event.getReason()),
                event.getHand(),
                event.isDropLeash()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordItemFrameChange(PlayerItemFrameChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var frame = event.getItemFrame();
        var target = PaperEntityEventPayloadCodec.snapshotEntity(frame);
        var action = event.getAction();
        var rotationBefore = frame.getRotation();
        var rotationAfter = action == PlayerItemFrameChangeEvent.ItemFrameChangeAction.ROTATE
            ? rotationBefore.rotateClockwise()
            : rotationBefore;
        this.submit(
            ITEM_FRAME_CHANGE_EVENT_TYPE,
            target,
            new PlayerSubject(event.getPlayer().getUniqueId()),
            PaperEntityStateChangePayloadCodec.encodeItemFrameChange(
                target,
                enumName(action),
                PaperItemStackPayloadCodec.encode(frame.getItem()),
                snapshotItemFrameResult(action, event.getItemStack()),
                rotationBefore,
                rotationAfter,
                frame.isFixed()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordTame(EntityTameEvent event) {
        Objects.requireNonNull(event, "event");

        var target = PaperEntityEventPayloadCodec.snapshotEntity(event.getEntity());
        var owner = event.getOwner();
        this.submit(
            ENTITY_TAME_EVENT_TYPE,
            target,
            owner instanceof Player player ? new PlayerSubject(player.getUniqueId()) : null,
            PaperEntityStateChangePayloadCodec.encodeTame(target, owner)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordNameChange(PlayerNameEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var entity = event.getEntity();
        var target = PaperEntityEventPayloadCodec.snapshotEntity(entity);
        this.submit(
            ENTITY_NAME_CHANGE_EVENT_TYPE,
            target,
            new PlayerSubject(event.getPlayer().getUniqueId()),
            PaperEntityStateChangePayloadCodec.encodeNameChange(
                target,
                entity.customName(),
                event.getName(),
                event.isPersistent()
            )
        );
    }

    private void submit(
        Key eventType,
        PaperEntityEventPayloadCodec.EntitySnapshot target,
        @Nullable PlayerSubject subject,
        EventPayload payload
    ) {
        this.api.submit(
            new EventSubmission(
                eventType,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                target.worldKey(),
                new BlockPosition(
                    (int) Math.floor(target.x()),
                    (int) Math.floor(target.y()),
                    (int) Math.floor(target.z())
                ),
                subject,
                payload
            )
        );
    }

    private static PaperEntityEventPayloadCodec.EntitySnapshot snapshotLeashHolder(Entity target) {
        if (!(target instanceof Leashable leashable) || !leashable.isLeashed()) {
            throw new IllegalStateException(
                "PlayerUnleashEntityEvent target is not currently leashed."
            );
        }
        return PaperEntityEventPayloadCodec.snapshotEntity(leashable.getLeashHolder());
    }

    private static CompoundTag snapshotItemFrameResult(
        PlayerItemFrameChangeEvent.ItemFrameChangeAction action,
        ItemStack eventItem
    ) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(eventItem, "eventItem");
        if (action == PlayerItemFrameChangeEvent.ItemFrameChangeAction.REMOVE
            || eventItem.isEmpty()) {
            return PaperItemStackPayloadCodec.encode(ItemStack.empty());
        }

        var normalized = eventItem.clone();
        normalized.setAmount(1);
        return PaperItemStackPayloadCodec.encode(normalized);
    }

    private static String enumName(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }
}
