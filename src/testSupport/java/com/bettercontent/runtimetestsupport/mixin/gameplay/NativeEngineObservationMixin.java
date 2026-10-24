package com.bettercontent.runtimetestsupport.mixin.gameplay;

import com.bettercontent.runtimetestsupport.gameplay.WorldHooksInteractionWorldProbe;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = com.vinlanx.explosionoverhaul.ServerExplosionHandler.class, remap = false)
public abstract class NativeEngineObservationMixin {
    @Inject(method = "handleExplosion", at = @At("HEAD"), require = 1)
    private static void worldHooks$engineEntered(ServerLevel level, Explosion explosion, List<BlockPos> positions, CallbackInfo ci) {
        WorldHooksInteractionWorldProbe.beginNativeEngine(level, explosion);
    }
    @Inject(method = "handleExplosion", at = @At("RETURN"), require = 1)
    private static void worldHooks$engineReturned(ServerLevel level, Explosion explosion, List<BlockPos> positions, CallbackInfo ci) {
        WorldHooksInteractionWorldProbe.recordNativeEngineReturn(level, explosion, positions);
    }
}
