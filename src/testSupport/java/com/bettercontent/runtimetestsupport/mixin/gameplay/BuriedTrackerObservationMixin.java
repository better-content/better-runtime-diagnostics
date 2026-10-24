package com.bettercontent.runtimetestsupport.mixin.gameplay;

import com.bettercontent.runtimetestsupport.gameplay.WorldHooksInteractionWorldProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only witnesses, not product-handler drivers. */
@Mixin(value = com.bettercontent.betterburiedencounters.worldgen.ExplosionRemovalTracker.class, remap = false)
public abstract class BuriedTrackerObservationMixin {
    @Inject(method = "watch", at = @At("RETURN"), require = 1)
    private static void worldHooks$watched(ServerLevel level, BlockPos pos, ResourceLocation id, CallbackInfo ci) {
        WorldHooksInteractionWorldProbe.recordNativeWatch(level, pos, id);
    }
    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private static void worldHooks$beginTrackerTick(ServerLevel level, CallbackInfo ci) { WorldHooksInteractionWorldProbe.beginNativeTrackerTick(level); }
    @Inject(method = "tick", at = @At("RETURN"), require = 1)
    private static void worldHooks$endTrackerTick(ServerLevel level, CallbackInfo ci) { WorldHooksInteractionWorldProbe.endNativeTrackerTick(); }
}
