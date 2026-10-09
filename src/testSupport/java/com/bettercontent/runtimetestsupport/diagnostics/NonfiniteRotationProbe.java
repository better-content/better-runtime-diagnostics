package com.bettercontent.runtimetestsupport.diagnostics;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.LoggerFactory;

/** Isolated observational fixture: INFO evidence only; native rejection/ERROR always remains intact. */
public final class NonfiniteRotationProbe {
    private static final int LIMIT = 16;
    private static final int STACK_LIMIT = 64;
    private static final Set<String> DISTINCT = new HashSet<>();
    private NonfiniteRotationProbe() {}

    public static void observe(Entity entity, String axis, float requested) {
        if (Float.isFinite(requested)) return;
        try {
            if (!NonfiniteRotationMixinPlugin.explicitFixture(
                    System.getProperty("bc.pack_test.diagnostics.run_id", ""),
                    System.getProperty("bc.pack_test.diagnostics.dir", ""))) return;
            if (entity == null) return;
            var level = entity.level();
            // Client rendering is not evidence of the dedicated/integrated server faults under study.
            if (level != null && level.isClientSide()) return;
            var nativeType = entity.getType();
            String type = nativeType == null || ForgeRegistries.ENTITY_TYPES == null ? "<constructor-null>"
                : String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(nativeType));
            Thread thread = Thread.currentThread();
            StackTraceElement[] allFrames = thread.getStackTrace();
            var frames = Arrays.copyOf(allFrames, Math.min(allFrames.length, STACK_LIMIT));
            String stack = Arrays.toString(frames);
            String key = axis + "|" + type + "|" + stack;
            int sequence;
            synchronized (DISTINCT) {
                if (DISTINCT.contains(key) || DISTINCT.size() >= LIMIT) return;
                DISTINCT.add(key); sequence = DISTINCT.size();
            }
            var flags = new LinkedHashMap<String, String>();
            for (String flag : new String[]{"run_id", "player", "mode", "client_sha256", "server_sha256", "runtime_sha256", "support_sha256"})
                flags.put(flag, System.getProperty("bc.pack_test.diagnostics." + flag, "<absent>"));
            LoggerFactory.getLogger("runtime-fixture/nonfinite-rotation").info(
                "nonfinite-rotation-entry: sequence={} axis={} requested={} current_yaw={} current_pitch={} type={} class={} entity_id={} uuid={} dimension={} position={} thread={} thread_id={} flags={} stack_truncated={} stack={}",
                sequence, axis, requested, entity.getYRot(), entity.getXRot(), type, entity.getClass().getName(),
                entity.getId(), String.valueOf(entity.getUUID()), level == null ? "<constructor-null>" : level.dimension().location(),
                entity.position(), thread.getName(), thread.getId(), flags, allFrames.length > STACK_LIMIT, stack);
        } catch (Throwable observerFailure) {
            // Even constructor/observer failure must not replace native setter behavior or its ERROR.
        }
    }
}
