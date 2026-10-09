package com.bettercontent.runtimetestsupport.diagnostics;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Non-mutating diagnostics are enabled only by an explicit disposable fixture identity. */
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
        if ("net.minecraft.world.entity.Entity".equals(target))
            return "com.bettercontent.runtimetestsupport.diagnostics.NonfiniteRotationMixin".equals(mixin);
        if (!"untamedwilds.util.EntityUtils".equals(target)
                || !"com.bettercontent.runtimetestsupport.diagnostics.UntamedSpeciesObservationMixin".equals(mixin)) return false;
        try {
            var loading = FMLLoader.getLoadingModList();
            boolean pinned = loading != null && loading.getMods().stream().anyMatch(mod ->
                "untamedwilds".equals(mod.getModId()) && "4.0.4".equals(mod.getVersion().toString()));
            LoggerFactory.getLogger("runtime-fixture/nonfinite-rotation").info("species-probe-gate: enabled={} native_version=4.0.4", pinned);
            return pinned;
        } catch (Throwable observerFailure) { return false; }
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
