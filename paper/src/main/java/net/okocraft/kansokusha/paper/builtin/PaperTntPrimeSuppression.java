package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

@NotNullByDefault
final class PaperTntPrimeSuppression {

    private static final Map<KansokushaApi, PaperTntTransitionTracker<Boolean>> EXPLOSION =
        new WeakHashMap<>();

    private PaperTntPrimeSuppression() {
    }

    static void suppressExplosion(
        KansokushaApi api,
        Key worldKey,
        BlockPosition position
    ) {
        tracker(api).add(worldKey, position, Boolean.TRUE);
    }

    static boolean consumeExplosion(KansokushaApi api, Block block) {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(block, "block");
        PaperTntTransitionTracker<Boolean> tracker;
        synchronized (EXPLOSION) {
            tracker = EXPLOSION.get(api);
        }
        return tracker != null && tracker.remove(block) != null;
    }

    static void clear(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        synchronized (EXPLOSION) {
            EXPLOSION.remove(api);
        }
    }

    private static PaperTntTransitionTracker<Boolean> tracker(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        synchronized (EXPLOSION) {
            return EXPLOSION.computeIfAbsent(api, ignored -> new PaperTntTransitionTracker<>());
        }
    }
}
