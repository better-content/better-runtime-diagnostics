package com.bettercontent.runtimetestsupport.gameplay;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native CHEST-context rolls in authored ordinary chests. This does NOT claim generated
 * structure reachability or Lootr per-player cache qualification; those are separate cases. */
public final class StructureLootProbe {
    private StructureLootProbe() {}
    public static final List<String> TABLES = List.of(
        "minecraft:chests/shipwreck_supply", "minecraft:chests/simple_dungeon",
        "rats:chest/pet_shop", "rats:chest/pet_shop_upstairs",
        "alexscaves:chests/underground_cabin_abyssal_chasm", "alexscaves:chests/underground_cabin_candy_cavity",
        "alexscaves:chests/underground_cabin_forlorn_hollows", "alexscaves:chests/underground_cabin_magnetic_caves",
        "alexscaves:chests/underground_cabin_primordial_caves", "alexscaves:chests/underground_cabin_toxic_caves",
        "wares:chests/village/desert_warehouse", "wares:chests/village/plains_warehouse",
        "wares:chests/village/savanna_warehouse", "wares:chests/village/snowy_warehouse", "wares:chests/village/taiga_warehouse",
        "fallout_wastelands_:chests/raider", "fallout_wastelands_:chests/raidertier_1", "fallout_wastelands_:chests/scraptier_1",
        "kaisyn:village/village_birch_forest_house", "kaisyn:village/village_forest_ruins/forest_ruins_butcher",
        "kaisyn:village/village_forest_ruins/forest_ruins_library", "kaisyn:village/village_forest_ruins/forest_ruins_temple",
        "wythers:chests/village/forest_ruins_big_house");

    public static Map<String, Object> run(ServerPlayer player) {
        var level = player.serverLevel();
        var pos = new BlockPos(1_000_006, 316, 1_000_000);
        if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("native loot fixture would overwrite foreign block");
        int selected = player.getInventory().selected;
        ItemStack held = player.getMainHandItem().copy();
        List<Map<String, Object>> samples = new ArrayList<>();
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.teleportTo(level, pos.getX() + .5, 316, pos.getZ() + 2.5, 180, 20);
            for (String table : TABLES) {
                var key = new ResourceLocation(table);
                if (!player.server.getLootData().getKeys(net.minecraft.world.level.storage.loot.LootDataType.TABLE).contains(key))
                    throw new IllegalStateException("selected native loot table missing: " + key);
                for (int seed = 1; seed <= 16; seed++) {
                    level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
                    var chest = (ChestBlockEntity) level.getBlockEntity(pos);
                    chest.setLootTable(key, seed);
                    var action = player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false));
                    if (player.containerMenu == player.inventoryMenu || !action.consumesAction())
                        throw new IllegalStateException("native chest interaction did not open selected table " + key);
                    List<Map<String, Object>> stacks = new ArrayList<>();
                    int total = 0;
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        var stack = chest.getItem(slot);
                        if (stack.isEmpty()) continue;
                        String id = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                        if ((stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION))
                                && PotionUtils.getPotion(stack) != Potions.WATER)
                            throw new IllegalStateException("selected chest leaked nonwater potion " + key + ": " + stack);
                        if (id.endsWith("_seeds") || id.equals("minecraft:beetroot_seeds") || id.equals("minecraft:wheat_seeds"))
                            throw new IllegalStateException("selected chest leaked seed progression " + key + ": " + id);
                        total += stack.getCount();
                        stacks.add(Map.of("slot", slot, "item", id, "count", stack.getCount(), "nbt", stack.save(new CompoundTag()).toString()));
                    }
                    if (total == 0) throw new IllegalStateException("selected native chest unexpectedly empty: " + key + " seed=" + seed);
                    CompoundTag first = chest.saveWithFullMetadata();
                    if (first.contains("LootTable")) throw new IllegalStateException("native first open failed to unpack table");
                    player.closeContainer();
                    player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false));
                    if (!first.equals(chest.saveWithFullMetadata())) throw new IllegalStateException("ordinary native chest rerolled on second open");
                    player.closeContainer();
                    samples.add(Map.of("table", table, "seed", seed, "items", stacks, "native_second_open_unchanged", true));
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            // Empty contexts must stay empty after the actual global modifier pipeline.
            for (String table : List.of("minecraft:empty", "the_bumblezone:structures/sempiternal_sanctum/sudoku_failed")) {
                level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
                var chest = (ChestBlockEntity) level.getBlockEntity(pos);
                chest.setLootTable(new ResourceLocation(table), 41);
                player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false));
                for (int slot = 0; slot < chest.getContainerSize(); slot++)
                    if (!chest.getItem(slot).isEmpty()) throw new IllegalStateException("negative loot table gained filler: " + table);
                player.closeContainer(); level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        } finally {
            player.closeContainer(); level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            player.getInventory().selected = selected; player.setItemInHand(InteractionHand.MAIN_HAND, held);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables", TABLES); result.put("samples", samples); result.put("seeds_per_table", 16);
        result.put("context", "native ordinary CHEST opened through ServerPlayerGameMode.useItemOn");
        result.put("negative_empty_tables", List.of("minecraft:empty", "the_bumblezone:structures/sempiternal_sanctum/sudoku_failed"));
        result.put("limitations", "Authored ordinary chests; not generated structure reachability, Lootr per-player caching, exhaustive RNG or complete progression-forbidden item qualification.");
        return result;
    }
}
