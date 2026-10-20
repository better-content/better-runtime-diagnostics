package com.bettercontent.runtimetestsupport.gameplay;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Native equipment/items/menu operations only. No custom storage, root repair, fabricated
 * upgrades, custom transfer packets or synthetic input. Separate new bag has NO crafting upgrade. */
public final class EmiWorldProbe {
    private static UUID owner;
    private static List<ItemStack> inventory;
    private static ItemStack backpack;
    private static String path;
    private static final UUID FALLBACK_SCHEMA = UUID.fromString("7b41142d-581b-4d9b-a3bc-0199257f5fe1");
    private static boolean legacyMenu;
    private EmiWorldProbe() {}
    public static Map<String, Object> prepare(ServerPlayer p, String kind) {
        if (!List.of("no-backpack", "equipped", "legacy-no-backpack", "legacy-equipped", "v2-no-backpack", "v2-equipped", "vanilla-no-backpack", "vanilla-equipped", "separate-backpack", "separate-upgraded", "restore").contains(kind)) throw new IllegalStateException("unknown EMI fixture path");
        var slots = CuriosApi.getCuriosInventory(p).resolve().orElseThrow().getStacksHandler("better_backpack").orElseThrow().getStacks();
        if (owner == null) {
            owner = p.getUUID(); inventory = new ArrayList<>();
            legacyMenu = top.theillusivec4.curios.common.CuriosConfig.SERVER.enableLegacyMenu.get();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            backpack = slots.getStackInSlot(0).copy();
        }
        if (!owner.equals(p.getUUID())) throw new IllegalStateException("EMI fixture owner changed");
        p.closeContainer();
        var ring = CuriosApi.getCuriosInventory(p).resolve().orElseThrow().getStacksHandler("ring").orElseThrow();
        ring.removeModifier(FALLBACK_SCHEMA); ring.update();
        top.theillusivec4.curios.common.CuriosConfig.SERVER.enableLegacyMenu.set(legacyMenu);
        // Restore only this explicitly snapshotted disposable fixture between native paths.
        for (int i = 0; i < inventory.size(); i++) p.getInventory().setItem(i, inventory.get(i).copy());
        if (!slots.getStackInSlot(0).isEmpty()) slots.extractItem(0, slots.getStackInSlot(0).getCount(), false);
        if (kind.equals("restore")) {
            if (!backpack.isEmpty() && !slots.insertItem(0, backpack.copy(), false).isEmpty()) throw new IllegalStateException("native original backpack restore rejected");
            owner = null; inventory = null; backpack = null; path = null;
            p.inventoryMenu.broadcastChanges(); return Map.of("restored", true);
        }
        path = kind;
        if (kind.startsWith("legacy-")) top.theillusivec4.curios.common.CuriosConfig.SERVER.enableLegacyMenu.set(true);
        if (kind.startsWith("v2-")) top.theillusivec4.curios.common.CuriosConfig.SERVER.enableLegacyMenu.set(false);
        if (kind.startsWith("vanilla-")) {
            // A real native temporary +1 schema modifier makes Journal's readiness guard
            // fall back without deleting slots/items or suppressing network responses.
            ring.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(FALLBACK_SCHEMA,
                "native fixture partial-schema fallback", 1, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION));
            ring.update();
        }
        if (!p.getInventory().add(new ItemStack(Items.OAK_LOG, 16)) || !p.getInventory().add(new ItemStack(Items.OAK_PLANKS, 16)))
            throw new IllegalStateException("native fixture lacks space for declared recipe inputs");
        var bagItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation("sophisticatedbackpacks:backpack"));
        if (bagItem == null || bagItem == Items.AIR) throw new IllegalStateException("native backpack item missing");
        if (kind.equals("equipped") || kind.endsWith("-equipped")) {
            var held = p.getMainHandItem();
            var equippedBag = new ItemStack(bagItem);
            p.setItemInHand(InteractionHand.MAIN_HAND, equippedBag);
            bagItem.use(p.level(), p, InteractionHand.MAIN_HAND); // Native initialization owns UUID/storage; never repair root NBT.
            if (!p.containerMenu.getClass().getName().contains("sophisticatedbackpacks")) throw new IllegalStateException("native equipped bag initialization did not open");
            p.closeContainer();
            var wrapper = equippedBag.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance()).resolve().orElseThrow();
            if (!wrapper.getInventoryHandler().insertItem(0, new ItemStack(Items.COPPER_INGOT, 7), false).isEmpty()) throw new IllegalStateException("native equipped bag payload insertion rejected");
            p.setItemInHand(InteractionHand.MAIN_HAND, held);
            if (!slots.insertItem(0, equippedBag, false).isEmpty()) throw new IllegalStateException("native Curios insertion rejected");
        }
        if (kind.startsWith("separate-")) {
            // Native Item.use owns initialization, menu and packets. No root/upgrade NBT authored.
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(bagItem));
            bagItem.use(p.level(), p, InteractionHand.MAIN_HAND);
            if (!p.containerMenu.getClass().getName().contains("sophisticatedbackpacks")) throw new IllegalStateException("native separate backpack did not open");
            if (kind.equals("separate-upgraded")) {
                p.closeContainer();
                var wrapper = p.getMainHandItem().getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance()).resolve().orElseThrow();
                var upgrade = ForgeRegistries.ITEMS.getValue(new ResourceLocation("sophisticatedbackpacks:crafting_upgrade"));
                if (upgrade == null || upgrade == Items.AIR || !wrapper.getUpgradeHandler().insertItem(0, new ItemStack(upgrade), false).isEmpty()
                        || !wrapper.getUpgradeHandler().getStackInSlot(0).is(upgrade)) throw new IllegalStateException("native crafting upgrade admission/payment failed");
                wrapper.setOpenTabId(0); // Native saved tab control, after REAL upgrade insertion.
                bagItem.use(p.level(), p, InteractionHand.MAIN_HAND);
                if (!(p.containerMenu instanceof net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase<?> storage)
                        || storage.getOpenOrFirstCraftingContainer(RecipeType.CRAFTING).isEmpty()) throw new IllegalStateException("installed native upgrade produced no independent crafting container");
            }
        }
        p.inventoryMenu.broadcastChanges(); p.containerMenu.broadcastChanges();
        return Map.of("path", kind, "backpack_equipped", !slots.getStackInSlot(0).isEmpty(),
            "server_menu", p.containerMenu.getClass().getName(), "menu_id", p.containerMenu.containerId,
            "fixture", "native inventory snapshot/grants, Curios insertion and real native upgrade-handler insertion only; no root/upgrade NBT authored");
    }
    public static Map<String, Object> inputs(ServerPlayer p, boolean full) {
        if (owner == null || !owner.equals(p.getUUID())) throw new IllegalStateException("EMI input-change fixture not armed");
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            var stack = p.getInventory().getItem(i);
            if (stack.is(Items.OAK_LOG) || stack.is(Items.OAK_PLANKS)) p.getInventory().removeItem(i, stack.getCount());
        }
        if (full && (!p.getInventory().add(new ItemStack(Items.OAK_LOG, 16)) || !p.getInventory().add(new ItemStack(Items.OAK_PLANKS, 16))))
            throw new IllegalStateException("declared native fixture inputs cannot be restored");
        p.inventoryMenu.broadcastChanges(); p.containerMenu.broadcastChanges();
        return Map.of("inputs", full ? "restored" : "removed", "origin", "temporary native inventory fixture; original inventory restored at family finish");
    }
    public static Map<String, Object> verifyTransfer(ServerPlayer p, String recipeId) {
        if (owner == null || !owner.equals(p.getUUID())) throw new IllegalStateException("EMI fixture not armed");
        CraftingContainer matrix = null; ResultContainer output = null;
        var nativeSlots = new ArrayList<>(p.containerMenu.slots);
        if (p.containerMenu instanceof net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase<?> storage) {
            var upgrade = storage.getOpenOrFirstCraftingContainer(RecipeType.CRAFTING);
            if (upgrade.isPresent()) {
                if (upgrade.get().getCraftMatrix() instanceof CraftingContainer c) matrix = c;
                nativeSlots.addAll(upgrade.get().getSlots());
            }
        }
        for (var slot : nativeSlots) {
            if (slot.container instanceof CraftingContainer c) matrix = c;
            if (slot.container instanceof ResultContainer r) output = r;
        }
        if (path.equals("separate-backpack")) {
            if (matrix != null || output != null) throw new IllegalStateException("unupgraded separate native backpack acquired personal crafting");
            return Map.of("ready", true, "path", path, "native_transfer", "correctly_unsupported_no_crafting_upgrade", "menu_class", p.containerMenu.getClass().getName());
        }
        if (matrix == null || output == null) throw new IllegalStateException("native matrix/result container missing");
        int width = path.equals("separate-upgraded") ? 3 : 2;
        if (matrix.getWidth() != width || matrix.getHeight() != width) throw new IllegalStateException("personal/upgrade native matrix dimensions crossed ownership boundary");
        var recipe = p.serverLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, matrix, p.serverLevel());
        if (recipe.isEmpty() || output.getItem(0).isEmpty()) return Map.of("ready", false, "waiting", "native server transfer/result packet");
        if (!recipe.get().getId().equals(new ResourceLocation(recipeId))) throw new IllegalStateException("native recipe selection differs from EMI backing recipe");
        var expected = recipe.get().assemble(matrix, p.serverLevel().registryAccess());
        var actual = output.getItem(0);
        if (!ItemStack.isSameItemSameTags(expected, actual) || expected.getCount() != actual.getCount()) throw new IllegalStateException("native crafting output differs from actual server recipe");
        return Map.of("ready", true, "path", path, "native_recipe", recipeId, "matrix_width", matrix.getWidth(),
            "matrix_height", matrix.getHeight(), "result", ForgeRegistries.ITEMS.getKey(actual.getItem()).toString(),
            "result_count", actual.getCount(), "menu_class", p.containerMenu.getClass().getName());
    }
}
