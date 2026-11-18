package com.bettercontent.betterruntimediagnostics;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fml.ModList;
import me.desht.pneumaticcraft.api.crafting.recipe.PressureChamberRecipe;

/** Public display API, isolated from exporters that also run without PneumaticCraft. */
final class PneumaticRecipeAdapter {
    private PneumaticRecipeAdapter() {}

    static boolean supports(Object recipe, String recipeType) {
        if (!"pneumaticcraft:pressure_chamber".equals(recipeType)
                || !ModList.get().isLoaded("pneumaticcraft")) return false;
        try {
            return Native.supports(recipe);
        } catch (LinkageError error) {
            RecipeGraphMod.LOGGER.warn("PneumaticCraft recipe adapter unavailable: {}", error.toString());
            return false;
        }
    }

    static List<Ingredient> inputs(Object recipe) {
        try {
            return Native.inputs(recipe);
        } catch (LinkageError error) {
            throw new IllegalStateException("PneumaticCraft display inputs unavailable", error);
        }
    }

    static List<List<ItemStack>> outputs(Object recipe) {
        try {
            return Native.outputs(recipe);
        } catch (LinkageError error) {
            throw new IllegalStateException("PneumaticCraft display outputs unavailable", error);
        }
    }

    static float pressure(Object recipe) {
        try {
            return Native.pressure(recipe);
        } catch (LinkageError error) {
            throw new IllegalStateException("PneumaticCraft display pressure unavailable", error);
        }
    }

    /** Loaded only for the registered provider and its exact recipe type. */
    private static final class Native {
        private Native() {}
        static boolean supports(Object recipe) {
            return recipe instanceof PressureChamberRecipe;
        }
        static List<Ingredient> inputs(Object recipe) {
            return ((PressureChamberRecipe) recipe).getInputsForDisplay();
        }
        static List<List<ItemStack>> outputs(Object recipe) {
            return ((PressureChamberRecipe) recipe).getResultsForDisplay();
        }
        static float pressure(Object recipe) {
            return ((PressureChamberRecipe) recipe).getCraftingPressureForDisplay();
        }
    }
}
