package com.bettercontent.runtimetestsupport.diagnostics;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Explicit disposable-fixture diagnostics. Gameplay's reviewed hold driver has an additional Debug gate. */
public final class NonfiniteRotationMixinPlugin implements IMixinConfigPlugin {
    private boolean enabled;
    public static boolean explicitFixture(String run, String directory) {
        try {
            return run != null && run.matches("[0-9]{8}T[0-9]{6}Z-[0-9]+")
                && directory != null && !directory.isBlank() && Path.of(directory).isAbsolute();
        } catch (RuntimeException failure) { return false; }
    }
    @Override public void onLoad(String mixinPackage) {
        try {
            String run = System.getProperty("bc.pack_test.diagnostics.run_id", "");
            String directory = System.getProperty("bc.pack_test.diagnostics.dir", "");
            enabled = explicitFixture(run, directory);
            LoggerFactory.getLogger("runtime-fixture/nonfinite-rotation").info(
                "rotation-probe-gate: enabled={} run={} directory={} player={} mode={}", enabled, run, directory,
                System.getProperty("bc.pack_test.diagnostics.player", "<absent>"),
                System.getProperty("bc.pack_test.diagnostics.mode", "<absent>"));
        } catch (Throwable observerFailure) { enabled = false; }
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (!enabled) return false;
        if (gameplayMixin(target, mixin, System.getProperty("bc.pack_test.diagnostics.mode", ""),
                Boolean.getBoolean("bc.pack_test.gameplay_client"))) return true;
        if (worldHooksMixin(target, mixin, System.getProperty("bc.pack_test.diagnostics.mode", ""),
                Boolean.getBoolean("bc.pack_test.gameplay_world"))) {
            try {
                java.util.UUID.fromString(System.getProperty("bc.pack_test.gameplay_world.nonce", ""));
                for (String kind : List.of("client", "server", "runtime", "support"))
                    if (!System.getProperty("bc.pack_test.diagnostics." + kind + "_sha256", "").matches("[a-f0-9]{64}")) return false;
                return true;
            } catch (RuntimeException absentBinding) { return false; }
        }
        if ("net.minecraft.world.entity.Entity".equals(target))
            return "com.bettercontent.runtimetestsupport.mixin.diagnostics.NonfiniteRotationMixin".equals(mixin);
        if (!"untamedwilds.util.EntityUtils".equals(target)
                || !"com.bettercontent.runtimetestsupport.mixin.diagnostics.UntamedSpeciesObservationMixin".equals(mixin)) return false;
        try {
            var loading = FMLLoader.getLoadingModList();
            boolean pinned = loading != null && loading.getMods().stream().anyMatch(mod ->
                "untamedwilds".equals(mod.getModId()) && "4.0.4".equals(mod.getVersion().toString()));
            LoggerFactory.getLogger("runtime-fixture/nonfinite-rotation").info("species-probe-gate: enabled={} native_version=4.0.4", pinned);
            return pinned;
        } catch (Throwable observerFailure) { return false; }
    }
    public static boolean gameplayMixin(String target, String mixin, String mode, boolean requested) {
        if (!requested || !"debug".equals(mode)) return false;
        if ("net.minecraft.client.particle.ParticleEngine".equals(target))
            return "com.bettercontent.runtimetestsupport.mixin.diagnostics.GameplayParticleMixin".equals(mixin);
        return "net.minecraft.client.multiplayer.MultiPlayerGameMode".equals(target)
            && Set.of("com.bettercontent.runtimetestsupport.mixin.diagnostics.GameplayCutMixin",
                "com.bettercontent.runtimetestsupport.mixin.diagnostics.GameplayCutDriverMixin").contains(mixin);
    }
    public static boolean worldHooksMixin(String target, String mixin, String mode, boolean requested) {
        if (!requested || !"debug".equals(mode)) return false;
        String owner="com.bettercontent.runtimetestsupport.mixin.gameplay.";
        return switch (target) {
            case "net.minecraft.world.level.Explosion" -> (owner+"ExplosionRadiusAccess").equals(mixin);
            case "com.bettercontent.betterburiedencounters.worldgen.ExplosionRemovalTracker" -> (owner+"BuriedTrackerObservationMixin").equals(mixin);
            case "com.bettercontent.betterburiedencounters.worldgen.MonsterSpawner" -> (owner+"BuriedSpawnerObservationMixin").equals(mixin);
            case "com.vinlanx.explosionoverhaul.ServerExplosionHandler" -> (owner+"NativeEngineObservationMixin").equals(mixin);
            case "com.vinlanx.explosionoverhaul.CraterDeformer" -> (owner+"NativeCraterObservationMixin").equals(mixin);
            default -> false;
        };
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
