package com.bettercontent.runtimetestsupport.gameplay;

import com.accbdd.complicated_bees.bees.Species;
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
    private static final java.util.ArrayList<BlockPos> dense = new java.util.ArrayList<>();
    private ColonyWorldProbe() {}
    public static Map<String, Object> setup(ServerPlayer p, String kind) {
        if (!List.of("occupied-nest", "empty-nest", "active-apiary", "inactive-apiary", "clear").contains(kind))
            throw new IllegalStateException("unknown colony fixture");
        var level = p.serverLevel();
        for (var extra : dense) level.setBlock(extra, Blocks.AIR.defaultBlockState(), 3);
        dense.clear();
        if (state == null && !level.getBlockState(POS).isAir()) throw new IllegalStateException("colony fixture would replace foreign block");
        level.setBlock(POS, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(POS.north(), Blocks.AIR.defaultBlockState(), 3);
        state = kind;
        if (kind.equals("clear")) return Map.of("cleared", true);
        if (kind.endsWith("nest")) {
            level.setBlock(POS, BlocksRegistration.BEE_NEST.get().defaultBlockState(), 3);
            var nest = (BeeNestBlockEntity) level.getBlockEntity(POS);
            var species = kind.equals("occupied-nest") ? SpeciesRegistration.getFromResourceLocation(new ResourceLocation("complicated_bees:forest")) : Species.INVALID;
            if (kind.equals("occupied-nest") && species == Species.INVALID) throw new IllegalStateException("native forest species missing");
            nest.setSpecies(species); nest.setChanged();
            level.sendBlockUpdated(POS, level.getBlockState(POS), level.getBlockState(POS), 3);
        } else {
            level.setBlock(POS, BlocksRegistration.APIARY.get().defaultBlockState(), 3);
            var apiary = (ApiaryBlockEntity) level.getBlockEntity(POS);
            if (kind.equals("active-apiary")) {
                level.setBlock(POS.north(), Blocks.DANDELION.defaultBlockState(), 3);
                var species = SpeciesRegistration.getFromResourceLocation(new ResourceLocation("complicated_bees:forest"));
                if (species == Species.INVALID) throw new IllegalStateException("native forest species missing");
                apiary.getBeeItems().setStackInSlot(0, species.toStack(ItemsRegistration.QUEEN.get()));
                apiary.getLogic().setLevel(level); apiary.getLogic().rebuildFlowerCache(); apiary.getLogic().checkConditions();
            }
        }
        p.teleportTo(level, POS.getX() + .5, 316, POS.getZ() + 5.5, 180, 5);
        return observe(p);
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
        return Map.of("state", state, "ready", occupied == expected, "native_occupied", occupied,
            "native_entity", entity.getClass().getName(), "native_day", p.serverLevel().isDay(),
            "native_rain", p.serverLevel().isRainingAt(POS.above()), "native_biome", p.serverLevel().getBiome(POS).unwrapKey().orElseThrow().location().toString(),
            "position", List.of(POS.getX(), POS.getY(), POS.getZ()));
    }
}
