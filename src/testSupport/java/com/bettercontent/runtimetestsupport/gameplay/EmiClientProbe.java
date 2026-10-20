package com.bettercontent.runtimetestsupport.gameplay;

import com.bettercontent.bettercompatfixes.compat.emi.IngredientOrderSnapshot;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.screen.RecipeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.Items;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed public native calls and read-only presentation snapshots. No reflection, key/mouse
 * injection, custom transfer handler or carried/nested bag search. */
public final class EmiClientProbe {
    private static EmiRecipe transfer;
    private static boolean separateContext;
    private static EmiRecipe explicitTarget;
    private static IngredientOrderSnapshot originalSnapshot;
    private static Map<EmiRecipe, Boolean> originalAvailability;
    private EmiClientProbe() {}
    public static Map<String, Object> openPersonal(Minecraft mc) {
        if (mc.player.containerMenu != mc.player.inventoryMenu) throw new IllegalStateException("native personal open requires closed previous menu");
        mc.setScreen(new InventoryScreen(mc.player)); // Production native Curios handoff observes normal ScreenEvent.Opening.
        return Map.of("requested", "native InventoryScreen opening; production selects Curios when admitted");
    }
    public static Map<String, Object> equipmentReady(Minecraft mc, String path) {
        var inventory = CuriosApi.getCuriosInventory(mc.player).resolve().orElseThrow();
        boolean equipped = !inventory.getStacksHandler("better_backpack").orElseThrow().getStacks().getStackInSlot(0).isEmpty();
        int rings = inventory.getStacksHandler("ring").orElseThrow().getSlots();
        boolean expectedEquipped = path.equals("equipped") || path.endsWith("-equipped");
        return Map.of("ready", equipped == expectedEquipped && (path.startsWith("vanilla-") ? rings == 2 : rings == 1),
            "backpack_equipped", equipped, "native_ring_slots", rings, "origin", "native Curios capability/sync observation");
    }
    public static Map<String, Object> ready(Minecraft mc, String path) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || screen.getMenu() != mc.player.containerMenu)
            return Map.of("ready", false, "waiting", "authoritative native menu/screen");
        boolean equipped = CuriosApi.getCuriosInventory(mc.player).resolve().orElseThrow()
            .getStacksHandler("better_backpack").orElseThrow().getStacks().getStackInSlot(0).isEmpty() == false;
        if (equipped != (path.equals("equipped") || path.endsWith("-equipped"))) return Map.of("ready", false, "waiting", "native Curios changed-stack sync");
        boolean separate = screen.getMenu().getClass().getName().contains("sophisticatedbackpacks");
        if (separate != path.startsWith("separate-")) return Map.of("ready", false, "waiting", "native separate menu packet");
        if (EmiApi.getRecipeManager().getRecipesByOutput(EmiStack.of(Items.OAK_PLANKS)).isEmpty())
            return Map.of("ready", false, "waiting", "native EMI recipe manager");
        String menu = screen.getMenu().getClass().getName();
        if (path.startsWith("legacy-") && !menu.equals("top.theillusivec4.curios.common.inventory.container.CuriosContainer"))
            throw new IllegalStateException("native legacy Curios menu was not selected");
        if (path.startsWith("v2-") && !menu.equals("top.theillusivec4.curios.common.inventory.container.CuriosContainerV2"))
            throw new IllegalStateException("native Curios V2 menu was not selected");
        if (path.startsWith("vanilla-") && (!(mc.screen instanceof InventoryScreen) || screen.getMenu() != mc.player.inventoryMenu))
            throw new IllegalStateException("partial native schema did not take production vanilla fallback");
        separateContext = separate;
        return Map.of("ready", true, "screen_class", screen.getClass().getName(), "menu_class", screen.getMenu().getClass().getName(),
            "menu_id", screen.getMenu().containerId, "backpack_equipped", equipped);
    }
    public static Map<String, Object> order(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?>) && !(mc.screen instanceof RecipeScreen)) throw new IllegalStateException("native recipe browsing requires actual menu");
        var nativeRecipes = List.copyOf(EmiApi.getRecipeManager().getRecipesByOutput(EmiStack.of(Items.OAK_PLANKS)));
        var inventory = EmiPlayerInventory.of(mc.player); // Independent native quantity matcher, not the production partition's cache.
        EmiApi.displayRecipes(EmiStack.of(Items.OAK_PLANKS));
        if (!(mc.screen instanceof RecipeScreen recipes) || !(mc.screen instanceof IngredientOrderSnapshot snapshot))
            throw new IllegalStateException("native RecipeScreen/ingredient snapshot not installed");
        boolean unavailableCategorySeen = false, availableSeen = false, unavailableSeen = false;
        List<Map<String, Object>> categories = new ArrayList<>();
        transfer = null;
        originalSnapshot = snapshot; originalAvailability = new LinkedHashMap<>();
        EmiRecipe explicit = null;
        for (EmiRecipeCategory category : snapshot.betterCompat$presentedCategories()) {
            List<EmiRecipe> ordered = snapshot.betterCompat$orderedRecipes(category);
            List<EmiRecipe> expected = new ArrayList<>();
            var original = nativeRecipes.stream().filter(r -> r.getCategory() == category).toList();
            for (boolean available : List.of(true, false)) for (var recipe : original)
                if (inventory.canCraft(recipe, 1) == available) expected.add(recipe);
            if (!expected.equals(ordered)) throw new IllegalStateException("native recipe ordering is not a stable quantity-aware partition for " + category.getId());
            boolean hasAvailable = false;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var recipe : ordered) {
                boolean available = inventory.canCraft(recipe, 1);
                originalAvailability.put(recipe, available);
                if (snapshot.betterCompat$ingredientsAvailable(recipe) != available) throw new IllegalStateException("snapshot differs from native inventory quantity oracle");
                hasAvailable |= available; availableSeen |= available; unavailableSeen |= !available;
                if (!available && explicit == null) explicit = recipe;
                if (available && transfer == null && recipe.getBackingRecipe() instanceof net.minecraft.world.item.crafting.CraftingRecipe
                        && (separateContext || EmiRecipeFiller.getFirstValidHandler(recipe, recipes.old) != null)) transfer = recipe;
                rows.add(Map.of("recipe", String.valueOf(recipe.getId()), "available", available));
            }
            if (hasAvailable && unavailableCategorySeen) throw new IllegalStateException("unavailable-only category precedes ingredient-available native category");
            unavailableCategorySeen |= !hasAvailable;
            categories.add(Map.of("category", category.getId().toString(), "has_available", hasAvailable, "recipes", rows));
        }
        if (!availableSeen || !unavailableSeen || transfer == null || explicit == null) throw new IllegalStateException("native matrix lacks available/unavailable recipe and supported transfer controls");
        if (snapshot.betterCompat$categoryIndex() != 0 || snapshot.betterCompat$presentedCategories().get(0) != recipes.getFocusedCategory())
            throw new IllegalStateException("generic native display retained unrelated category focus");
        explicitTarget = explicit;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", categories); result.put("explicit_focus", String.valueOf(explicit.getId()));
        result.put("native_transfer_recipe", String.valueOf(transfer.getId())); result.put("backing_recipe", transfer.getBackingRecipe().getId().toString());
        result.put("oracle", "actual EmiPlayerInventory.canCraft(recipe,1), native RecipeScreen presented/visible state and public EmiApi focus");
        return result;
    }
    public static Map<String, Object> inputsReady(Minecraft mc) {
        if (originalAvailability == null) throw new IllegalStateException("native original ingredient snapshot absent");
        var inventory = EmiPlayerInventory.of(mc.player);
        boolean ready = originalAvailability.entrySet().stream().filter(Map.Entry::getValue).allMatch(e -> inventory.canCraft(e.getKey(), 1));
        return Map.of("ready", ready, "origin", "native inventory quantity matcher after fixture input restoration");
    }
    public static Map<String, Object> snapshotChanged(Minecraft mc, boolean reopen) {
        if (originalSnapshot == null || originalAvailability == null || !(mc.screen instanceof RecipeScreen)) throw new IllegalStateException("native inventory-change snapshot not armed");
        var inventory = EmiPlayerInventory.of(mc.player);
        long changed = originalAvailability.entrySet().stream().filter(e -> e.getValue() && !inventory.canCraft(e.getKey(), 1)).count();
        if (changed == 0) return Map.of("ready", false, "waiting", "native inventory removal synchronization");
        for (var entry : originalAvailability.entrySet())
            if (originalSnapshot.betterCompat$ingredientsAvailable(entry.getKey()) != entry.getValue())
                throw new IllegalStateException("an existing native RecipeScreen changed its ingredient snapshot after inventory sync");
        if (reopen) {
            EmiApi.displayRecipes(EmiStack.of(Items.OAK_PLANKS));
            if (!(mc.screen instanceof IngredientOrderSnapshot fresh) || fresh == originalSnapshot)
                throw new IllegalStateException("native reopen reused old ingredient snapshot");
            for (var recipe : originalAvailability.keySet())
                if (fresh.betterCompat$ingredientsAvailable(recipe) != inventory.canCraft(recipe, 1))
                    throw new IllegalStateException("native reopen did not compute a fresh quantity snapshot");
        }
        return Map.of("ready", true, "changed_native_recipes", changed, "existing_snapshot_immutable", true, "reopened_snapshot_fresh", reopen);
    }
    public static Map<String, Object> explicitFocus(Minecraft mc) {
        if (explicitTarget == null) throw new IllegalStateException("native generic ordering must be observed before explicit focus");
        // A deliberate unavailable recipe request must not be overridden by ingredient order.
        EmiApi.focusRecipe(explicitTarget);
        if (!(mc.screen instanceof RecipeScreen focused) || !(mc.screen instanceof IngredientOrderSnapshot focus)
                || focused.getFocusedCategory() != explicitTarget.getCategory() || !focus.betterCompat$visibleRecipes().contains(explicitTarget))
            throw new IllegalStateException("explicit native recipe focus was lost to ingredient sorting");
        return Map.of("explicit_focus", String.valueOf(explicitTarget.getId()), "visible", true,
            "native_page", focus.betterCompat$pageIndex(), "native_category_index", focus.betterCompat$categoryIndex());
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Map<String, Object> fill(Minecraft mc, boolean separate) {
        if (transfer == null || !(mc.screen instanceof RecipeScreen recipes)) throw new IllegalStateException("native ordering must select actual backing recipe before transfer");
        AbstractContainerScreen menu = recipes.old;
        boolean result = EmiRecipeFiller.performFill(transfer, menu, EmiCraftContext.Type.FILL_BUTTON, EmiCraftContext.Destination.NONE, 1);
        if (result == separate) throw new IllegalStateException("native transfer positive/unsupported separate-bag negative changed: " + result);
        return Map.of("native_fill_result", result, "backing_recipe", transfer.getBackingRecipe().getId().toString(),
            "screen_class", menu.getClass().getName(), "menu_class", menu.getMenu().getClass().getName(),
            "destination", "NONE native fill only; no output pickup/input synthesis", "separate_unupgraded_backpack", separate);
    }
}
