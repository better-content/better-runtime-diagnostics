package com.bettercontent.runtimetestsupport.gameplay;

import com.accbdd.complicated_bees.bees.Species;
import com.accbdd.complicated_bees.bees.GeneticHelper;
import com.accbdd.complicated_bees.bees.gene.enums.EnumTemperature;
import com.accbdd.complicated_bees.bees.gene.enums.EnumHumidity;
import com.accbdd.complicated_bees.block.entity.ApiaryBlockEntity;
import com.accbdd.complicated_bees.block.entity.BeeNestBlockEntity;
import com.accbdd.complicated_bees.registry.BlocksRegistration;
import com.accbdd.complicated_bees.registry.ItemsRegistration;
import com.accbdd.complicated_bees.registry.SpeciesRegistration;
import com.bettercontent.bettercompatfixes.compat.bees.ColonyActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import java.util.List;
import java.util.Map;

/** Authored real native colonies, not a replacement activity predicate or pollination test. */
public final class ColonyWorldProbe {
    public static final BlockPos POS = new BlockPos(1_000_008, 316, 1_000_000);
    private static String state;
    private static String requestedSpecies = "complicated_bees:forest";
    private static final java.util.ArrayList<BlockPos> dense = new java.util.ArrayList<>();
    private ColonyWorldProbe() {}
    public static Map<String, Object> setup(ServerPlayer p, String kind) {
        return setup(p, kind, "complicated_bees:forest");
    }
    public static Map<String, Object> setup(ServerPlayer p, String kind, String speciesId) {
        if (!List.of("occupied-nest", "empty-nest", "active-apiary", "inactive-apiary", "clear").contains(kind))
            throw new IllegalStateException("unknown colony fixture");
        Species selected = kind.equals("occupied-nest") || kind.equals("active-apiary") ? selectedSpecies(speciesId) : Species.INVALID;
        var level = p.serverLevel();
        for (var extra : dense) level.setBlock(extra, Blocks.AIR.defaultBlockState(), 3);
        dense.clear();
        if (state == null && !level.getBlockState(POS).isAir()) throw new IllegalStateException("colony fixture would replace foreign block");
        level.setBlock(POS, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(POS.north(), Blocks.AIR.defaultBlockState(), 3);
        state = kind;
        requestedSpecies = kind.equals("occupied-nest") || kind.equals("active-apiary") ? speciesId : "invalid";
        if (kind.equals("clear")) return Map.of("cleared", true);
        if (kind.endsWith("nest")) {
            level.setBlock(POS, BlocksRegistration.BEE_NEST.get().defaultBlockState(), 3);
            var nest = (BeeNestBlockEntity) level.getBlockEntity(POS);
            var species = kind.equals("occupied-nest") ? selected : Species.INVALID;
            if (kind.equals("occupied-nest") && species == Species.INVALID) throw new IllegalStateException("native forest species missing");
            nest.setSpecies(species); nest.setChanged();
            level.sendBlockUpdated(POS, level.getBlockState(POS), level.getBlockState(POS), 3);
        } else {
            level.setBlock(POS, BlocksRegistration.APIARY.get().defaultBlockState(), 3);
            var apiary = (ApiaryBlockEntity) level.getBlockEntity(POS);
            if (kind.equals("active-apiary")) {
                level.setBlock(POS.north(), Blocks.DANDELION.defaultBlockState(), 3);
                var species = selected;
                if (species == Species.INVALID) throw new IllegalStateException("native forest species missing");
                apiary.getBeeItems().setStackInSlot(0, species.toStack(ItemsRegistration.QUEEN.get()));
                apiary.getLogic().setLevel(level); apiary.getLogic().rebuildFlowerCache(); apiary.getLogic().checkConditions();
            }
        }
        p.teleportTo(level, POS.getX() + .5, 316, POS.getZ() + 5.5, 180, 5);
        return observe(p);
    }
    private static Species selectedSpecies(String id) {
        if (!List.of("complicated_bees:forest", "complicated_bees:plains").contains(id))
            throw new IllegalStateException("Only the two approved native species are fixture inputs");
        Species species = SpeciesRegistration.getFromResourceLocation(ResourceLocation.tryParse(id));
        if (species == null || species == Species.INVALID) throw new IllegalStateException("Requested native species missing: " + id);
        return species;
    }
    /** Existing native block entity, native setter/factory only; never edits genes or eligibility. */
    public static Map<String, Object> changeSpecies(ServerPlayer p, String id) {
        if (state == null || !dense.isEmpty()) throw new IllegalStateException("Species change requires a single authored colony");
        Species species = id.equals("invalid") ? Species.INVALID : selectedSpecies(id);
        var level = p.serverLevel();
        var entity = level.getBlockEntity(POS);
        if (entity instanceof BeeNestBlockEntity nest) {
            nest.setSpecies(species);
            state = species == Species.INVALID ? "empty-nest" : "occupied-nest";
        } else if (entity instanceof ApiaryBlockEntity apiary) {
            apiary.getBeeItems().setStackInSlot(0, species == Species.INVALID ? net.minecraft.world.item.ItemStack.EMPTY
                    : species.toStack(ItemsRegistration.QUEEN.get()));
            state = species == Species.INVALID ? "inactive-apiary" : "active-apiary";
            // Native notifications handle condition invalidation; do not force activity, climate or genes.
        } else throw new IllegalStateException("Authored native colony absent");
        requestedSpecies = id;
        entity.setChanged();
        level.sendBlockUpdated(POS, level.getBlockState(POS), level.getBlockState(POS), 3);
        if (level.getBlockEntity(POS) != entity) throw new IllegalStateException("Species change replaced native block entity");
        var result = new java.util.LinkedHashMap<String, Object>(observe(p));
        result.put("same_native_block_entity", true);
        return result;
    }
    public static Map<String, Object> dense(ServerPlayer p) {
        if (!"occupied-nest".equals(state) || !dense.isEmpty()) throw new IllegalStateException("dense native workload needs one fresh occupied nest");
        var level = p.serverLevel();
        var species = SpeciesRegistration.getFromResourceLocation(new ResourceLocation("complicated_bees:forest"));
        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) for (int dy = 0; dy < 3; dy++) {
            var pos = POS.offset(dx, dy, dz);
            if (pos.equals(POS)) continue;
            if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("dense native colony workload would replace foreign blocks");
            level.setBlock(pos, BlocksRegistration.BEE_NEST.get().defaultBlockState(), 3);
            ((BeeNestBlockEntity) level.getBlockEntity(pos)).setSpecies(species);
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            dense.add(pos.immutable());
        }
        p.teleportTo(level, POS.getX() + .5, 316, POS.getZ() + 20.5, 180, 5);
        return Map.of("native_occupied_nests", dense.size() + 1, "origin", "675 real native colony block entities; more than production scan limit512", "scan_limit", 512);
    }
    public static Map<String, Object> observe(ServerPlayer p) {
        var entity = p.serverLevel().getBlockEntity(POS);
        boolean occupied;
        if (entity instanceof BeeNestBlockEntity nest) occupied = nest.getSpecies() != Species.INVALID;
        else if (entity instanceof ApiaryBlockEntity apiary) {
            apiary.getLogic().checkConditions();
            occupied = apiary.getBeeItems().getStackInSlot(0).is(ItemsRegistration.QUEEN.get())
                && apiary.getLogic().isQueenSatisfied() && apiary.getOutputBuffer().isEmpty();
            if (!(entity instanceof ColonyActivity activity) || activity.betterCompat$colonyActive() != occupied)
                throw new IllegalStateException("native colony activity mixin differs from independent queen/conditions/buffer predicate");
        } else throw new IllegalStateException("native colony block entity absent");
        boolean expected = state.equals("occupied-nest") || state.equals("active-apiary");
        var result = new java.util.LinkedHashMap<String, Object>(Map.of("state", state, "ready", occupied == expected, "native_occupied", occupied,
            "native_entity", entity.getClass().getName(), "native_day", p.serverLevel().isDay(),
            "native_rain", p.serverLevel().isRainingAt(POS.above()), "native_biome", p.serverLevel().getBiome(POS).unwrapKey().orElseThrow().location().toString(),
            "position", List.of(POS.getX(), POS.getY(), POS.getZ())));
        Species species = Species.INVALID;
        boolean queenPresent = false, satisfied = false, outputEmpty = true, genomeChecked = false;
        if (entity instanceof BeeNestBlockEntity nest) species = nest.getSpecies();
        if (entity instanceof ApiaryBlockEntity apiary) {
            var queen = apiary.getBeeItems().getStackInSlot(0);
            queenPresent = queen.is(ItemsRegistration.QUEEN.get());
            satisfied = apiary.getLogic().isQueenSatisfied(); outputEmpty = apiary.getOutputBuffer().isEmpty();
            // Independent NONSHIPPING native oracle only. Never introduce this decode into production.
            if (queenPresent && queen.getTag() != null) {
                var before = queen.getTag().copy();
                species = GeneticHelper.getSpecies(queen, true);
                if (!before.equals(queen.getTag())) throw new IllegalStateException("Native appearance oracle mutated authored queen tags");
                genomeChecked = true;
            }
        }
        boolean speciesKnown = species != null && species != Species.INVALID;
        String nativeId = speciesKnown ? SpeciesRegistration.getResourceLocation(species).toString() : "invalid";
        int nativeRgb = speciesKnown ? species.getColor() : -1;
        if (speciesKnown && (nativeRgb < 0 || nativeRgb > 0xffffff)) throw new IllegalStateException("Native body color not RGB24");
        boolean cosmeticActive = entity instanceof BeeNestBlockEntity ? occupied
                : entity instanceof ColonyActivity activity && activity.betterCompat$colonyActive();
        boolean known = entity instanceof BeeNestBlockEntity ? speciesKnown
                : entity instanceof ColonyActivity activity && activity.betterCompat$colonyColorKnown();
        int cosmeticRgb = entity instanceof BeeNestBlockEntity ? (speciesKnown ? nativeRgb : 0)
                : entity instanceof ColonyActivity activity ? activity.betterCompat$colonyColor() : 0;
        var temperature = EnumTemperature.getFromPosition(p.serverLevel(), POS);
        var humidity = EnumHumidity.getFromPosition(p.serverLevel(), POS);
        result.put("requested_species_id", requestedSpecies); result.put("native_species_id", nativeId);
        result.put("native_body_rgb", nativeRgb); result.put("native_nest_rgb", speciesKnown ? species.getNestColor() : -1);
        result.put("native_queen_present", queenPresent); result.put("native_queen_satisfied", satisfied);
        result.put("native_output_empty", outputEmpty); result.put("native_genome_oracle_checked", genomeChecked); result.put("native_genome_oracle_unchanged", genomeChecked);
        result.put("native_temperature", temperature.name()); result.put("native_humidity", humidity.name());
        result.put("shared_species_climate", temperature == EnumTemperature.NORMAL && humidity == EnumHumidity.NORMAL);
        result.put("native_dandelion_present", p.serverLevel().getBlockState(POS.north()).is(Blocks.DANDELION));
        result.put("cosmetic_active", cosmeticActive); result.put("cosmetic_color_known", known); result.put("cosmetic_body_rgb", cosmeticRgb);
        result.put("appearance_ready", occupied == expected && nativeId.equals(requestedSpecies)
                && (expected ? known && cosmeticRgb == nativeRgb : !known && cosmeticRgb == 0));
        return result;
    }
}
