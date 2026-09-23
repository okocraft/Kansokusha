package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.projectiles.BlockProjectileSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

@NotNullByDefault
record PaperEntityAttribution(
    @Nullable UUID entityId,
    @Nullable String entityType,
    @Nullable UUID sourceEntityId,
    @Nullable String sourceEntityType,
    @Nullable UUID shooterEntityId,
    @Nullable String shooterEntityType,
    @Nullable BlockReference shooterBlock,
    @Nullable UUID ownerId,
    @Nullable PlayerSubject subject
) {

    static PaperEntityAttribution capture(@Nullable Entity entity) {
        if (entity == null) {
            return new PaperEntityAttribution(
                null, null, null, null, null, null, null, null, null
            );
        }

        var entityId = entity.getUniqueId();
        var entityType = entity.getType().name();
        UUID sourceEntityId = null;
        String sourceEntityType = null;
        UUID shooterEntityId = null;
        String shooterEntityType = null;
        BlockReference shooterBlock = null;
        UUID ownerId = null;
        PlayerSubject subject = entity instanceof Player
            ? new PlayerSubject(entityId)
            : null;

        if (entity instanceof TNTPrimed primed) {
            var source = primed.getSource();
            if (source != null) {
                sourceEntityId = source.getUniqueId();
                sourceEntityType = source.getType().name();
                if (subject == null && source instanceof Player) {
                    subject = new PlayerSubject(sourceEntityId);
                }
            }
        }

        if (entity instanceof Projectile projectile) {
            ownerId = projectile.getOwnerUniqueId();
            var shooter = projectile.getShooter();
            if (shooter instanceof Entity shooterEntity) {
                shooterEntityId = shooterEntity.getUniqueId();
                shooterEntityType = shooterEntity.getType().name();
                if (subject == null && shooterEntity instanceof Player) {
                    subject = new PlayerSubject(shooterEntityId);
                }
            } else if (shooter instanceof BlockProjectileSource blockSource) {
                shooterBlock = BlockReference.capture(blockSource.getBlock());
            }
        }

        if (entity instanceof Tameable tameable && ownerId == null) {
            ownerId = tameable.getOwnerUniqueId();
        }

        return new PaperEntityAttribution(
            entityId,
            entityType,
            sourceEntityId,
            sourceEntityType,
            shooterEntityId,
            shooterEntityType,
            shooterBlock,
            ownerId,
            subject
        );
    }

    record BlockReference(Key worldKey, BlockPosition position) {

        static BlockReference capture(Block block) {
            return new BlockReference(
                PaperKansokusha.key(block.getWorld().getKey()),
                new BlockPosition(block.getX(), block.getY(), block.getZ())
            );
        }
    }
}
