package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.entity.Leashable;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import net.kyori.adventure.key.Key;
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
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperEntityStateChangeListener implements PaperInFlightListener {

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
    private final PaperInFlightMap<PlayerArmorStandManipulateEvent, Snapshot> armorStandInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<PlayerLeashEntityEvent, Snapshot> leashInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<PlayerUnleashEntityEvent, Snapshot> unleashInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<PlayerItemFrameChangeEvent, Snapshot> itemFrameInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<EntityTameEvent, Snapshot> tameInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<PlayerNameEntityEvent, Snapshot> nameInFlight =
        new PaperInFlightMap<>();

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

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        Objects.requireNonNull(event, "event");

        var playerItem = event.getPlayerItem();
        var armorStandItem = event.getArmorStandItem();
        if (playerItem.isEmpty() && armorStandItem.isEmpty()) {
            return;
        }

        var target = PaperEntityEventPayloadCodec.snapshotEntity(event.getRightClicked());
        this.armorStandInFlight.put(
            event,
            this.snapshot(
                target,
                new PlayerSubject(event.getPlayer().getUniqueId()),
                PaperEntityStateChangePayloadCodec.encodeArmorStandManipulate(
                    target,
                    event.getSlot(),
                    event.getHand(),
                    PaperItemStackPayloadCodec.encode(playerItem),
                    PaperItemStackPayloadCodec.encode(armorStandItem)
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        Objects.requireNonNull(event, "event");
        this.finalizeEvent(
            ARMOR_STAND_MANIPULATE_EVENT_TYPE,
            event.isCancelled(),
            this.armorStandInFlight.remove(event)
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureLeash(PlayerLeashEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var target = PaperEntityEventPayloadCodec.snapshotEntity(event.getEntity());
        var holder = PaperEntityEventPayloadCodec.snapshotEntity(event.getLeashHolder());
        this.leashInFlight.put(
            event,
            this.snapshot(
                target,
                new PlayerSubject(event.getPlayer().getUniqueId()),
                PaperEntityStateChangePayloadCodec.encodeLeashChange(
                    "leash",
                    target,
                    holder,
                    "player_leash",
                    event.getHand(),
                    false
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeLeash(PlayerLeashEntityEvent event) {
        Objects.requireNonNull(event, "event");
        this.finalizeEvent(
            ENTITY_LEASH_CHANGE_EVENT_TYPE,
            event.isCancelled(),
            this.leashInFlight.remove(event)
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureUnleash(PlayerUnleashEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var targetEntity = event.getEntity();
        var target = PaperEntityEventPayloadCodec.snapshotEntity(targetEntity);
        var holder = snapshotLeashHolder(targetEntity);
        this.unleashInFlight.put(
            event,
            this.snapshot(
                target,
                new PlayerSubject(event.getPlayer().getUniqueId()),
                PaperEntityStateChangePayloadCodec.encodeLeashChange(
                    "unleash",
                    target,
                    holder,
                    enumName(event.getReason()),
                    event.getHand(),
                    event.isDropLeash()
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeUnleash(PlayerUnleashEntityEvent event) {
        Objects.requireNonNull(event, "event");
        this.finalizeEvent(
            ENTITY_LEASH_CHANGE_EVENT_TYPE,
            event.isCancelled(),
            this.unleashInFlight.remove(event)
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureItemFrameChange(PlayerItemFrameChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var frame = event.getItemFrame();
        var target = PaperEntityEventPayloadCodec.snapshotEntity(frame);
        var action = event.getAction();
        var itemBefore = PaperItemStackPayloadCodec.encode(frame.getItem());
        var eventItem = PaperItemStackPayloadCodec.encode(event.getItemStack());
        var itemAfter = switch (action) {
            case PLACE, ROTATE -> eventItem;
            case REMOVE -> PaperItemStackPayloadCodec.encode(ItemStack.empty());
        };
        var rotationBefore = frame.getRotation();
        var rotationAfter = action == PlayerItemFrameChangeEvent.ItemFrameChangeAction.ROTATE
            ? rotationBefore.rotateClockwise()
            : rotationBefore;
        var fixed = frame.isFixed();

        this.itemFrameInFlight.put(
            event,
            this.snapshot(
                target,
                new PlayerSubject(event.getPlayer().getUniqueId()),
                PaperEntityStateChangePayloadCodec.encodeItemFrameChange(
                    target,
                    enumName(action),
                    itemBefore,
                    itemAfter,
                    rotationBefore,
                    rotationAfter,
                    fixed,
                    fixed
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeItemFrameChange(PlayerItemFrameChangeEvent event) {
        Objects.requireNonNull(event, "event");
        this.finalizeEvent(
            ITEM_FRAME_CHANGE_EVENT_TYPE,
            event.isCancelled(),
            this.itemFrameInFlight.remove(event)
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureTame(EntityTameEvent event) {
        Objects.requireNonNull(event, "event");

        var target = PaperEntityEventPayloadCodec.snapshotEntity(event.getEntity());
        var owner = event.getOwner();
        var subject = owner instanceof Player player
            ? new PlayerSubject(player.getUniqueId())
            : null;
        this.tameInFlight.put(
            event,
            this.snapshot(
                target,
                subject,
                PaperEntityStateChangePayloadCodec.encodeTame(target, owner)
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeTame(EntityTameEvent event) {
        Objects.requireNonNull(event, "event");
        this.finalizeEvent(
            ENTITY_TAME_EVENT_TYPE,
            event.isCancelled(),
            this.tameInFlight.remove(event)
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureNameChange(PlayerNameEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var entity = event.getEntity();
        var target = PaperEntityEventPayloadCodec.snapshotEntity(entity);
        this.nameInFlight.put(
            event,
            this.snapshot(
                target,
                new PlayerSubject(event.getPlayer().getUniqueId()),
                PaperEntityStateChangePayloadCodec.encodeNameChange(
                    target,
                    entity.customName(),
                    event.getName(),
                    event.isPersistent()
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeNameChange(PlayerNameEntityEvent event) {
        Objects.requireNonNull(event, "event");
        this.finalizeEvent(
            ENTITY_NAME_CHANGE_EVENT_TYPE,
            event.isCancelled(),
            this.nameInFlight.remove(event)
        );
    }

    @Override
    public void clearInFlightState() {
        this.armorStandInFlight.clear();
        this.leashInFlight.clear();
        this.unleashInFlight.clear();
        this.itemFrameInFlight.clear();
        this.tameInFlight.clear();
        this.nameInFlight.clear();
    }

    int inFlightCount() {
        return this.armorStandInFlight.size()
            + this.leashInFlight.size()
            + this.unleashInFlight.size()
            + this.itemFrameInFlight.size()
            + this.tameInFlight.size()
            + this.nameInFlight.size();
    }

    private Snapshot snapshot(
        PaperEntityEventPayloadCodec.EntitySnapshot target,
        @Nullable PlayerSubject subject,
        EventPayload payload
    ) {
        return new Snapshot(
            Instant.now(this.clock),
            this.serverKey,
            target.worldKey(),
            new BlockPosition(
                (int) Math.floor(target.x()),
                (int) Math.floor(target.y()),
                (int) Math.floor(target.z())
            ),
            subject,
            payload
        );
    }

    private void finalizeEvent(Key eventType, boolean cancelled, @Nullable Snapshot snapshot) {
        if (snapshot == null || cancelled) {
            return;
        }

        this.api.submit(
            new EventSubmission(
                eventType,
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

    private static PaperEntityEventPayloadCodec.EntitySnapshot snapshotLeashHolder(Entity target) {
        if (!(target instanceof Leashable leashable) || !leashable.isLeashed()) {
            throw new IllegalStateException(
                "PlayerUnleashEntityEvent target is not currently leashed."
            );
        }
        return PaperEntityEventPayloadCodec.snapshotEntity(leashable.getLeashHolder());
    }

    private static String enumName(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        @Nullable PlayerSubject subject,
        EventPayload payload
    ) {
    }
}
