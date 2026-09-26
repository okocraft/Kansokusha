package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Helpers shared by the Paper built-in listeners.
 */
@NotNullByDefault
final class PaperBuiltInSupport {

    private PaperBuiltInSupport() {
    }

    /**
     * Registers the given built-in event types with payload generation 1.
     */
    static void register(KansokushaApi api, Key... eventTypes) {
        for (var eventType : eventTypes) {
            api.registerEventType(new EventTypeDefinition(eventType, PayloadGeneration.FIRST));
        }
    }

    static BlockPosition position(Block block) {
        return new BlockPosition(block.getX(), block.getY(), block.getZ());
    }

    static BlockPosition position(BlockState state) {
        return new BlockPosition(state.getX(), state.getY(), state.getZ());
    }
}
