package com.bettercontent.runtimetestsupport.mixin.diagnostics;

import com.bettercontent.runtimetestsupport.gameplay.GameplayClientProbe;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only native particle creation: cannot cancel, substitute, or spawn. */
@Mixin(ParticleEngine.class)
public abstract class GameplayParticleMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void bc$submitted(Particle particle, CallbackInfo ci) {
        var center = particle.getBoundingBox().getCenter();
        GameplayClientProbe.submittedParticle(particle.getClass().getName(), center.x, center.y, center.z);
    }
    @Inject(method = "createParticle", at = @At("RETURN"))
    private void bc$observe(ParticleOptions options, double x, double y, double z,
                            double vx, double vy, double vz, CallbackInfoReturnable<Particle> ci) {
        if (ci.getReturnValue() != null)
            GameplayClientProbe.particle(BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()).toString(), x, y, z);
    }
}
