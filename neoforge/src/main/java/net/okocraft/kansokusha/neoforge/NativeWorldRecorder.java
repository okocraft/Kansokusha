package net.okocraft.kansokusha.neoforge;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.DifficultyChangeEvent;
import net.neoforged.neoforge.event.level.GameRuleChangedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;

final class NativeWorldRecorder {
    private final NativeEventRecorder recorder;
    private final java.util.Map<String, String> gameRuleValues = new java.util.HashMap<>();

    NativeWorldRecorder(NativeEventRecorder recorder, KansokushaRuntime runtime) {
        this.recorder = recorder;
        NativeEventRecorder.register(runtime, "world_difficulty_change", "gamerule_change", "world_spawn_change",
            "world_border_change", "whitelist_change");
        NeoForge.EVENT_BUS.addListener(this::difficulty);
        NeoForge.EVENT_BUS.addListener(this::gameRule);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::serverStarted);
    }

    private void difficulty(DifficultyChangeEvent event) {
        if (event.getDifficulty() == event.getOldDifficulty()) return;
        var payload = new JsonObject();
        payload.addProperty("before", event.getOldDifficulty().name());
        payload.addProperty("after", event.getDifficulty().name());
        recorder.recordGlobal("world_difficulty_change", payload);
    }

    private void gameRule(GameRuleChangedEvent event) {
        var id = event.getGameRule().getIdentifier().toString();
        var after = String.valueOf(event.getNewValue());
        var before = gameRuleValues.put(id, after);
        if (before == null || before.equals(after)) return;
        var payload = new JsonObject();
        payload.addProperty("game_rule", id);
        payload.addProperty("before", before);
        payload.addProperty("after", after);
        recorder.recordGlobal("gamerule_change", payload);
    }

    private void serverStarted(ServerStartedEvent event) {
        var gameRules = event.getServer().getGameRules();
        gameRules.availableRules().forEach(rule -> gameRuleValues.put(rule.getIdentifier().toString(),
            gameRuleValue(gameRules, rule)));
        var level = event.getServer().overworld();
        var border = level.getWorldBorder();
        border.addListener(new BorderRecorder(level, border));
    }

    private static <T> String gameRuleValue(GameRules rules, GameRule<T> rule) {
        return String.valueOf(rules.get(rule));
    }

    private final class BorderRecorder implements BorderChangeListener {
        private final ServerLevel level;
        private double x;
        private double z;
        private double size;

        private BorderRecorder(ServerLevel level, WorldBorder border) {
            this.level = level;
            x = border.getCenterX();
            z = border.getCenterZ();
            size = border.getSize();
        }

        @Override public void onSetCenter(WorldBorder border, double nextX, double nextZ) {
            if (Double.compare(x, nextX) != 0 || Double.compare(z, nextZ) != 0) {
                var payload = new JsonObject();
                payload.addProperty("action", "center");
                payload.addProperty("before", x + "," + z);
                payload.addProperty("after", nextX + "," + nextZ);
                recorder.recordWorld("world_border_change", level, payload);
            }
            x = nextX;
            z = nextZ;
        }

        @Override public void onSetSize(WorldBorder border, double nextSize) {
            if (Double.compare(size, nextSize) != 0) borderSize("instant_move", size, nextSize, 0);
            size = nextSize;
        }

        @Override public void onLerpSize(WorldBorder border, double fromSize, double toSize,
                                          long ticks, long gameTime) {
            if (Double.compare(fromSize, toSize) != 0) borderSize("started_move", fromSize, toSize, ticks);
            size = toSize;
        }

        private void borderSize(String action, double before, double after, long ticks) {
            var payload = new JsonObject();
            payload.addProperty("action", action);
            payload.addProperty("before_size", before);
            payload.addProperty("after_size", after);
            payload.addProperty("transition_duration_ticks", ticks);
            recorder.recordWorld("world_border_change", level, payload);
        }

        @Override public void onSetWarningTime(WorldBorder border, int time) { }
        @Override public void onSetWarningBlocks(WorldBorder border, int blocks) { }
        @Override public void onSetDamagePerBlock(WorldBorder border, double damagePerBlock) { }
        @Override public void onSetSafeZone(WorldBorder border, double safeZone) { }
    }
}
