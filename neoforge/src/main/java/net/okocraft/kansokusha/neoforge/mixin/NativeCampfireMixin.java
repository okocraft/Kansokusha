package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

@Mixin(CampfireBlockEntity.class)
abstract class NativeCampfireMixin {
    private static final ThreadLocal<List<ItemStack>> RESULTS = new ThreadLocal<>();

    @WrapMethod(method = "cookTick")
    private static void kansokusha$cook(ServerLevel level, BlockPos pos, BlockState state,
                                        CampfireBlockEntity campfire,
                                        RecipeManager.CachedCheck<SingleRecipeInput, CampfireCookingRecipe> recipes,
                                        Operation<Void> original) {
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null) {
            original.call(level, pos, state, campfire, recipes);
            return;
        }
        var before = campfire.getItems().stream().map(ItemStack::copy).toList();
        var results = new ArrayList<ItemStack>();
        var previous = RESULTS.get();
        RESULTS.set(results);
        try {
            original.call(level, pos, state, campfire, recipes);
        } finally {
            if (previous == null) RESULTS.remove(); else RESULTS.set(previous);
        }
        int result = 0;
        for (int slot = 0; slot < before.size() && result < results.size(); slot++) {
            if (!before.get(slot).isEmpty() && campfire.getItems().get(slot).isEmpty())
                recorder.recordCookProcess(level, pos, before.get(slot), results.get(result++));
        }
    }

    @WrapOperation(method = "cookTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/Containers;dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V"))
    private static void kansokusha$dropCooked(Level level, double x, double y, double z, ItemStack stack,
                                                Operation<Void> original) {
        original.call(level, x, y, z, stack);
        var results = RESULTS.get();
        if (results != null) results.add(stack.copy());
    }
}
