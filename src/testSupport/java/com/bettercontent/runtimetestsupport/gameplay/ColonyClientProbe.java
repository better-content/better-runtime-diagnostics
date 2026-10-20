package com.bettercontent.runtimetestsupport.gameplay;

import com.accbdd.complicated_bees.bees.Species;
import com.accbdd.complicated_bees.block.entity.BeeNestBlockEntity;
import com.accbdd.complicated_bees.block.entity.ApiaryBlockEntity;
import com.bettercontent.bettercompatfixes.client.bees.ColonySwarms;
import com.bettercontent.bettercompatfixes.compat.bees.ColonyActivity;
import com.bettercontent.bettercompatfixes.config.BcFixesClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Map;

/** Checks production client activity against native synchronized BE state and actual engine
 * creation/submission. Emission requests alone are never promoted to rendering acceptance. */
public final class ColonyClientProbe {
    private static final BlockPos POS = new BlockPos(1_000_008, 316, 1_000_000);
    private static String state, mode;
    private static long startTick, creation, submission, requests;
    private static int density;
    private static ParticleStatus particles;
    private static boolean completed;
    private ColonyClientProbe() {}
    private static boolean expected() { return state.equals("occupied-nest") || state.equals("active-apiary"); }
    private static boolean nativeOccupied(Minecraft mc) {
        var entity = mc.level.getBlockEntity(POS);
        if (entity instanceof BeeNestBlockEntity nest) return nest.getSpecies() != Species.INVALID;
        if (entity instanceof ApiaryBlockEntity && entity instanceof ColonyActivity activity) return activity.betterCompat$colonyActive();
        throw new IllegalStateException("native client colony block not synchronized yet");
    }
    public static Map<String, Object> start(Minecraft mc, String kind, String control) {
        if (state != null || !List.of("occupied-nest", "empty-nest", "active-apiary", "inactive-apiary").contains(kind)
                || !List.of("normal", "reduced", "minimal", "disabled", "range", "dense", "unload").contains(control)) throw new IllegalStateException("invalid/overlapping native colony window");
        state = kind; mode = control;
        density = BcFixesClientConfig.BEE_SWARM_DENSITY.get(); particles = mc.options.particles().get();
        BcFixesClientConfig.BEE_SWARM_DENSITY.set(control.equals("disabled") ? 0 : control.equals("dense") ? 4 : 2);
        mc.options.particles().set(control.equals("minimal") ? ParticleStatus.MINIMAL : control.equals("reduced") ? ParticleStatus.DECREASED : ParticleStatus.ALL);
        startTick = -1; completed = false;
        return Map.of("armed", true, "state", state, "control", mode);
    }
    public static Map<String, Object> observe(Minecraft mc) {
        if (state == null) throw new IllegalStateException("native colony observer unarmed");
        boolean loaded = mc.level.hasChunkAt(POS);
        boolean occupied = false;
        if (mode.equals("unload")) {
            if (loaded) return Map.of("ready", false, "waiting", "native chunk unload packet");
        } else {
            var entity = mc.level.getBlockEntity(POS);
            if (!(entity instanceof BeeNestBlockEntity) && !(entity instanceof ApiaryBlockEntity)) return Map.of("ready", false, "waiting", "native block entity sync");
            occupied = nativeOccupied(mc);
            if (occupied != expected()) return Map.of("ready", false, "waiting", "native occupancy/activity sync", "occupied", occupied);
            if (ColonySwarms.occupied(entity) != occupied) throw new IllegalStateException("production colony predicate differs from native synchronized state");
            boolean inRange = POS.distToCenterSqr(mc.player.position()) <= 24 * 24;
            if (inRange == mode.equals("range")) return Map.of("ready", false, "waiting", "native client range/arrival state");
            if (mode.equals("dense")) {
                int colonies = 0;
                for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) for (int dy = 0; dy < 3; dy++)
                    if (mc.level.getBlockEntity(POS.offset(dx, dy, dz)) instanceof BeeNestBlockEntity nest && nest.getSpecies() != Species.INVALID) colonies++;
                if (colonies != 675) return Map.of("ready", false, "waiting", "all 675 actual native colony block entities", "loaded_occupied", colonies);
            }
        }
        if (startTick < 0) {
            startTick = mc.level.getGameTime();
            creation = GameplayClientProbe.nativeParticleCount("better_compat_fixes:colony_bee");
            submission = GameplayClientProbe.nativeParticleCount("submitted:com.bettercontent.bettercompatfixes.client.bees.ColonyBeeParticle");
            requests = ColonySwarms.emittedCount();
        }
        long elapsed = mc.level.getGameTime() - startTick;
        if (elapsed < 100) return Map.of("ready", false, "elapsed_native_ticks", elapsed);
        long created = GameplayClientProbe.nativeParticleCount("better_compat_fixes:colony_bee") - creation;
        long added = GameplayClientProbe.nativeParticleCount("submitted:com.bettercontent.bettercompatfixes.client.bees.ColonyBeeParticle") - submission;
        long emitted = ColonySwarms.emittedCount() - requests;
        {
            boolean positive = expected() && List.of("normal", "reduced", "dense").contains(mode);
            if (positive ? (created <= 0 || added <= 0 || emitted <= 0) : (created != 0 || added != 0 || emitted != 0))
                throw new IllegalStateException("native colony engine positive/negative mismatch: " + state + "/" + mode + " created=" + created + " added=" + added + " requested=" + emitted);
            long maximum = 8 * (elapsed / 10 + 1); // Inclusive native ten-tick phase boundary.
            if (emitted > maximum || created > maximum || added > maximum) throw new IllegalStateException("native colony bounded emission window exceeded 8 per ten ticks");
            completed = true;
            return Map.of("ready", true, "state", state, "control", mode, "native_occupied", occupied,
                "native_ticks", elapsed, "engine_creations", created, "engine_submissions", added, "emission_requests", emitted,
                "native_chunk_loaded", loaded, "visual_verdict", "PENDING_LLM_FRAMEBUFFER_REVIEW");
        }
    }
    public static Map<String, Object> finish(Minecraft mc) {
        if (state == null || !completed) throw new IllegalStateException("native colony window has not completed");
        BcFixesClientConfig.BEE_SWARM_DENSITY.set(density); mc.options.particles().set(particles); state = null;
        return Map.of("restored", true, "origin", "fixture-only native settings restored after actual framebuffer capture");
    }
}
