package net.okocraft.kansokusha.neoforge.capture;

import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;

/** Observes actual block writes only inside a named native operation. */
public final class NativeBlockOperationCapture {
    private static final ThreadLocal<Deque<Scope>> ACTIVE = new ThreadLocal<>();

    private NativeBlockOperationCapture() { }

    public static Scope fertilize(ServerLevel level, Player player) {
        if (fertilizing(level)) return null;
        return begin(level, player == null ? null : new PlayerActor(player.getUUID()),
            "block_fertilize", "bone_meal");
    }

    public static Scope natural(ServerLevel level, String cause) {
        var scopes = ACTIVE.get();
        if ("tree_growth".equals(cause) && scopes != null && scopes.stream().anyMatch(scope ->
            scope.level == level && scope.eventType.equals("block_fertilize"))) return null;
        return begin(level, null, "natural_block_change", cause);
    }

    public static Scope entity(ServerLevel level, Entity actor, String cause) {
        return begin(level, entityActor(actor), "entity_block_change", cause);
    }

    public static Scope dragon(ServerLevel level, Entity actor, String cause) {
        return begin(level, entityActor(actor), "explosion_block_change", cause);
    }

    public static boolean fertilizing(ServerLevel level) {
        var scopes = ACTIVE.get();
        return scopes != null && scopes.stream().anyMatch(scope ->
            scope.level == level && scope.eventType.equals("block_fertilize"));
    }

    public static boolean ownsEntityMutation(ServerLevel level, Entity entity) {
        var scopes = ACTIVE.get();
        return scopes != null && scopes.stream().anyMatch(scope ->
            scope.level == level && scope.eventType.equals("entity_block_change")
                && scope.actor instanceof EntityActor actor && actor.uniqueId().equals(entity.getUUID()));
    }

    private static Scope begin(ServerLevel level, EventActor actor, String eventType, String cause) {
        if (KansokushaNeoForge.recorder() == null) return null;
        var scopes = ACTIVE.get();
        if (scopes == null) {
            scopes = new ArrayDeque<>();
            ACTIVE.set(scopes);
        }
        var scope = new Scope(level, actor, eventType, cause);
        scopes.push(scope);
        return scope;
    }

    public static void observe(Level level, BlockPos position) {
        var scopes = ACTIVE.get();
        if (scopes == null) return;
        for (var scope : scopes) {
            if (scope.level == level) {
                scope.before.putIfAbsent(position.immutable(), level.getBlockState(position));
                if (scope.eventType.equals("entity_block_change")
                    || scope.eventType.equals("explosion_block_change")
                    || scope.cause.equals("support_loss")) return;
            }
        }
    }

    public static void end(Scope scope, boolean accepted) {
        if (scope == null) return;
        var scopes = ACTIVE.get();
        scopes.pop();
        if (scopes.isEmpty()) ACTIVE.remove();
        if (!accepted) return;
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null) return;
        var changes = new ArrayList<>(scope.pending);
        var occurredAt = Instant.now();
        scope.before.forEach((position, before) -> {
            var after = scope.level.getBlockState(position);
            if (!before.equals(after)) changes.add(new Pending(scope, position, before, after, occurredAt));
        });
        for (var parent : scopes) {
            if (parent.level != scope.level) continue;
            parent.pending.addAll(changes);
            return;
        }
        for (var change : changes)
            if (!(change.owner.cause.equals("weather_formation")
                && (change.before.getBlock() instanceof AbstractCauldronBlock
                    || change.after.getBlock() instanceof AbstractCauldronBlock)))
                recorder.recordScopedBlockChange(change.owner.eventType, scope.level, change.position,
                    change.before, change.after, change.owner.actor, change.owner.cause, change.occurredAt);
    }

    private static EntityActor entityActor(Entity entity) {
        return new EntityActor(entity.getUUID(),
            Key.key(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
    }

    private record Pending(Scope owner, BlockPos position, BlockState before, BlockState after,
                           Instant occurredAt) { }

    public static final class Scope {
        private final ServerLevel level;
        private final EventActor actor;
        private final String eventType;
        private final String cause;
        private final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        private final List<Pending> pending = new ArrayList<>();

        private Scope(ServerLevel level, EventActor actor, String eventType, String cause) {
            this.level = level;
            this.actor = actor;
            this.eventType = eventType;
            this.cause = cause;
        }
    }
}
