package com.bettercontent.runtimetestsupport.diagnostics;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import untamedwilds.entity.ComplexMob;
import untamedwilds.util.EntityDataListenerEvent;

/** Fixture-only causal observation. Native species lookup, return99 and WARN are untouched. */
@Mixin(targets = "untamedwilds.util.EntityUtils", remap = false)
public abstract class UntamedSpeciesObservationMixin {
    @Unique private static final int JOURNAL_SPECIES_LIMIT = 16;
    @Unique private static final int JOURNAL_SPECIES_STACK_LIMIT = 64;
    @Unique private static final Set<String> journalSpeciesDistinct = new HashSet<>();

    @Inject(method = "getNumberOfSpecies", at = @At("HEAD"), remap = false, cancellable = false, require = 1)
    private static void journalObserveMissingSpecies(EntityType<?> type, CallbackInfoReturnable<Integer> callback) {
        try {
            String run = System.getProperty("bc.pack_test.diagnostics.run_id", "");
            String directory = System.getProperty("bc.pack_test.diagnostics.dir", "");
            if (!NonfiniteRotationMixinPlugin.explicitFixture(run, directory)) return;
            if (type == null) return;
            boolean loaded = EntityDataListenerEvent.isEntityDataLoaded();
            boolean serverPresent = ComplexMob.ENTITY_DATA_HASH.containsKey(type);
            boolean clientPresent = ComplexMob.CLIENT_DATA_HASH.containsKey(type);
            if (!loaded || serverPresent || clientPresent) return;
            synchronized (journalSpeciesDistinct) {
                if (journalSpeciesDistinct.size() >= JOURNAL_SPECIES_LIMIT) return;
            }
            int serverSize = ComplexMob.ENTITY_DATA_HASH.size(), clientSize = ComplexMob.CLIENT_DATA_HASH.size();
            ResourceLocation registryKey = ForgeRegistries.ENTITY_TYPES == null ? null : ForgeRegistries.ENTITY_TYPES.getKey(type);
            String key = registryKey == null ? "<unregistered>" : registryKey.toString();
            // Same pure loader getter/resource-key construction used by native registerEntityData;
            // importantly, do NOT call that registering method or ComplexMob.getEntityData here.
            ResourceLocation holderKey = registryKey == null ? null : new ResourceLocation("untamedwilds", registryKey.getPath());
            Boolean holderPresent = holderKey == null ? null : EntityDataListenerEvent.ENTITY_DATA_HOLDERS.getData(holderKey) != null;
            Thread thread = Thread.currentThread();
            StackTraceElement[] allFrames = thread.getStackTrace();
            StackTraceElement[] frames = Arrays.copyOf(allFrames, Math.min(allFrames.length, JOURNAL_SPECIES_STACK_LIMIT));
            String stack = Arrays.toString(frames);
            String state = key + "|" + loaded + "|" + serverPresent + "|" + clientPresent + "|" + serverSize
                + "|" + clientSize + "|" + holderPresent + "|" + thread.getName() + "|" + stack;
            int sequence;
            synchronized (journalSpeciesDistinct) {
                if (journalSpeciesDistinct.contains(state) || journalSpeciesDistinct.size() >= JOURNAL_SPECIES_LIMIT) return;
                journalSpeciesDistinct.add(state);
                sequence = journalSpeciesDistinct.size();
            }
            var flags = new LinkedHashMap<String, String>();
            for (String flag : new String[]{"player", "mode", "client_sha256", "server_sha256", "runtime_sha256", "support_sha256"})
                flags.put(flag, System.getProperty("bc.pack_test.diagnostics." + flag, "<absent>"));
            LoggerFactory.getLogger("runtime-fixture/untamed-species").info(
                "untamed-species-entry: sequence={} run_id={} dir={} type={} loaded={} server_present={} server_size={} client_present={} client_size={} holder_key={} holder_present={} thread={} thread_id={} flags={} stack_truncated={} stack={}",
                sequence, run, directory, key, loaded, serverPresent, serverSize, clientPresent, clientSize,
                holderKey == null ? "<unregistered>" : holderKey.toString(), holderPresent,
                thread.getName(), thread.getId(), flags, allFrames.length > JOURNAL_SPECIES_STACK_LIMIT, stack);
        } catch (Throwable observerFailure) {
            // An observer failure must never replace native lookup behavior or produce a second error.
        }
    }
}
