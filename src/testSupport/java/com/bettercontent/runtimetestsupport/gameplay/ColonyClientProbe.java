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
    private static long startTick, creation, submission, requests, legacyCreation, tintedCreation;
    private static int density;
    private static ParticleStatus particles;
    private static boolean completed, colorWindow;
    private static int expectedRgb;
    private static String expectedSpecies;
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
        startTick = -1; completed = false; colorWindow = false; expectedSpecies = null; expectedRgb = 0;
        return Map.of("armed", true, "state", state, "control", mode);
    }
    /** Additive native-color window; caller binds expected values to the independent server receipt. */
    public static Map<String, Object> startColor(Minecraft mc, String kind, String control, String speciesId, int rgb) {
        boolean positive = kind.equals("occupied-nest") || kind.equals("active-apiary");
        if (positive ? (!List.of("complicated_bees:forest", "complicated_bees:plains").contains(speciesId) || rgb < 0 || rgb > 0xffffff)
                : (!speciesId.equals("invalid") || rgb != -1)) throw new IllegalStateException("Invalid native species/color receipt binding");
        var result = new java.util.LinkedHashMap<String, Object>(start(mc, kind, control));
        colorWindow = true; expectedSpecies = speciesId; expectedRgb = rgb;
        result.put("native_expected_species_id", speciesId); result.put("native_expected_rgb", rgb);
        return result;
    }
    private static String colorMismatch(Minecraft mc) {
        if (!colorWindow) return null;
        var entity = mc.level.getBlockEntity(POS);
        var appearance = ColonySwarms.bodyColor(entity);
        if (!expected()) return appearance.known() || appearance.rgb() != 0 ? "known tint after invalidation" : null;
        if (!appearance.known() || appearance.rgb() != expectedRgb) return "native body RGB/primitive metadata sync";
        if (entity instanceof BeeNestBlockEntity nest && (nest.getSpecies().getColor() != expectedRgb
                || !com.accbdd.complicated_bees.registry.SpeciesRegistration.getResourceLocation(nest.getSpecies()).toString().equals(expectedSpecies)))
            return "native nest species sync";
        return null;
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
        if (colorWindow && !mode.equals("unload")) {
            String mismatch = colorMismatch(mc);
            if (mismatch != null) {
                if (startTick >= 0) throw new IllegalStateException("Native color changed during armed100tick window: " + mismatch);
                return Map.of("ready", false, "waiting", mismatch);
            }
        }
        if (startTick < 0) {
            startTick = mc.level.getGameTime();
            legacyCreation = GameplayClientProbe.nativeParticleCount("better_compat_fixes:colony_bee");
            tintedCreation = GameplayClientProbe.nativeParticleCount("better_compat_fixes:colony_bee_tinted_v1");
            creation = legacyCreation + tintedCreation;
            submission = GameplayClientProbe.nativeParticleCount("submitted:com.bettercontent.bettercompatfixes.client.bees.ColonyBeeParticle");
            requests = ColonySwarms.emittedCount();
        }
        long elapsed = mc.level.getGameTime() - startTick;
        if (elapsed < 100) return Map.of("ready", false, "elapsed_native_ticks", elapsed);
        long legacy = GameplayClientProbe.nativeParticleCount("better_compat_fixes:colony_bee") - legacyCreation;
        long tinted = GameplayClientProbe.nativeParticleCount("better_compat_fixes:colony_bee_tinted_v1") - tintedCreation;
        long created = GameplayClientProbe.nativeParticleCount("better_compat_fixes:colony_bee")
                + GameplayClientProbe.nativeParticleCount("better_compat_fixes:colony_bee_tinted_v1") - creation;
        long added = GameplayClientProbe.nativeParticleCount("submitted:com.bettercontent.bettercompatfixes.client.bees.ColonyBeeParticle") - submission;
        long emitted = ColonySwarms.emittedCount() - requests;
        {
            boolean positive = expected() && List.of("normal", "reduced", "dense").contains(mode);
            if (positive ? (created <= 0 || added <= 0 || emitted <= 0) : (created != 0 || added != 0 || emitted != 0))
                throw new IllegalStateException("native colony engine positive/negative mismatch: " + state + "/" + mode + " created=" + created + " added=" + added + " requested=" + emitted);
            long maximum = 8 * (elapsed / 10 + 1); // Inclusive native ten-tick phase boundary.
            if (emitted > maximum || created > maximum || added > maximum) throw new IllegalStateException("native colony bounded emission window exceeded 8 per ten ticks");
            completed = true;
            var result = new java.util.LinkedHashMap<String, Object>(Map.of("ready", true, "state", state, "control", mode, "native_occupied", occupied,
                "native_ticks", elapsed, "engine_creations", created, "engine_submissions", added, "emission_requests", emitted,
                "native_chunk_loaded", loaded, "visual_verdict", "PENDING_LLM_FRAMEBUFFER_REVIEW"));
            result.put("engine_creations_legacy", legacy);
            result.put("engine_creations_tinted_v1", tinted);
            if (colorWindow) {
                if (positive && (tinted <= 0 || legacy != 0)) throw new IllegalStateException("New native-color window requires typed engine creations and zero legacy creations");
                var appearance = loaded ? ColonySwarms.bodyColor(mc.level.getBlockEntity(POS))
                        : com.bettercontent.bettercompatfixes.compat.bees.DecodedColorScope.Sample.UNKNOWN;
                result.put("native_expected_species_id", expectedSpecies); result.put("native_expected_rgb", expectedRgb);
                result.put("cosmetic_color_known", appearance.known()); result.put("cosmetic_body_rgb", appearance.rgb());
                result.put("native_color_agreement", mode.equals("unload") ? !appearance.known() : colorMismatch(mc) == null);
            }
            return result;
        }
    }
    public static Map<String, Object> finish(Minecraft mc) {
        if (state == null || !completed) throw new IllegalStateException("native colony window has not completed");
        BcFixesClientConfig.BEE_SWARM_DENSITY.set(density); mc.options.particles().set(particles); state = null;
        return Map.of("restored", true, "origin", "fixture-only native settings restored after actual framebuffer capture");
    }
}
