package com.bettercontent.runtimetestsupport.mixin.diagnostics;

import com.bettercontent.runtimetestsupport.gameplay.GameplayClientProbe;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Read-only native progress after actual cutting calls; no production behavior override. */
@Mixin(MultiPlayerGameMode.class)
public abstract class GameplayCutMixin {
    @Shadow private float destroyProgress;
    @Inject(method = "continueDestroyBlock", at = @At("RETURN"))
    private void bc$observe(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> ci) {
        GameplayClientProbe.cut(pos, face, destroyProgress, ci.getReturnValue());
    }
}
