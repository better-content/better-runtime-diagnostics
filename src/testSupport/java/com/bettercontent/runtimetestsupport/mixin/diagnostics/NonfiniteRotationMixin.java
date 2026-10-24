package com.bettercontent.runtimetestsupport.mixin.diagnostics;

import com.bettercontent.runtimetestsupport.diagnostics.NonfiniteRotationProbe;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes native entry only. Default remapping uses the separate fixture annotation-processor refmap. */
@Mixin(Entity.class)
public abstract class NonfiniteRotationMixin {
    @Inject(method = "setYRot(F)V", at = @At("HEAD"), cancellable = false, require = 1)
    private void runtimefixtures$observeYaw(float requested, CallbackInfo callback) {
        try { NonfiniteRotationProbe.observe((Entity) (Object) this, "yaw", requested); }
        catch (Throwable observerFailure) { /* Preserve native code even if the observer cannot load. */ }
    }
    @Inject(method = "setXRot(F)V", at = @At("HEAD"), cancellable = false, require = 1)
    private void runtimefixtures$observePitch(float requested, CallbackInfo callback) {
        try { NonfiniteRotationProbe.observe((Entity) (Object) this, "pitch", requested); }
        catch (Throwable observerFailure) { /* Preserve native code even if the observer cannot load. */ }
    }
}
