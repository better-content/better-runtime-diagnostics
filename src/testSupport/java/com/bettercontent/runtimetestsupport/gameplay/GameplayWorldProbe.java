package com.bettercontent.runtimetestsupport.gameplay;

import com.bettercontent.betterwildfruittrees.WildBushes;
import com.bettercontent.betterwildfruittrees.WildBushField;
import com.bettercontent.bettersurvivalphysiology.nutrition.DietBridge;
import com.bettercontent.bettersurvivalphysiology.metabolism.MetabolicStateStore;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded, nonce-bound authored world setup. It never changes world time or campaign history.
 * Native feature/harvest/growth calls are labelled authored fixture scheduling, not natural elapsed growth. */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support")
public final class GameplayWorldProbe {
    private static final int CORE = 1_000_000;
    private static final String[] PALETTE = {"sacred", "wicked", "arcane", "aerial", "aqueous", "earthen", "infernal", "tempo"};
    private static final String[] FEATURES = {"minecraft:patch_berry_bush", "fruitsdelight:blueberry_bush", "fruitsdelight:cranberry_bush", "ars_nouveau:patch_berry"};
    private static final List<BlockPos> bushes = new ArrayList<>();
    private static int sequence;
    private GameplayWorldProbe() {}
    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent event) throws Exception {
        if (!Boolean.getBoolean("bc.pack_test.gameplay_world") || event.phase != TickEvent.Phase.END
                || !(event.player instanceof ServerPlayer player)) return;
        String run = System.getProperty("bc.pack_test.diagnostics.run_id", "");
        String nonce = System.getProperty("bc.pack_test.gameplay_world.nonce", "");
        if (!"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode")) || !run.matches("[A-Za-z0-9_.-]{1,128}")
                || !UUID.fromString(nonce).toString().equals(nonce)) throw new IllegalStateException("invalid gameplay world binding");
        Path root = Path.of(System.getProperty("bc.pack_test.diagnostics.dir")).toAbsolutePath().normalize();
        Path request = root.resolve("gameplay-world.request");
        if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)) return;
        for (Path p = request; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IllegalStateException("symlink gameplay world request");
        if (!Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS) || Files.size(request) > 4096) throw new IllegalStateException("invalid gameplay world request");
        JsonObject input = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
        if (!player.getUUID().toString().equals(input.get("player").getAsString())) return;
        int next = input.get("sequence").getAsInt();
        String label = input.get("label").getAsString();
        if (!"bc.gameplay_world_request.v1".equals(input.get("schema").getAsString()) || !run.equals(input.get("run").getAsString())
                || !nonce.equals(input.get("nonce").getAsString())
                || !System.getProperty("bc.pack_test.diagnostics.client_sha256", "").equals(input.get("client_sha256").getAsString())
                || !System.getProperty("bc.pack_test.diagnostics.server_sha256", "").equals(input.get("server_sha256").getAsString()) || next <= sequence || !label.matches("[A-Za-z0-9_.-]{1,80}")
                || !player.serverLevel().dimension().equals(Level.OVERWORLD) || !player.isAlive()
                || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL
                || Math.abs(player.getX() - CORE) > 128 || Math.abs(player.getZ() - CORE) > 128 || Math.abs(player.getY() - 316) > 32)
            throw new IllegalStateException("gameplay world request identity/scope/replay mismatch");
        String stem = "gameplay-world-" + next + "-" + label;
        Files.move(request, root.resolve(stem + ".consumed")); sequence = next;
        String action = input.get("action").getAsString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "bc.gameplay_world_observation.v1"); result.put("run", run); result.put("nonce", nonce);
        Map<String, String> binding = new LinkedHashMap<>();
        for (String kind : new String[]{"client", "server", "runtime", "support"}) {
            String hash = System.getProperty("bc.pack_test.diagnostics." + kind + "_sha256", "");
            if (!hash.matches("[a-f0-9]{64}")) throw new IllegalStateException("missing gameplay world candidate/support binding");
            binding.put(kind + "_sha256", hash);
        }
        result.put("candidate_binding", binding);
        result.put("sequence", next); result.put("label", label); result.put("action", action); result.put("player", player.getUUID().toString());
        result.put("world_root", player.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString());
        result.put("world_seed", player.serverLevel().getSeed()); result.put("server_process", ProcessHandle.current().pid());
        result.put("server_tick", player.server.getTickCount()); result.put("game_time", player.serverLevel().getGameTime()); result.put("day_time", player.serverLevel().getDayTime());
        result.put("native_day", player.serverLevel().isDay()); result.put("native_night", player.serverLevel().isNight());
        result.put("origin", "authored_fixture_using_public_native_apis");
        switch (action) {
            case "empty-hand" -> {
                int empty = -1;
                for (int slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).isEmpty()) { empty = slot; break; }
                if (empty < 0) throw new IllegalStateException("fixture needs empty hotbar slot; no foreign item is discarded");
                player.getInventory().selected = empty;
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(empty));
                result.put("authored_selected_empty_slot", empty);
            }
            case "campfires" -> campfires(player, result);
            case "campfire-view" -> campfireView(player, input.get("index").getAsInt(), input.has("signal") && input.get("signal").getAsBoolean(), result);
            case "field" -> field(player, input.get("index").getAsInt(), result);
            case "bush-harvest" -> harvest(player, result);
            case "bush-regrow" -> regrow(player, result);
            case "readiness-low", "readiness-high", "readiness-observe" -> readiness(player, action, result);
            case "chopping-log", "chopping-dt" -> chopping(player, action.equals("chopping-dt"), result);
            case "spoilage", "rod-no-backpack", "rod-equipped" -> result.put("native_probe", com.bettercontent.runtimetestsupport.GameplaySpoilageProbe.run(player, action));
            case "trader-funds0", "trader-funds9", "trader-funds10", "trader-arm", "trader-observe", "trader-complete", "trader-resume" -> result.put("native_probe", com.bettercontent.runtimetestsupport.GameplayTraderProbe.run(player, action));
            case "core" -> player.teleportTo(player.serverLevel(), CORE + .5, 316, CORE + .5, 180, 0);
            default -> throw new IllegalStateException("unknown gameplay world action " + action);
        }
        player.containerMenu.broadcastChanges(); player.inventoryMenu.broadcastChanges();
        Path pending = root.resolve(stem + ".output-pending");
        Files.writeString(pending, new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardOpenOption.CREATE_NEW);
        Files.move(pending, root.resolve(stem + ".json"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    private static void campfires(ServerPlayer player, Map<String, Object> result) {
        if (!ModList.get().isLoaded("adchimneys") || !ModList.get().isLoaded("weather2")) throw new IllegalStateException("actual AdvancedChimneys and Weather2 required");
        var level = player.serverLevel();
        List<Map<String, Object>> states = new ArrayList<>();
        for (int i = 0; i < PALETTE.length; i++) for (int signal = 0; signal <= 1; signal++) {
            BlockPos pos = new BlockPos(CORE - 14 + i * 4, 316, CORE - 12 - signal * 4);
            var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("better_spirit_commerce", PALETTE[i] + "_campfire"));
            if (!(block instanceof CampfireBlock)) throw new IllegalStateException("missing native palette campfire " + PALETTE[i]);
            level.setBlock(pos.below(), (signal == 1 ? Blocks.HAY_BLOCK : Blocks.GRASS_BLOCK).defaultBlockState(), 3);
            BlockState state = block.defaultBlockState().setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, signal == 1);
            level.setBlock(pos, state, 3);
            states.add(Map.of("palette", PALETTE[i], "signal", signal == 1, "position", List.of(pos.getX(), pos.getY(), pos.getZ()), "state", level.getBlockState(pos).toString()));
        }
        player.teleportTo(level, CORE + .5, 316, CORE + 18.5, 180, 5);
        result.put("campfires", states); result.put("day_night_origin", "unmodified_native_world_clock");
        result.put("smoke_verdict", "requires actual framebuffer review, not presence of particle event alone");
    }
    private static void campfireView(ServerPlayer player, int index, boolean signalFocus, Map<String, Object> result) {
        if (index < 0 || index >= PALETTE.length) throw new IllegalStateException("palette view index out of range");
        var level = player.serverLevel(); int x = CORE - 14 + index * 4;
        List<Map<String, Object>> pair = new ArrayList<>();
        for (int signal = 0; signal <= 1; signal++) {
            BlockPos pos = new BlockPos(x, 316, CORE - 12 - signal * 4);
            var state = level.getBlockState(pos);
            if (!new ResourceLocation("better_spirit_commerce", PALETTE[index] + "_campfire").equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()))
                    || !state.getValue(CampfireBlock.LIT) || state.getValue(CampfireBlock.SIGNAL_FIRE) != (signal == 1))
                throw new IllegalStateException("native palette scene changed before capture " + pos);
            pair.add(Map.of("position", List.of(pos.getX(), pos.getY(), pos.getZ()), "state", state.toString()));
        }
        int focusZ = CORE - 12 - (signalFocus ? 4 : 0);
        // Oblique native view avoids front-row normal fire occluding its signal variant.
        player.teleportTo(level, x + 2.5, 316, focusZ + 4.5, 153.435f, 14);
        result.put("signal_focus", signalFocus);
        result.put("palette", PALETTE[index]); result.put("normal_and_signal", pair);
        result.put("camera_origin", "authored_native_camera_fixture_no_pointer_input");
    }
    private static void field(ServerPlayer player, int index, Map<String, Object> result) {
        if (index < 0 || index >= FEATURES.length) throw new IllegalStateException("field index out of range");
        var level = player.serverLevel(); var origin = new BlockPos(CORE + 48, 316, CORE);
        bushes.clear();
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            for (int y = 0; y <= 3; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        var feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).get(new ResourceLocation(FEATURES[index]));
        if (feature == null || !(feature.feature() instanceof WildBushField)) throw new IllegalStateException("candidate missing configured WildBushField " + FEATURES[index]);
        if (!feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(112), origin)) throw new IllegalStateException("native configured field failed");
        List<Map<String, Object>> shapes = new ArrayList<>();
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) {
            BlockPos pos = origin.offset(x, 0, z); var state = level.getBlockState(pos);
            if (!WildBushes.isBush(state)) continue;
            bushes.add(pos);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if ((dx != 0 || dz != 0) && WildBushes.isBush(level.getBlockState(pos.offset(dx, 0, dz)))) throw new IllegalStateException("native field became dense hedge");
            shapes.add(Map.of("position", List.of(pos.getX(), pos.getY(), pos.getZ()), "state", state.toString(), "shape", state.getShape(level, pos).toAabbs().toString()));
        }
        if (bushes.size() < 10 || bushes.size() > 14) throw new IllegalStateException("native field aggregate count " + bushes.size());
        int spanX = bushes.stream().mapToInt(BlockPos::getX).max().orElseThrow() - bushes.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int spanZ = bushes.stream().mapToInt(BlockPos::getZ).max().orElseThrow() - bushes.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        if (spanX < 8 || spanZ < 8) throw new IllegalStateException("native bush field too compact");
        result.put("configured_feature", FEATURES[index]); result.put("fixture_random_seed", 112); result.put("bushes", shapes); result.put("span_x", spanX); result.put("span_z", spanZ);
        // Authored camera pedestal is outside the field footprint, not fabricated bush geometry.
        BlockPos pedestal = origin.offset(0, 2, 17); level.setBlock(pedestal, Blocks.GLASS.defaultBlockState(), 3);
        player.teleportTo(level, pedestal.getX() + .5, pedestal.getY() + 1, pedestal.getZ() + .5, 180, 12);
    }
    private static void harvest(ServerPlayer player, Map<String, Object> result) {
        if (bushes.isEmpty()) throw new IllegalStateException("harvest requires same-process native field");
        var level = player.serverLevel(); var pos = bushes.get(0); var state = WildBushes.mature(level.getBlockState(pos));
        level.setBlock(pos, state, 3);
        level.setBlock(pos.above(2), Blocks.LIGHT.defaultBlockState(), 3); // authored invisible growth light, no clock override
        result.put("growth_light_origin", "authored_fixture_native_light_block_y318");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.teleportTo(level, pos.getX() + .5, pos.getY(), pos.getZ() + 2.5, 180, 26);
        var area = new AABB(pos).inflate(2); int before = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).stream().mapToInt(e -> e.getItem().getCount()).sum();
        var interaction = player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        var after = level.getBlockState(pos);
        int dropped = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).stream().mapToInt(e -> e.getItem().getCount()).sum() - before;
        if (after.equals(state) || !after.is(state.getBlock()) || dropped < 1 || dropped > 3) throw new IllegalStateException("native bush harvest failed/yield " + dropped);
        result.put("native_interaction", interaction.toString()); result.put("before", state.toString()); result.put("after", after.toString()); result.put("native_dropped_count", dropped);
        result.put("shape_before", state.getShape(level, pos).toAabbs().toString()); result.put("shape_after", after.getShape(level, pos).toAabbs().toString());
        if (!state.getShape(level, pos).toAabbs().equals(after.getShape(level, pos).toAabbs())) throw new IllegalStateException("harvest changed bush geometry");
    }
    private static void regrow(ServerPlayer player, Map<String, Object> result) {
        if (bushes.isEmpty()) throw new IllegalStateException("regrowth requires same-process native field");
        var level = player.serverLevel(); var pos = bushes.get(0); var before = level.getBlockState(pos); var mature = WildBushes.mature(before);
        int calls = 0;
        for (; calls < 300 && !level.getBlockState(pos).equals(mature); calls++)
            before.getBlock().randomTick(level.getBlockState(pos), level, pos, RandomSource.create(calls));
        if (!level.getBlockState(pos).equals(mature)) throw new IllegalStateException("native random fruit ripening failed");
        result.put("before", before.toString()); result.put("after", level.getBlockState(pos).toString()); result.put("authored_native_random_tick_calls", calls);
        result.put("growth_verdict", "native randomTick path under authored scheduling; not elapsed natural regrowth");
        // Exercise native bonemeal path separately from the random-tick oracle.
        level.setBlock(pos, before, 3);
        if (!(before.getBlock() instanceof BonemealableBlock grower)) throw new IllegalStateException("missing native bonemeal path");
        int meals = 0;
        for (; meals < 5 && !level.getBlockState(pos).equals(mature); meals++) grower.performBonemeal(level, level.random, pos, level.getBlockState(pos));
        if (!level.getBlockState(pos).equals(mature)) throw new IllegalStateException("native bonemeal regrowth failed");
        result.put("authored_native_bonemeal_calls", meals);
    }
    private static void readiness(ServerPlayer player, String action, Map<String, Object> result) {
        var diet = DietBridge.tracker(player).orElseThrow(() -> new IllegalStateException("actual Diet capability required"));
        result.put("before_proteins", diet.getValue("proteins"));
        if (!action.equals("readiness-observe")) {
            // Author only Diet's public persistent nutrition fixture, never the production edge detector or clock.
            for (String group : new String[]{"proteins", "grains", "fruits", "fats", "vegetables", "dairy"})
                diet.setValue(group, group.equals("proteins") && action.equals("readiness-low") ? 0f : 1f);
            diet.sync();
        }
        var state = MetabolicStateStore.get(player);
        result.put("after_proteins", diet.getValue("proteins")); result.put("cooldown", state.heavyBlowCooldown);
        result.put("last_attack_tick", state.lastAttackTick); result.put("attack_strength", player.getAttackStrengthScale(.5f));
        result.put("presentation_flags", state.lastPresentationFlags); result.put("blackout", state.blackout);
        result.put("feedback_origin", "production native PlayerTick only; fixture does not invoke activation or feedback update");
    }
    private static void chopping(ServerPlayer player, boolean dynamic, Map<String, Object> result) {
        var level = player.serverLevel(); var pos = new BlockPos(CORE, 316, CORE + 20);
        level.setBlock(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        if (dynamic) {
            var species = Species.REGISTRY.get(new ResourceLocation("dynamictrees:apple_oak"));
            if (!species.isValid()) throw new IllegalStateException("actual DynamicTrees apple_oak missing");
            species.placeRootyDirtBlock(level, pos.below(), 15);
            species.getFamily().getBranch().orElseThrow().setRadius(level, pos, 4, Direction.DOWN, 3);
        } else level.setBlock(pos, Blocks.OAK_LOG.defaultBlockState(), 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(dynamic ? Items.DIAMOND_AXE : Items.WOODEN_AXE));
        player.teleportTo(level, pos.getX() + .5, 316, pos.getZ() + 3.5, 180, 20.5f);
        result.put("target", List.of(pos.getX(), pos.getY(), pos.getZ())); result.put("state", level.getBlockState(pos).toString());
        result.put("shape", level.getBlockState(pos).getShape(level, pos).toAabbs().toString());
        result.put("cut_origin", "authored native block fixture; actual cut must be driven by client native gameMode");
    }
}
