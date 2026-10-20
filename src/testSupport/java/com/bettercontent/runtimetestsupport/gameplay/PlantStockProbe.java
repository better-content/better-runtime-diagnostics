package com.bettercontent.runtimetestsupport.gameplay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Candidate resource -> real registered native stacks, four balanced native inventory pages.
 * Never invents an icon or tooltip and never replaces an inventory without retaining its contents. */
public final class PlantStockProbe {
    private static List<ItemStack> saved;
    private static List<JsonObject> active;
    public static Map<String, Object> run(ServerPlayer player, JsonObject input) throws Exception {
        if (input.get("action").getAsString().equals("plant-stock-finish")) {
            if (saved == null) throw new IllegalStateException("native plant stock session absent");
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, saved.get(i));
            saved = null; active = null;
            player.inventoryMenu.broadcastChanges();
            return Map.of("restored", true);
        }
        int page = input.get("page").getAsInt();
        if (page < 0 || page > 3) throw new IllegalArgumentException("native stock page outside declared four-page workload");
        if (saved == null) {
            saved = new ArrayList<>();
            for (int i = 0; i < 36; i++) saved.add(player.getInventory().getItem(i).copy());
            active = new ArrayList<>();
            var provider = ModList.get().getModFileById("better_drinking_water");
            if (provider == null) throw new IllegalStateException("selected candidate plant provider missing");
            try (var stream = java.nio.file.Files.newInputStream(provider.getFile().findResource("plant_drinks.json"))) {
                var matrix = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                if (matrix.get("version").getAsInt() != 1 || matrix.get("serving_mb").getAsInt() != 250)
                    throw new IllegalStateException("unsupported candidate plant catalogue");
                for (var element : matrix.getAsJsonArray("drinks")) {
                    var row = element.getAsJsonObject();
                    boolean available = true;
                    for (var mod : row.getAsJsonArray("mods")) available &= ModList.get().isLoaded(mod.getAsString());
                    if (!available) continue;
                    var id = new ResourceLocation(row.get("item").getAsString());
                    if (!ForgeRegistries.ITEMS.containsKey(id)) throw new IllegalStateException("active native plant stock item absent: " + id);
                    active.add(row);
                }
            }
            if (active.size() < 4 || active.size() > 144) throw new IllegalStateException("native catalogue exceeds declared four-by-36 page workload");
        }
        player.closeContainer();
        var ids = new ArrayList<String>();
        var profiles = new ArrayList<JsonObject>();
        for (int slot = 0; slot < 36; slot++) {
            int index = page + slot * 4;
            ItemStack stack = ItemStack.EMPTY;
            if (index < active.size()) {
                var row = active.get(index);
                String id = row.get("item").getAsString();
                stack = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(id)));
                profiles.add(row.deepCopy()); ids.add(id);
            } else ids.add("minecraft:air");
            player.getInventory().setItem(slot, stack);
        }
        player.inventoryMenu.broadcastChanges();
        return Map.of("page", page, "page_count", 4, "active_profiles", active.size(), "items", ids,
            "profiles", profiles, "origin", "selected-candidate catalogue and actual native registry stacks; icons require framebuffer review");
    }
}
