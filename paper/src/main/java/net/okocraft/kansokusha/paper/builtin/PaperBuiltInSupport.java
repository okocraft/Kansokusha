package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Whether TNT primes instead of being destroyed. While this is true, a TNT block that is
     * lit or exploded is recorded as tnt_prime rather than as a block change.
     */
    static boolean tntExplodes(World world) {
        return Boolean.TRUE.equals(world.getGameRuleValue(GameRules.TNT_EXPLODES));
    }

    static BlockPosition position(Block block) {
        return new BlockPosition(block.getX(), block.getY(), block.getZ());
    }

    static BlockPosition position(BlockState state) {
        return new BlockPosition(state.getX(), state.getY(), state.getZ());
    }

    static Key type(Material material) {
        return PaperKansokusha.key(material.getKey());
    }

    static Key blockType(BlockData blockData) {
        return type(blockData.getMaterial());
    }

    /**
     * The target type of a block change: the block before the change, or the block after the
     * change when an empty block was filled.
     */
    static Key changedBlockType(BlockData before, BlockData after) {
        return blockType(before.getMaterial().isAir() ? after : before);
    }

    static @Nullable Key itemType(@Nullable ItemStack item) {
        return item == null || item.isEmpty() ? null : type(item.getType());
    }

    static Key entityType(Entity entity) {
        return PaperKansokusha.key(entity.getType().getKey());
    }

    static EventActor actor(Entity entity) {
        return entity instanceof Player player
            ? new PlayerActor(player.getUniqueId())
            : new EntityActor(entity.getUniqueId(), entityType(entity));
    }

    static @Nullable EventActor nullableActor(@Nullable Entity entity) {
        return entity == null ? null : actor(entity);
    }

    static BlockActor actor(BlockData blockData) {
        return new BlockActor(blockType(blockData));
    }
}
