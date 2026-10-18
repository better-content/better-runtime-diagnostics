package com.bettercontent.runtimetestsupport;

import com.wdiscute.starcatcher.bob.FishingBobEntity;
import com.wdiscute.starcatcher.io.ModDataComponents;
import com.wdiscute.starcatcher.io.SingleStackContainer;
import com.wdiscute.starcatcher.rod.FishingRodMenu;
import com.wdiscute.starcatcher.storage.FishProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.LinkedHashMap;
import java.util.Map;

/** Nonshipping, run-bound caller supplies the strict request envelope. No bridge registrations here. */
public final class GameplaySpoilageProbe {
    private GameplaySpoilageProbe() {}

    public static Map<String, Object> run(ServerPlayer player, String action) {
        require(player.getServer().isSameThread(), "server thread required");
        require(java.util.Set.of("spoilage", "rod-no-backpack", "rod-equipped").contains(action), "unknown action");
        require(ModList.get().isLoaded("better_industrial_heat") && ModList.get().isLoaded("starcatcher"), "actual providers required");
        Item produce = item("better_industrial_heat:spoiled_produce");
        Item meat = item("better_industrial_heat:spoiled_meat");
        require(ComposterBlock.COMPOSTABLES.containsKey(produce) && ComposterBlock.COMPOSTABLES.getFloat(produce) == .65f,
                "production common-setup compost registration missing");
        require(ModDataComponents.getDefaults().containsKey(meat)
                && java.util.List.of(com.wdiscute.starcatcher.registry.custom.catchmodifiers.ModCatchModifiers.DECREASES_LURE_TIME.getFirst())
                .equals(ModDataComponents.get(new ItemStack(meat), ModDataComponents.CATCH_MODIFIERS)), "production basic bait defaults missing");
        require(!FishProperties.BaitRestrictions.LEGENDARY_BAIT.correctBait().contains(ForgeRegistries.ITEMS.getKey(meat))
                && !FishProperties.BaitRestrictions.LUSH_BAIT.correctBait().contains(ForgeRegistries.ITEMS.getKey(meat)), "not specialist bait");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "bc.gameplay_spoilage.v1"); out.put("action", action);
        out.put("fixture_origin", "authored_items_and_public_timing_fields_native_operations");
        out.put("produce", "better_industrial_heat:spoiled_produce"); out.put("meat", "better_industrial_heat:spoiled_meat");
        out.put("compost_chance", ComposterBlock.COMPOSTABLES.getFloat(produce));
        out.put("production_bait_defaults", true); out.put("specialist_bait", false);
        player.closeContainer();
        Item rodItem = item("starcatcher:humble_rod");
        ItemStack held = player.getMainHandItem();
        require(held.isEmpty() || held.is(rodItem), "refusing to replace nonfixture held item");
        ItemStack rod = new ItemStack(rodItem);
        player.setItemInHand(InteractionHand.MAIN_HAND, rod);
        FishingRodMenu menu = new FishingRodMenu(0, player.getInventory(), rod);
        require(menu.getSlot(37).mayPlace(new ItemStack(meat)), "native bait slot admission missing");
        menu.getSlot(37).set(new ItemStack(meat, 3)); menu.removed(player);
        require(ModDataComponents.get(rod, ModDataComponents.BAIT).stack().getCount() == 3, "native menu persistence missing");
        out.put("persisted_bait_count", 3); out.put("bait_slot", 37);
        if (action.equals("spoilage")) {
            exerciseCompost(player, produce, out);
            exerciseBobber(player, rod, out);
        } else {
            var handler = CuriosApi.getCuriosInventory(player).resolve().orElseThrow();
            var slot = handler.getStacksHandler("better_backpack").orElseThrow();
            require(slot.getStacks().getSlots() >= 1, "native backpack slot missing");
            Item backpack = item("sophisticatedbackpacks:backpack");
            ItemStack previous = slot.getStacks().getStackInSlot(0);
            require(previous.isEmpty() || previous.is(backpack), "refusing to replace nonfixture backpack");
            slot.getStacks().setStackInSlot(0, action.equals("rod-equipped") ? new ItemStack(backpack) : ItemStack.EMPTY);
            boolean equipped = !slot.getStacks().getStackInSlot(0).isEmpty();
            require(equipped == action.equals("rod-equipped"), "native equipment state mismatch");
            out.put("backpack_equipped", equipped); out.put("equipment_origin", "authored_fixture_native_curios_handler");
            out.put("equipment_slot", "better_backpack[0]");
            // Send authored held-item state before the native open packet/client constructor.
            player.inventoryMenu.broadcastChanges();
            // The native item use branch opens the real MenuProvider and sends vanilla menu packets.
            boolean shifted = player.isShiftKeyDown();
            var pose = player.getPose();
            try {
                player.setShiftKeyDown(true);
                // Native Entity.isCrouching reads pose, not the shift flag before the next tick.
                player.setPose(net.minecraft.world.entity.Pose.CROUCHING);
                rodItem.use(player.level(), player, InteractionHand.MAIN_HAND);
            } finally { player.setShiftKeyDown(shifted); player.setPose(pose); }
            require(player.containerMenu instanceof FishingRodMenu, "native rod menu did not open");
            out.put("menu_class", player.containerMenu.getClass().getName());
            out.put("menu_id", player.containerMenu.containerId);
            out.put("human_pointer_verdict", "pending_separate_human_interaction");
            player.containerMenu.broadcastChanges();
        }
        player.inventoryMenu.broadcastChanges();
        return out;
    }

    private static void exerciseCompost(ServerPlayer player, Item produce, Map<String, Object> out) {
        var level = player.serverLevel(); BlockPos pos = player.blockPosition().offset(5, 0, 5);
        var original = level.getBlockState(pos);
        require(level.getBlockEntity(pos) == null, "refusing to replace block entity");
        try {
            var state = Blocks.COMPOSTER.defaultBlockState(); level.setBlockAndUpdate(pos, state);
            var stack = new ItemStack(produce, 2);
            var filled = ComposterBlock.insertItem(null, state, level, stack, pos);
            require(stack.getCount() == 1 && filled.getValue(ComposterBlock.LEVEL) == 1, "native compost insertion failed");
            var ready = state.setValue(ComposterBlock.LEVEL, 8); level.setBlockAndUpdate(pos, ready);
            var before = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream().map(ItemEntity::getUUID).collect(java.util.stream.Collectors.toSet());
            ComposterBlock.extractProduce(null, ready, level, pos);
            require(level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
                    .anyMatch(e -> !before.contains(e.getUUID()) && e.getItem().is(Items.BONE_MEAL)), "native fertilizer output missing");
            out.put("compost_consumed", 1); out.put("compost_first_layer", 1); out.put("fertilizer", "minecraft:bone_meal");
            out.put("ready_composter_origin", "authored_level_8_fixture_not_elapsed_maturation");
        } finally { level.setBlockAndUpdate(pos, original); }
    }

    private static void exerciseBobber(ServerPlayer player, ItemStack rod, Map<String, Object> out) {
        var level = player.serverLevel(); BlockPos pos = player.blockPosition().offset(6, 0, 5);
        var lower = level.getBlockState(pos); var upper = level.getBlockState(pos.above());
        require(level.getBlockEntity(pos) == null && level.getBlockEntity(pos.above()) == null, "refusing to replace block entities");
        ItemStack emptyRod = rod.copy(); ModDataComponents.set(emptyRod, ModDataComponents.BAIT, new SingleStackContainer(ItemStack.EMPTY));
        var empty = new FishingBobEntity(level, player, emptyRod); var bob = new FishingBobEntity(level, player, rod);
        try {
            require(bob.minTicksToFish < empty.minTicksToFish && bob.maxTicksToFish < empty.maxTicksToFish, "native lure benefit missing");
            out.put("bait_min_ticks", bob.minTicksToFish); out.put("bait_max_ticks", bob.maxTicksToFish);
            out.put("empty_min_ticks", empty.minTicksToFish); out.put("empty_max_ticks", empty.maxTicksToFish);
            level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState()); level.setBlockAndUpdate(pos.above(), Blocks.WATER.defaultBlockState());
            bob.setPos(pos.getX() + .5, pos.getY() + 1.5, pos.getZ() + .5); bob.setDeltaMovement(Vec3.ZERO);
            bob.minTicksToFish = 0; bob.maxTicksToFish = 0; bob.ticksInFluid = 0; bob.chanceToFishEachTick = 1;
            bob.tick(); require(bob.ticksInFluid == 0, "native fluid contact failed");
            bob.tick(); require(bob.getEntityData().get(FishingBobEntity.STATE) == 2 && bob.ticksInFluid == 1, "native bite transition failed");
            require(ModDataComponents.get(rod, ModDataComponents.BAIT).stack().getCount() == 3, "premature bait consumption");
            bob.timeBiting = 80; bob.tick();
            require(bob.getEntityData().get(FishingBobEntity.STATE) == 3 && bob.timeBiting == 81, "native timeout transition failed");
            require(ModDataComponents.get(rod, ModDataComponents.BAIT).stack().getCount() == 2, "native consumption not exactly one");
            out.put("native_states", java.util.List.of("FLYING", "BOBBING", "BITING", "MISSED_BITE"));
            out.put("bait_before_timeout", 3); out.put("bait_after_timeout", 2); out.put("bobber_origin", "real_native_entity_public_tick_not_world_elapsed_fishing");
        } finally {
            bob.discard(); empty.discard(); level.setBlockAndUpdate(pos, lower); level.setBlockAndUpdate(pos.above(), upper);
        }
    }
    private static Item item(String id) {
        var key = new ResourceLocation(id); require(ForgeRegistries.ITEMS.containsKey(key), "missing actual item " + id);
        return ForgeRegistries.ITEMS.getValue(key);
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException("gameplay spoilage: " + message); }
}
