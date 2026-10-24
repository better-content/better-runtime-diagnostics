package com.bettercontent.runtimetestsupport.mixin.gameplay;

import com.bettercontent.runtimetestsupport.gameplay.WorldHooksInteractionWorldProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = com.bettercontent.betterburiedencounters.worldgen.MonsterSpawner.class, remap = false)
public abstract class BuriedSpawnerObservationMixin {
    @Inject(method = "spawn", at = @At("HEAD"), require = 1)
    private static void worldHooks$nativeSpawnPath(Level level, BlockPos pos, ResourceLocation id, CallbackInfo ci) {
        WorldHooksInteractionWorldProbe.recordNativeSpawnerCall(level, pos, id);
    }
}
