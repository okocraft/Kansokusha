package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperEntityPlaceListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "entity_place");
    static final String GENERIC_SOURCE_EVENT = "org.bukkit.event.entity.EntityPlaceEvent";
    static final String HANGING_SOURCE_EVENT = "org.bukkit.event.hanging.HangingPlaceEvent";

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperEntityPlaceListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperEntityPlaceListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperEntityPlaceListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperEntityPlaceListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordGeneric(EntityPlaceEvent event) {
        Objects.requireNonNull(event, "event");

        var entity = event.getEntity();
        if (entity instanceof Hanging) {
            return;
        }

        var player = event.getPlayer();
        var hand = event.getHand();
        this.submit(
            PaperEntityEventPayloadCodec.snapshotEntity(entity),
            PaperBuiltInSupport.entityType(entity),
            player,
            hand,
            heldItem(player, hand),
            GENERIC_SOURCE_EVENT,
            null
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordHanging(HangingPlaceEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        var player = event.getPlayer();
        var hand = event.getHand();
        var item = event.getItemStack();
        if (item == null) {
            item = heldItem(player, hand);
        }

        var entity = event.getEntity();
        this.submit(
            PaperEntityEventPayloadCodec.snapshotEntity(entity),
            PaperBuiltInSupport.entityType(entity),
            player,
            hand,
            item,
            HANGING_SOURCE_EVENT,
            new PaperEntityEventPayloadCodec.HangingPlacementSnapshot(
                block.getX(),
                block.getY(),
                block.getZ(),
                event.getBlockFace().name().toLowerCase(java.util.Locale.ROOT)
            )
        );
    }

    private void submit(
        PaperEntityEventPayloadCodec.EntitySnapshot entity,
        Key entityType,
        @Nullable Player player,
        @Nullable EquipmentSlot hand,
        @Nullable ItemStack item,
        String sourceEvent,
        @Nullable PaperEntityEventPayloadCodec.HangingPlacementSnapshot hanging
    ) {
        PaperEntityEventPayloadCodec.EntitySnapshot actor = null;
        PlayerActor playerActor = null;
        if (player != null) {
            actor = PaperEntityEventPayloadCodec.snapshotEntity(player);
            playerActor = new PlayerActor(player.getUniqueId());
        }

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                this.serverKey,
                entity.worldKey(),
                new BlockPosition(
                    (int) Math.floor(entity.x()),
                    (int) Math.floor(entity.y()),
                    (int) Math.floor(entity.z())
                ),
                playerActor,
                entityType,
                PaperEntityEventPayloadCodec.encodePlacement(
                    entity,
                    actor,
                    hand,
                    PaperItemStackPayloadCodec.encode(item == null ? ItemStack.empty() : item),
                    sourceEvent,
                    hanging
                )
            )
        );
    }

    private static @Nullable ItemStack heldItem(
        @Nullable Player player,
        @Nullable EquipmentSlot hand
    ) {
        if (player == null || hand == null) {
            return null;
        }
        return switch (hand) {
            case HAND -> player.getInventory().getItemInMainHand();
            case OFF_HAND -> player.getInventory().getItemInOffHand();
            default -> null;
        };
    }
}
