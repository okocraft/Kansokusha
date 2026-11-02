package net.okocraft.kansokusha.neoforge;

import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.okocraft.kansokusha.api.actor.BlockActor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Captures each moved block before the piston writes moving placeholders. */
final class NativePistonRecorder {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_PENDING = 100_000;
    private final NativeEventRecorder recorder;
    private final Map<OperationKey, List<Move>> before = new HashMap<>();
    private final Map<Move, Count> pending = new HashMap<>();

    NativePistonRecorder(NativeEventRecorder recorder) {
        this.recorder = recorder;
        NeoForge.EVENT_BUS.addListener(this::pre);
        NeoForge.EVENT_BUS.addListener(this::post);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void pre(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var helper = event.getStructureHelper();
        if (helper == null || !helper.resolve()) return;
        var key = key(event, level);
        var direction = event.getPistonMoveType().isExtend
            ? event.getDirection() : event.getDirection().getOpposite();
        var pistonState = level.getBlockState(event.getPos());
        var moves = new ArrayList<Move>();
        for (var from : helper.getToPush()) {
            var state = level.getBlockState(from);
            moves.add(new Move(level, from.immutable(), from.relative(direction).immutable(),
                event.getPos().immutable(), event.getPistonMoveType().name().toLowerCase(java.util.Locale.ROOT),
                direction, pistonState, state));
        }
        before.put(key, moves);
    }

    private void post(PistonEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var moves = before.remove(key(event, level));
        if (moves == null) return;
        var now = Instant.now();
        for (var move : moves) {
            var count = pending.get(move);
            if (count != null && now.isBefore(count.start.plus(WINDOW))) {
                count.amount++;
            } else {
                if (count != null) {
                    submit(move, count);
                    pending.remove(move);
                }
                if (pending.size() >= MAX_PENDING) submit(move, new Count(now, 1));
                else pending.put(move, new Count(now, 1));
            }
        }
    }

    private void tick(ServerTickEvent.Post event) {
        before.clear();
        var now = Instant.now();
        pending.entrySet().removeIf(entry -> {
            if (now.isBefore(entry.getValue().start.plus(WINDOW))) return false;
            submit(entry.getKey(), entry.getValue());
            return true;
        });
    }

    void flushAll() {
        pending.forEach(this::submit);
        pending.clear();
        before.clear();
    }

    private void submit(Move move, Count count) {
        var payload = new JsonObject();
        payload.addProperty("action", move.action);
        payload.addProperty("direction", move.direction.getName());
        payload.addProperty("block_state", move.state.toString());
        payload.addProperty("from", move.from.toShortString());
        payload.addProperty("to", move.to.toShortString());
        payload.addProperty("piston", move.piston.toShortString());
        payload.addProperty("attempt_count", count.amount);
        recorder.recordAt("piston_move", move.level, move.to,
            new BlockActor(blockKey(move.pistonState)), blockKey(move.state), payload, count.start);
    }

    private static OperationKey key(PistonEvent event, ServerLevel level) {
        return new OperationKey(level, event.getPos().immutable(), event.getPistonMoveType());
    }

    private static Key blockKey(BlockState state) {
        return Key.key(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    private record OperationKey(ServerLevel level, BlockPos piston, PistonEvent.PistonMoveType action) { }
    private record Move(ServerLevel level, BlockPos from, BlockPos to, BlockPos piston,
                        String action, Direction direction, BlockState pistonState, BlockState state) { }
    private static final class Count {
        private final Instant start;
        private int amount;
        private Count(Instant start, int amount) { this.start = start; this.amount = amount; }
    }
}
