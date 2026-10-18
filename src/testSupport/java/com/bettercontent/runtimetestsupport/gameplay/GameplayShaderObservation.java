package com.bettercontent.runtimetestsupport.gameplay;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraftforge.fml.ModList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact Oculus 1.8.0 public typed API observations only. Never toggles/reloads or patches rendering. */
public final class GameplayShaderObservation {
    public static final String EXPECTED_PACK = "ComplementaryReimagined_r5.8.1.zip";
    private GameplayShaderObservation() {}
    public static Map<String, Object> observe(boolean required) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean loaded = ModList.get().isLoaded("oculus");
        result.put("oculus_loaded", loaded);
        if (!loaded) {
            if (required) throw new IllegalStateException("required pinned Oculus is not loaded");
            result.put("verdict", "UNAVAILABLE"); return result;
        }
        var api = IrisApi.getInstance();
        boolean enabled = api.getConfig().areShadersEnabled();
        boolean inUse = api.isShaderPackInUse();
        boolean fallback = Iris.isFallback();
        boolean error = Iris.getStoredError().isPresent();
        String name = Iris.getCurrentPackName();
        result.put("oculus_version", ModList.get().getModContainerById("oculus").orElseThrow().getModInfo().getVersion().toString());
        result.put("iris_version", Iris.getVersion()); result.put("api_minor_revision", api.getMinorApiRevision());
        result.put("enabled", enabled); result.put("in_use", inUse); result.put("fallback", fallback);
        result.put("native_pack_present", Iris.getCurrentPack().isPresent()); result.put("native_pack_name", name);
        result.put("shadow_pass", api.isRenderingShadowPass()); result.put("stored_error", error);
        if (error) result.put("error_message", String.valueOf(Iris.getStoredError().orElseThrow().getMessage()));
        if (inUse && EXPECTED_PACK.equals(name)) {
            Path directory = Iris.getShaderpacksDirectory().toAbsolutePath().normalize();
            Path pack = directory.resolve(name).normalize();
            Path expected = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve("shaderpacks");
            for (Path path = pack; path != null; path = path.getParent())
                if (Files.isSymbolicLink(path)) throw new IllegalStateException("symlink active shaderpack boundary");
            if (!directory.equals(expected) || !pack.getParent().equals(directory) || !Files.isRegularFile(pack) || Files.size(pack) > 268_435_456L)
                throw new IllegalStateException("active shaderpack ZIP is not the candidate's regular native pack");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(pack)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            result.put("pack_sha256", HexFormat.of().formatHex(digest.digest()));
            result.put("pack_version", "5.8.1"); result.put("version_origin", "exact candidate-selected filename; SHA-256 identifies actual loaded ZIP bytes");
        }
        if (required && (!enabled || !inUse || fallback || error || !Iris.getCurrentPack().isPresent()
                || !EXPECTED_PACK.equals(name) || api.isRenderingShadowPass()))
            throw new IllegalStateException("required native shaderpack failed/disabled/fallback: " + result);
        result.put("verdict", inUse && !fallback && !error ? "ACTIVE_NATIVE_SHADERPACK" : "NOT_ACTIVE");
        return result;
    }
}
