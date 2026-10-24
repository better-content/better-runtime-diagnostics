package com.bettercontent.runtimetestsupport.mixin.gameplay;

import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Explosion.class)
public interface ExplosionRadiusAccess {
    @Accessor("radius") float worldHooks$radius();
}
