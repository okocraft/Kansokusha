package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperEntityLifecycleAuditListener implements Listener {

    static final Key DEATH_EVENT_TYPE = Key.key("kansokusha", "entity_death");
    static final Key SPAWN_EVENT_TYPE = Key.key("kansokusha", "entity_spawn");

    private static final Set<String> AUDITED_SPAWN_REASONS = Set.of(
        "spawner_egg",
        "dispense_egg",
        "bucket",
        "egg",
        "build_coppergolem",
        "build_irongolem",
        "build_snowman",
        "build_wither"
    );

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperEntityLifecycleAuditListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperEntityLifecycleAuditListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperEntityLifecycleAuditListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, DEATH_EVENT_TYPE, SPAWN_EVENT_TYPE);
        return new PaperEntityLifecycleAuditListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordDeath(EntityDeathEvent event) {
        Objects.requireNonNull(event, "event");
        var entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }

        var damageSource = event.getDamageSource();
        var causingEntity = damageSource.getCausingEntity();
        if (!shouldRecordDeath(entity, causingEntity)) {
            return;
        }

        var snapshot = PaperEntityEventPayloadCodec.snapshotEntity(entity);
        var killer = causingEntity == null
            ? null
            : PaperEntityEventPayloadCodec.snapshotEntity(causingEntity);
        this.api.submit(new EventSubmission(
            DEATH_EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            snapshot.worldKey(),
            blockPosition(snapshot),
            PaperBuiltInSupport.nullableActor(causingEntity),
            PaperBuiltInSupport.entityType(entity),
            PaperAuditGapPayloadCodec.encodeEntityDeath(
                snapshot,
                killer,
                damageSource.getDamageType().getKey().toString(),
                damageSource.isIndirect(),
                event.getDroppedExp(),
                event.getDrops()
            )
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordSpawn(CreatureSpawnEvent event) {
        Objects.requireNonNull(event, "event");
        var reason = event.getSpawnReason().name().toLowerCase(Locale.ROOT);
        if (!AUDITED_SPAWN_REASONS.contains(reason)) {
            return;
        }

        var entity = event.getEntity();
        var snapshot = PaperEntityEventPayloadCodec.snapshotEntity(entity);
        this.api.submit(new EventSubmission(
            SPAWN_EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            snapshot.worldKey(),
            blockPosition(snapshot),
            null,
            PaperBuiltInSupport.entityType(entity),
            PaperAuditGapPayloadCodec.encodeEntitySpawn(snapshot, reason)
        ));
    }

    private static boolean shouldRecordDeath(
        org.bukkit.entity.LivingEntity entity,
        @Nullable org.bukkit.entity.Entity causingEntity
    ) {
        if (causingEntity instanceof Player) {
            return true;
        }
        if (entity instanceof AbstractVillager || entity instanceof ArmorStand) {
            return true;
        }
        if (entity instanceof Tameable tameable && tameable.isTamed()) {
            return true;
        }
        return entity.customName() != null;
    }

    private static BlockPosition blockPosition(PaperEntityEventPayloadCodec.EntitySnapshot entity) {
        return new BlockPosition(
            (int) Math.floor(entity.x()),
            (int) Math.floor(entity.y()),
            (int) Math.floor(entity.z())
        );
    }
}
