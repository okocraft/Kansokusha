package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.okocraft.kansokusha.neoforge.NativeEntityRecorder;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockAttachedEntity.class)
public abstract class NativeHangingDamageMixin {
    @WrapMethod(method = "hurtServer")
    private boolean kansokusha$damage(ServerLevel level, DamageSource source, float amount,
                                      Operation<Boolean> original) {
        NativeEntityRecorder.beginHangingDamage((BlockAttachedEntity) (Object) this, source);
        try {
            return original.call(level, source, amount);
        } finally {
            NativeEntityRecorder.endHangingDamage();
        }
    }
}
