package com.bettercontent.runtimetestsupport.mixin.gameplay;

import com.bettercontent.runtimetestsupport.gameplay.WorldHooksInteractionWorldProbe;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(value = com.vinlanx.explosionoverhaul.CraterDeformer.class, remap = false)
public abstract class NativeCraterObservationMixin {
    @Inject(method = "getCraterBlocks", at = @At("RETURN"), require = 1)
    private static void worldHooks$craterReturned(ServerLevel level, Vec3 center, float power,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Set<BlockPos>> cir) {
        WorldHooksInteractionWorldProbe.recordNativeCraterReturn(level, center, power, cir.getReturnValue());
    }
}
