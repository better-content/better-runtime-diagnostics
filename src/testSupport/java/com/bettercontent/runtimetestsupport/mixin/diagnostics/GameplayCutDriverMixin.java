package com.bettercontent.runtimetestsupport.mixin.diagnostics;

import com.bettercontent.runtimetestsupport.gameplay.GameplayCutDriver;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reviewed NONSHIPPING fixture mutation, separate from read-only native progress observations.
 * No fields/return values/inputs are changed. Only release of an exact bound held-cut is cancelled. */
@Mixin(value = MultiPlayerGameMode.class, priority = 2000)
public abstract class GameplayCutDriverMixin {
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), require = 1)
    private void bc$newTarget(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> ci) {
        GameplayCutDriver.stop(Minecraft.getInstance()); // clears BEFORE a new native start, never cancels it
    }
    @Inject(method = "stopDestroyBlock", at = @At("HEAD"), cancellable = true, require = 1)
    private void bc$authoredHold(CallbackInfo ci) {
        if (GameplayCutDriver.suppressUnrelatedRelease()) ci.cancel();
    }
}
