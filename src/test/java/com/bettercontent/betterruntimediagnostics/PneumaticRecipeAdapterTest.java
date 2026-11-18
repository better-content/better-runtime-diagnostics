package com.bettercontent.betterruntimediagnostics;

import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import me.desht.pneumaticcraft.api.crafting.recipe.PressureChamberRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.items.IItemHandler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Typed provider contract; production recipe corpus/absence checks remain a separate runtime gate. */
class PneumaticRecipeAdapterTest {
    @Test void displayAccessorsPreserveAlternativesAndNeverExecuteCrafting() {
        DisplayRecipe recipe = new DisplayRecipe();
        assertFalse(PneumaticRecipeAdapter.supports(recipe, "minecraft:crafting"));
        assertSame(recipe.ingredients, PneumaticRecipeAdapter.inputs(recipe));
        assertSame(recipe.outputs, PneumaticRecipeAdapter.outputs(recipe));
        assertEquals(2, PneumaticRecipeAdapter.outputs(recipe).size());
        assertEquals(2.5f, PneumaticRecipeAdapter.pressure(recipe));
    }

    private static final class DisplayRecipe extends PressureChamberRecipe {
        private final List<Ingredient> ingredients = List.of(Ingredient.EMPTY, Ingredient.EMPTY);
        private final List<List<ItemStack>> outputs = List.of(List.of(), List.of());
        private DisplayRecipe() { super(new ResourceLocation("better_runtime_diagnostics", "display_contract")); }
        @Override public void write(FriendlyByteBuf buf) { throw new AssertionError("Display must not serialize"); }
        @Override public float getCraftingPressure(IItemHandler handler, IntList slots) {
            throw new AssertionError("Display must not craft");
        }
        @Override public float getCraftingPressureForDisplay() { return 2.5f; }
        @Override public IntCollection findIngredients(IItemHandler handler) {
            throw new AssertionError("Display must not consume inputs");
        }
        @Override public List<Ingredient> getInputsForDisplay() { return ingredients; }
        @Override public List<List<ItemStack>> getResultsForDisplay() { return outputs; }
        @Override public boolean isValidInputItem(ItemStack stack) { return false; }
        @Override public NonNullList<ItemStack> craftRecipe(IItemHandler handler, IntList slots, boolean simulate) {
            throw new AssertionError("Display must not craft");
        }
        @Override public NonNullList<Ingredient> getIngredients() { return NonNullList.create(); }
        @Override public RecipeType<?> getType() { return null; }
        @Override public RecipeSerializer<?> getSerializer() { return null; }
    }
}
