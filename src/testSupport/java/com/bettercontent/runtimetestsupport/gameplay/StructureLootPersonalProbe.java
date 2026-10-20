package com.bettercontent.runtimetestsupport.gameplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import noobanidus.mods.lootr.api.LootrAPI;
import noobanidus.mods.lootr.api.inventory.ILootrInventory;
import noobanidus.mods.lootr.block.entities.LootrChestBlockEntity;
import noobanidus.mods.lootr.data.DataStorage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Nonshipping, explicitly dispatched native fixture. No startup/reload work.
 * Uses two actual connected players and actual Lootr block/menu/cache classes.
 * Registry/data reachability, authored Lootr fixtures and naturally generated
 * structure observations are separate evidence fields, never interchangeable.
 */
public final class StructureLootPersonalProbe {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final BlockPos FIXTURE = new BlockPos(1_000_010, 316, 1_000_000);
    private static final Pattern TEMPLATE = Pattern.compile("!data/([^/]+)/structures/(.+)\\.nbt:");
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private StructureLootPersonalProbe() {}

    public static Map<String, Object> run(ServerPlayer owner, JsonObject request) throws Exception {
        Path diagnostics = Path.of(System.getProperty("bc.pack_test.diagnostics.dir")).toAbsolutePath().normalize();
        Path reviewPath = diagnostics.resolve("structure-loot-review.json");
        for (Path p = reviewPath; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IllegalStateException("symlink loot review fixture");
        String expected = request.has("review_sha256") ? request.get("review_sha256").getAsString() : "";
        if (!expected.matches("[a-f0-9]{64}") || !Files.isRegularFile(reviewPath, LinkOption.NOFOLLOW_LINKS)
                || Files.size(reviewPath) > 2_000_000) throw new IllegalStateException("unbound loot review fixture");
        byte[] bytes = Files.readAllBytes(reviewPath);
        if (!sha256(bytes).equals(expected)) throw new IllegalStateException("loot review fixture digest changed");
        JsonObject review = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!"bc.structure_loot_review.v1".equals(review.get("schema").getAsString())) throw new IllegalStateException("wrong loot review schema");
        Policy policy = Policy.read(owner, review);
        var level = owner.serverLevel();
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) throw new IllegalStateException("loot fixture requires Overworld");
        List<ServerPlayer> players = owner.server.getPlayerList().getPlayers().stream()
                .filter(p -> p != owner && live(p) && !p.getUUID().equals(owner.getUUID())
                        && p.getGameProfile().getName().equals("SmokeClient2")).toList();
        if (!live(owner) || players.isEmpty()) throw new IllegalStateException("Lootr requires TWO distinct real connected Survival players");
        ServerPlayer second = players.get(0);
        List<ServerPlayer> pair = List.of(owner, second);
        if (pair.stream().anyMatch(p -> p.getLuck() != 0f)) throw new IllegalStateException("declared zero-luck native loot fixture has nonzero player luck");
        List<PlayerState> states = pair.stream().map(PlayerState::new).toList();
        List<Map<String, Object>> samples = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("review_sha256", expected);
        result.put("loot_provider", "lootr-forge-1.20-0.7.35.94.jar");
        result.put("loot_provider_pin_sha1", "1ec77a7df8adbf3290a296bf25b8f99f37a60020");
        result.put("players", pair.stream().map(p -> p.getUUID().toString()).toList());
        result.put("policy_inputs", review.get("policy_sha256"));
        List<Map<String, Object>> authoredRegistrations = new ArrayList<>();
        for (JsonElement element : review.getAsJsonArray("tables")) {
            JsonObject row = element.getAsJsonObject();
            if (!row.get("decision").getAsString().equals("author-missing")) continue;
            List<String> registeredItems = new ArrayList<>();
            for (JsonElement declared : row.getAsJsonArray("authored_items")) {
                ResourceLocation id = new ResourceLocation(declared.getAsString());
                var item = ForgeRegistries.ITEMS.getValue(id);
                if (!ForgeRegistries.ITEMS.containsKey(id) || item == null || item == Items.AIR)
                    throw new IllegalStateException("newly authored loot item missing from native registry: " + id);
                registeredItems.add(ForgeRegistries.ITEMS.getKey(item).toString());
            }
            if (registeredItems.isEmpty()) throw new IllegalStateException("newly authored table has no reviewed item identities");
            authoredRegistrations.add(Map.of("table", row.get("id").getAsString(), "registered_non_air_items", registeredItems));
        }
        if (authoredRegistrations.size() != 8) throw new IllegalStateException("expected eight reviewed newly authored loot definitions");
        result.put("newly_authored_item_registry", authoredRegistrations);
        result.put("personal_rng", "native Lootr getInventory/createInventory path; authored container seed is not a promise of deterministic personal-roll RNG");
        if (!level.getBlockState(FIXTURE).isAir() || !level.getBlockState(FIXTURE.above()).isAir())
            throw new IllegalStateException("Lootr fixture would overwrite foreign blocks");
        var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("lootr:lootr_chest"));
        if (block == null || block == Blocks.AIR) throw new IllegalStateException("actual Lootr chest block unavailable");
        try {
            for (ServerPlayer p : pair) {
                p.closeContainer(); p.setShiftKeyDown(false); p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                p.teleportTo(level, FIXTURE.getX() + .5, FIXTURE.getY(), FIXTURE.getZ() + 2.5, 180, 15);
            }
            for (JsonElement element : review.getAsJsonObject("native_probe").getAsJsonArray("tables")) {
                ResourceLocation table = new ResourceLocation(element.getAsString());
                if (!owner.server.getLootData().getKeys(LootDataType.TABLE).contains(table)) throw new IllegalStateException("missing reviewed table " + table);
                level.setBlock(FIXTURE, block.defaultBlockState(), 3);
                if (!(level.getBlockEntity(FIXTURE) instanceof LootrChestBlockEntity chest)) throw new IllegalStateException("not a Lootr block entity");
                // Give only the newly authored block its table. No existing/world-generated
                // chest, cache or inventory is reassigned, cleared or migrated.
                chest.setLootTable(table, 20261020L);
                UUID tile = chest.getTileId();
                if (tile == null || !chest.getOpeners().isEmpty()) throw new IllegalStateException("Lootr fixture is not fresh");
                var data = DataStorage.getContainerData(level, FIXTURE, tile);
                if (data == null || data.getInventory(owner) != null || data.getInventory(second) != null)
                    throw new IllegalStateException("fresh Lootr tile already has personal inventory");
                ILootrInventory first = openPersonal(owner, chest, policy);
                CompoundTag firstBefore = snapshot(first);
                owner.closeContainer();
                ILootrInventory other = openPersonal(second, chest, policy);
                CompoundTag secondBefore = snapshot(other);
                second.closeContainer();
                if (first == other || data.getInventory(owner) != first || data.getInventory(second) != other)
                    throw new IllegalStateException("Lootr aliased players or bypassed native per-player cache");
                // Equal RNG outcomes are legal. Independent object/cache ownership, not
                // requiring different item IDs, is the distinct-roll oracle.
                int changedSlot = -1;
                for (int slot = 0; slot < first.getContainerSize(); slot++) if (!first.getItem(slot).isEmpty()) { changedSlot = slot; break; }
                if (changedSlot < 0) throw new IllegalStateException("selected Lootr table unexpectedly empty " + table);
                ItemStack removed = first.removeItem(changedSlot, 1);
                if (removed.isEmpty()) throw new IllegalStateException("native personal inventory removal failed");
                CompoundTag firstAfter = snapshot(first);
                if (firstAfter.equals(firstBefore) || !snapshot(other).equals(secondBefore)) throw new IllegalStateException("Lootr personal mutation was shared or lost");
                ILootrInventory repeat = openPersonal(owner, chest, policy);
                if (repeat != first || !snapshot(repeat).equals(firstAfter)) throw new IllegalStateException("Lootr second open rerolled depleted personal inventory");
                owner.closeContainer();
                ILootrInventory otherRepeat = openPersonal(second, chest, policy);
                if (otherRepeat != other || !snapshot(otherRepeat).equals(secondBefore)) throw new IllegalStateException("Lootr other player cache changed after first-player removal");
                second.closeContainer();
                if (!chest.getOpeners().containsAll(pair.stream().map(ServerPlayer::getUUID).toList()) || !table.equals(chest.getTable()))
                    throw new IllegalStateException("native Lootr opener/table state missing");
                samples.add(Map.of("table", table.toString(), "authored_container_seed", 20261020L, "tile_uuid", tile.toString(),
                        "first_player_before", firstBefore.toString(), "first_player_after_native_remove", firstAfter.toString(),
                        "second_player_unchanged", secondBefore.toString(), "same_player_cache_identity", true, "distinct_player_cache_identity", true,
                        "origin", "authored_fresh_lootr_block_native_useItemOn_and_menu"));
                // Removal is only of this newly authored block. Its generated saved Lootr
                // cache/evidence remains retained; no DataStorage.clear* API is called.
                level.setBlock(FIXTURE, Blocks.AIR.defaultBlockState(), 3);
            }
            result.put("personal_rolls", samples);
            result.put("native_negative_contexts", negativeContexts(owner, policy));
            result.put("generated_structure", generatedShipwreck(owner, policy));
            result.put("consumer_reachability", consumerReachability(owner, review));
            @SuppressWarnings("unchecked") Map<String, Object> reach = (Map<String, Object>) result.get("consumer_reachability");
            @SuppressWarnings("unchecked") Map<String, Object> generated = (Map<String, Object>) result.get("generated_structure");
            result.put("complete", Boolean.TRUE.equals(reach.get("complete")) && Boolean.TRUE.equals(generated.get("complete")));
            result.put("limitations", "Finite native samples and one naturally generated shipwreck are representative, not exhaustive distributions. Data reachability is not placement frequency; unresolved/unknown active consumers remain blocking findings, never assumed empty.");
            return result;
        } finally {
            for (ServerPlayer p : pair) p.closeContainer();
            level.setBlock(FIXTURE, Blocks.AIR.defaultBlockState(), 3);
            for (PlayerState state : states) state.restore();
        }
    }

    private static boolean live(ServerPlayer p) {
        return !(p instanceof FakePlayer) && !LootrAPI.isFakePlayer(p) && p.connection != null
                && p.connection.connection.isConnected() && p.isAlive()
                && p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
    }

    private static ILootrInventory openPersonal(ServerPlayer player, LootrChestBlockEntity chest, Policy policy) {
        if (!live(player)) throw new IllegalStateException("native Lootr network player disconnected");
        var action = player.gameMode.useItemOn(player, player.serverLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(chest.getBlockPos()), Direction.SOUTH, chest.getBlockPos(), false));
        if (!action.consumesAction() || !(player.containerMenu instanceof ChestMenu menu)
                || !(menu.getContainer() instanceof ILootrInventory inventory)
                || !inventory.getPos().equals(chest.getBlockPos()) || !chest.getOpeners().contains(player.getUUID()))
            throw new IllegalStateException("actual native Lootr block interaction/menu/opener path failed");
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) policy.check(inventory.getItem(slot));
        return inventory;
    }

    private static CompoundTag snapshot(Container container) {
        CompoundTag result = new CompoundTag();
        for (int slot = 0; slot < container.getContainerSize(); slot++) result.put(Integer.toString(slot), container.getItem(slot).save(new CompoundTag()));
        return result;
    }

    private static Map<String, Object> negativeContexts(ServerPlayer player, Policy policy) {
        var level = player.serverLevel();
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(FIXTURE)).create(LootContextParamSets.CHEST);
        List<Map<String, Object>> observations = new ArrayList<>();
        boolean largeCarry = false, geologicalFeed = false;
        for (String id : List.of("quark:misc/monster_box", "quark:entities/stoneling_carry", "wares:gameplay/empty_package", "minecraft:empty", "the_bumblezone:structures/sempiternal_sanctum/sudoku_failed")) {
            ResourceLocation key = new ResourceLocation(id);
            if (!player.server.getLootData().getKeys(LootDataType.TABLE).contains(key)) throw new IllegalStateException("negative table missing " + key);
            var table = player.server.getLootData().getLootTable(key);
            for (long seed = 1; seed <= 128; seed++) {
                var items = table.getRandomItems(params, seed);
                for (ItemStack stack : items) policy.check(stack);
                if ((id.equals("minecraft:empty") || id.endsWith("/sudoku_failed")) && !items.isEmpty())
                    throw new IllegalStateException("empty/fully-filtered failure acquired filler " + id);
                if (id.equals("quark:entities/stoneling_carry")) {
                    if (items.size() > 1) throw new IllegalStateException("CHEST-context mob reward acquired generic supplies");
                    if (items.stream().anyMatch(s -> s.is(Items.COAL) && s.getCount() > 16)) largeCarry = true;
                }
                if (id.equals("quark:misc/monster_box")) for (ItemStack stack : items) {
                    String item = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                    if (Set.of("minecraft:raw_iron", "minecraft:raw_copper", "minecraft:raw_gold").contains(item))
                        throw new IllegalStateException("native geological replacement did not apply");
                    if (Set.of("better_ore_geology:small_ore_chunk_ironstone", "better_ore_geology:small_ore_chunk_copper_bloom").contains(item)) {
                        if (stack.getCount() < 4 || stack.getCount() > 10) throw new IllegalStateException("native geological feed count escaped source range");
                        geologicalFeed = true;
                    }
                    if (item.equals("better_ore_geology:gold_concentrate")) {
                        if (stack.getCount() < 3 || stack.getCount() > 8) throw new IllegalStateException("native gold feed count escaped source range");
                        geologicalFeed = true;
                    }
                }
                if (id.equals("wares:gameplay/empty_package") && items.stream().anyMatch(s -> !s.is(Items.PAPER)))
                    throw new IllegalStateException("empty-package helper acquired unrelated supplies");
                if (seed == 1) observations.add(Map.of("table", id, "seed", seed, "stacks", items.stream().map(s -> s.save(new CompoundTag()).toString()).toList()));
            }
        }
        if (!largeCarry) throw new IllegalStateException("native CHEST-context Stoneling large coal quantity not preserved across declared 128 seeds");
        if (!geologicalFeed) throw new IllegalStateException("declared native negative sample did not exercise geological feed");
        return Map.of("contexts", observations, "seeds_per_context", 128, "stoneling_large_native_quantity_preserved", true,
                "geological_replacement_in_source_count_range", true,
                "origin", "native CHEST-origin table pipeline; not a mob spawn/AI or physical helper placement claim");
    }

    private static Map<String, Object> generatedShipwreck(ServerPlayer player, Policy policy) {
        ServerLevel level = player.serverLevel();
        BlockPos search = new BlockPos(1_006_000, 64, 1_006_000);
        TagKey<Structure> tag = TagKey.create(Registries.STRUCTURE, new ResourceLocation("minecraft:shipwreck"));
        long started = System.nanoTime();
        LOGGER.info("[bc-structure-loot] phase=locate-before origin={} radius_chunks=64 elapsed_ms={}", search, (System.nanoTime() - started) / 1_000_000L);
        BlockPos located = level.findNearestMapStructure(tag, search, 64, false);
        LOGGER.info("[bc-structure-loot] phase=locate-after origin={} radius_chunks=64 located={} elapsed_ms={}", search, located, (System.nanoTime() - started) / 1_000_000L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("search_origin", List.of(search.getX(), search.getY(), search.getZ())); result.put("search_radius_chunks", 64);
        result.put("origin", "native locate, native full chunk generation and saved structure start; no /place or authored LootTable/container");
        if (located == null) { result.put("complete", false); result.put("blocking_finding", "No natural shipwreck located within bounded native search"); return result; }
        LOGGER.info("[bc-structure-loot] phase=full-chunk-before purpose=located-start origin={} radius_chunks=64 chunk_x={} chunk_z={} elapsed_ms={}", search, located.getX() >> 4, located.getZ() >> 4, (System.nanoTime() - started) / 1_000_000L);
        var startChunk = level.getChunk(located.getX() >> 4, located.getZ() >> 4);
        LOGGER.info("[bc-structure-loot] phase=full-chunk-after purpose=located-start origin={} radius_chunks=64 chunk_x={} chunk_z={} elapsed_ms={}", search, located.getX() >> 4, located.getZ() >> 4, (System.nanoTime() - started) / 1_000_000L);
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<Map<String, Object>> starts = new ArrayList<>();
        List<Map<String, Object>> containers = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        boolean openedSupply = false;
        for (var entry : startChunk.getAllStarts().entrySet()) {
            if (!entry.getValue().isValid() || !registry.getHolderOrThrow(registry.getResourceKey(entry.getKey()).orElseThrow()).is(tag)) continue;
            var box = entry.getValue().getBoundingBox();
            if (box.getXSpan() > 96 || box.getZSpan() > 96) throw new IllegalStateException("native shipwreck exceeds bounded fixture extent");
            starts.add(Map.of("structure", registry.getKey(entry.getKey()).toString(), "bounding_box", box.toString(), "piece_count", entry.getValue().getPieces().size()));
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (System.nanoTime() - started > 120_000_000_000L) throw new IllegalStateException("native generated shipwreck budget expired");
                LOGGER.info("[bc-structure-loot] phase=full-chunk-before purpose=structure-extent origin={} radius_chunks=64 chunk_x={} chunk_z={} elapsed_ms={}", search, cx, cz, (System.nanoTime() - started) / 1_000_000L);
                var chunk = level.getChunk(cx, cz);
                LOGGER.info("[bc-structure-loot] phase=full-chunk-after purpose=structure-extent origin={} radius_chunks=64 chunk_x={} chunk_z={} elapsed_ms={}", search, cx, cz, (System.nanoTime() - started) / 1_000_000L);
                for (var entity : new ArrayList<>(chunk.getBlockEntities().values())) {
                    BlockPos pos = entity.getBlockPos();
                    if (!box.isInside(pos) || !visited.add(pos)) continue;
                    CompoundTag before = entity.saveWithFullMetadata();
                    String table = before.contains("specialLootChest_table") ? before.getString("specialLootChest_table") : before.getString("LootTable");
                    if (table.isEmpty()) continue;
                    Map<String, Object> row = new LinkedHashMap<>(); row.put("position", List.of(pos.getX(), pos.getY(), pos.getZ()));
                    row.put("block", ForgeRegistries.BLOCKS.getKey(entity.getBlockState().getBlock()).toString()); row.put("table_before_open", table);
                    row.put("native_block_entity_before", before.toString());
                    if (table.equals("minecraft:chests/shipwreck_supply")) {
                        player.closeContainer(); player.teleportTo(level, pos.getX() + .5, pos.getY(), pos.getZ() + 2.5, 180, 10);
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.setShiftKeyDown(false);
                        var action = player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false));
                        if (!action.consumesAction() || !(player.containerMenu instanceof ChestMenu menu)) throw new IllegalStateException("generated physical supply chest did not open natively");
                        Container inventory = menu.getContainer();
                        for (int slot = 0; slot < inventory.getContainerSize(); slot++) policy.check(inventory.getItem(slot));
                        row.put("native_opened_inventory", snapshot(inventory).toString()); row.put("lootr_personal_menu", inventory instanceof ILootrInventory);
                        player.closeContainer(); openedSupply = true;
                    }
                    containers.add(row);
                }
            }
        }
        result.put("located", List.of(located.getX(), located.getY(), located.getZ())); result.put("saved_native_starts", starts); result.put("generated_containers", containers);
        result.put("complete", !starts.isEmpty() && openedSupply);
        if (!openedSupply) result.put("blocking_finding", "Native located/generated structure did not expose an unopened physical shipwreck_supply consumer");
        return result;
    }

    private static Map<String, Object> consumerReachability(ServerPlayer player, JsonObject review) throws Exception {
        // Exact loaded registry/resource witnesses. This conservative graph can prove
        // a data path, but absence cannot disprove a custom Java placement caller.
        Map<String, JsonObject> documents = new LinkedHashMap<>();
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Set<String> roots = new LinkedHashSet<>();
        var access = player.serverLevel().registryAccess();
        for (String kind : List.of("structure_set", "structure", "template_pool", "processor_list", "configured_feature", "placed_feature")) {
            for (var resource : player.server.getResourceManager().listResources("worldgen/" + kind, path -> path.getPath().endsWith(".json")).entrySet()) {
                String path = resource.getKey().getPath();
                String name = resource.getKey().getNamespace() + ":" + path.substring(("worldgen/" + kind + "/").length(), path.length() - 5);
                String node = kind + ":" + name;
                try (var reader = resource.getValue().openAsReader()) {
                    var json = JsonParser.parseReader(reader);
                    if (json.isJsonObject()) documents.put(node, json.getAsJsonObject());
                }
            }
        }
        for (ResourceLocation id : access.registryOrThrow(Registries.STRUCTURE_SET).keySet()) roots.add("structure_set:" + id);
        // Native generation settings from actually possible biomes, not all declared
        // biome JSON. Keep separate witnesses for every loaded dimension.
        List<Map<String, Object>> dimensionBiomes = new ArrayList<>();
        for (ServerLevel level : player.server.getAllLevels()) {
            List<String> biomeIds = new ArrayList<>();
            for (var biome : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
                biome.unwrapKey().ifPresent(key -> biomeIds.add(key.location().toString()));
                for (var step : biome.value().getGenerationSettings().features()) for (var feature : step)
                    feature.unwrapKey().ifPresent(key -> roots.add("placed_feature:" + key.location()));
            }
            dimensionBiomes.add(Map.of("dimension", level.dimension().location().toString(), "possible_biomes", biomeIds.stream().sorted().toList()));
        }
        for (var entry : documents.entrySet()) {
            Set<String> edges = new LinkedHashSet<>();
            collectEdges(entry.getValue(), "", entry.getKey().split(":", 2)[0], documents.keySet(), edges);
            graph.put(entry.getKey(), edges);
        }
        Set<String> reachable = new HashSet<>(); Map<String, String> predecessor = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            if (!reachable.add(node)) continue;
            for (String child : graph.getOrDefault(node, Set.of())) if (!reachable.contains(child)) { predecessor.putIfAbsent(child, node); queue.add(child); }
        }
        Map<String, Map<String, Object>> unusedEvidence = reviewedUnusedEvidence(player, review, reachable);
        List<Map<String, Object>> observations = new ArrayList<>(); List<String> blockers = new ArrayList<>();
        List<String> selectedPacks = new ArrayList<>(player.server.getPackRepository().getSelectedIds());
        selectedPacks.sort(String::compareTo);
        for (JsonElement element : review.getAsJsonArray("tables")) {
            JsonObject row = element.getAsJsonObject(); String table = row.get("id").getAsString(); String kind = row.get("kind").getAsString();
            List<Map<String, Object>> templates = new ArrayList<>();
            boolean dataReachable = reachable.contains("table:" + table);
            for (JsonElement witness : row.getAsJsonArray("template_consumers")) {
                var matcher = TEMPLATE.matcher(witness.getAsString());
                if (!matcher.find()) throw new IllegalStateException("unparseable reviewed native template witness");
                ResourceLocation id = new ResourceLocation(matcher.group(1), matcher.group(2));
                var nativeTemplate = player.server.getStructureManager().get(id);
                String node = "template:" + id;
                boolean reached = reachable.contains(node); dataReachable |= reached;
                List<String> chain = chain(node, predecessor);
                templates.add(Map.of("template", id.toString(), "loaded_native_template", nativeTemplate.isPresent(), "data_reachable", reached,
                        "registry_path", chain, "reviewed_block_consumer", witness.getAsString()));
            }
            boolean loaded = player.server.getLootData().getKeys(LootDataType.TABLE).contains(new ResourceLocation(table));
            // Pinned RatsMod.addRatlantisDatapack registers pack ID "ratlantis".
            // Its dutchrat_ship table, structure and structure-set resources exist
            // only inside that optional built-in pack. Default configuration alone
            // is deliberately insufficient: every native absence is required.
            Map<String, Boolean> ratlantisConjuncts = new LinkedHashMap<>();
            if (table.equals("rats:chest/dutchrat_ship")) {
                ResourceLocation ship = new ResourceLocation("rats:dutchrat_ship");
                ratlantisConjuncts.put("builtin_pack_not_selected", !selectedPacks.contains("ratlantis"));
                ratlantisConjuncts.put("structure_set_absent", !access.registryOrThrow(Registries.STRUCTURE_SET).containsKey(ship));
                ratlantisConjuncts.put("structure_absent", !access.registryOrThrow(Registries.STRUCTURE).containsKey(ship));
                ratlantisConjuncts.put("loot_table_absent", !loaded);
                ratlantisConjuncts.put("no_active_data_or_template_path", !dataReachable);
            }
            boolean disabledRatlantis = !ratlantisConjuncts.isEmpty() && ratlantisConjuncts.values().stream().allMatch(Boolean.TRUE::equals);
            boolean exactArchaeology = exactGoetyArchaeology(player, review, table);
            if (kind.equals("unresolved") && !disabledRatlantis) {
                if (!exactArchaeology && !loaded) blockers.add(table + (dataReachable ? ": missing table on reachable data/template path" : ": missing table; custom placement activity remains UNKNOWN"));
            }
            String namespace = new ResourceLocation(table).getNamespace();
            boolean orphanDefinition = Set.of("apotheosis", "apotheotic_additions").contains(namespace)
                    && !ModList.get().isLoaded(namespace) && !dataReachable && templates.isEmpty()
                    && row.getAsJsonArray("worldgen_consumers").isEmpty();
            Map<String, Object> unused = unusedEvidence.getOrDefault(table, Map.of());
            boolean reviewedUnused = Boolean.TRUE.equals(unused.get("inactive")) && templates.isEmpty()
                    && row.getAsJsonArray("worldgen_consumers").isEmpty();
            if (kind.equals("unknown-consumer") && !orphanDefinition && !reviewedUnused)
                blockers.add(table + ": physical consumer activity remains UNKNOWN; no folder/type inference");
            if (kind.equals("unresolved") || kind.equals("unknown-consumer") || (!templates.isEmpty() && kind.equals("physical"))) {
                Map<String, Object> observation = new LinkedHashMap<>(); observation.put("table", table); observation.put("review_kind", kind);
                observation.put("live_table_loaded", loaded); observation.put("native_template_consumers", templates);
                observation.put("reviewed_worldgen_consumers", row.getAsJsonArray("worldgen_consumers"));
                observation.put("inactive", disabledRatlantis || reviewedUnused);
                observation.put("exact_native_archaeology_nonphysical", exactArchaeology);
                if (!unused.isEmpty()) observation.put("reviewed_unused_definition", unused);
                if (!ratlantisConjuncts.isEmpty()) observation.put("ratlantis_disabled_conjuncts", ratlantisConjuncts);
                observation.put("activity_verdict", disabledRatlantis ? "optional_ratlantis_pack_unselected_and_native_placement_roots_and_table_absent"
                        : reviewedUnused ? "finite_pinned_datagen_only_definition_and_no_selected_native_consumer"
                        : exactArchaeology ? "exact_native_goety_brushable_gravel_nonphysical_context"
                        : dataReachable ? "reachable_data_path_not_placement_frequency"
                        : orphanDefinition ? "reviewed_orphan_definition_no_loaded_provider_or_known_consumer" : "not_proven_by_data_graph");
                observations.add(observation);
            }
        }
        return Map.of("complete", blockers.isEmpty(), "blocking_findings", blockers, "consumer_observations", observations,
                "loaded_dimension_biomes", dimensionBiomes, "selected_native_packs", selectedPacks, "root_count", roots.size(), "reachable_nodes", reachable.size(),
                "limits", "Structure-set roots are conservative across native generator configurations. Data reachability does not prove biome-valid placement or custom Java enablement. Missing/custom unknowns are not silently excluded.");
    }

    private static ResourceLocation dataResource(String source) {
        String[] parts = source.split("/", 3);
        if (parts.length != 3 || !parts[0].equals("data")) throw new IllegalStateException("invalid reviewed data resource");
        return new ResourceLocation(parts[1], parts[2]);
    }

    private static boolean nativeResourceHash(ServerPlayer player, String source, String expected) throws Exception {
        var resource = player.server.getResourceManager().getResource(dataResource(source));
        if (resource.isEmpty()) return false;
        try (var input = resource.get().open()) { return sha256(input.readAllBytes()).equals(expected); }
    }

    private static boolean exactGoetyArchaeology(ServerPlayer player, JsonObject review, String table) throws Exception {
        if (!table.equals("goety:archaelogy/ruined_monastery")) return false;
        JsonObject scope = review.getAsJsonObject("unused_definition_review").getAsJsonObject("nonphysical_context");
        if (!scope.get("id").getAsString().equals(table)
                || !scope.get("template").getAsString().equals("goety:ruined_monastery/rubbles_4")
                || !scope.get("block").getAsString().equals("minecraft:suspicious_gravel")
                || !scope.get("block_entity").getAsString().equals("minecraft:brushable_block")
                || !nativeResourceHash(player, scope.get("resource").getAsString(), scope.get("template_sha256").getAsString())) return false;
        var template = player.server.getStructureManager().get(new ResourceLocation("goety:ruined_monastery/rubbles_4"));
        if (template.isEmpty()) return false;
        CompoundTag saved = template.get().save(new CompoundTag());
        var palette = saved.getList("palette", 10);
        var blocks = saved.getList("blocks", 10); int matching = 0;
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.getCompound(i); var nbt = block.getCompound("nbt");
            if (!nbt.getString("LootTable").equals(table)) continue;
            int state = block.getInt("state");
            if (state < 0 || state >= palette.size()) return false;
            var actual = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(palette.getCompound(state).getString("Name")));
            var pos = block.getList("pos", 3);
            if (actual != Blocks.SUSPICIOUS_GRAVEL || !nbt.getString("id").equals("minecraft:brushable_block")
                    || pos.size() != 3 || pos.getInt(0) != 1 || pos.getInt(1) != 0 || pos.getInt(2) != 0) return false;
            matching++;
        }
        return matching == 1;
    }

    private static Map<String, Map<String, Object>> reviewedUnusedEvidence(ServerPlayer player, JsonObject review, Set<String> reachable) throws Exception {
        JsonObject source = review.getAsJsonObject("unused_definition_review");
        if (!source.get("schema").getAsString().equals("bc.structure_loot_unused.v1")) throw new IllegalStateException("unsupported unused-definition review");
        Set<String> allowed = Set.of("bloodmagic:chests/simple_dungeon/test_gems", "rats:chest/plague_doctor_hut");
        Map<String, JsonObject> contexts = new LinkedHashMap<>();
        for (JsonElement element : source.getAsJsonArray("definitions")) {
            JsonObject row = element.getAsJsonObject(); String id = row.get("id").getAsString();
            if (!allowed.contains(id) || contexts.put(id, row) != null) throw new IllegalStateException("unused-definition context escaped finite review");
        }
        if (!contexts.keySet().equals(allowed)) throw new IllegalStateException("missing reviewed unused-definition context");
        Map<String, List<String>> selectedHits = new LinkedHashMap<>(), configHits = new LinkedHashMap<>();
        for (String id : allowed) { selectedHits.put(id, new ArrayList<>()); configHits.put(id, new ArrayList<>()); }
        int resourceCount = 0; long scannedBytes = 0;
        // Selected native data only, including every selected physical template.
        // A new positive literal is conservative blocking evidence, not an excuse
        // to skip it because the resource is outside an old source inventory.
        for (var entry : player.server.getResourceManager().listResources("", ignored -> true).entrySet()) {
            if (++resourceCount > 100_000) throw new IllegalStateException("selected-consumer resource bound exceeded");
            byte[] raw;
            try (var input = entry.getValue().open()) { raw = input.readNBytes(33_554_433); }
            if (raw.length > 33_554_432) throw new IllegalStateException("selected-consumer individual resource bound exceeded");
            if (raw.length > 2 && raw[0] == (byte) 0x1f && raw[1] == (byte) 0x8b) {
                try (var input = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(raw))) { raw = input.readNBytes(33_554_433); }
            }
            scannedBytes += raw.length;
            if (raw.length > 33_554_432 || scannedBytes > 1_073_741_824L) throw new IllegalStateException("selected-consumer byte bound exceeded");
            String text = new String(raw, StandardCharsets.UTF_8);
            for (String id : allowed) {
                if (entry.getKey().equals(dataResource(contexts.get(id).get("definition_resource").getAsString()))) continue;
                if (text.contains(id) || text.contains(new ResourceLocation(id).getPath())) selectedHits.get(id).add(entry.getKey().toString());
            }
        }
        Path root = player.server.getServerDirectory().toPath().toAbsolutePath().normalize(); int configCount = 0;
        for (String directory : List.of("config", "defaultconfigs", "kubejs/server_scripts", "kubejs/startup_scripts")) {
            Path base = root.resolve(directory);
            if (!Files.exists(base)) continue;
            try (var walk = Files.walk(base)) {
                var paths = walk.iterator();
                while (paths.hasNext()) {
                    Path path = paths.next();
                    if (Files.isSymbolicLink(path)) throw new IllegalStateException("symlink in reviewed code-configuration inputs");
                    if (!Files.isRegularFile(path)) continue;
                    if (++configCount > 100_000 || Files.size(path) > 33_554_432) throw new IllegalStateException("code-configuration scan bound exceeded");
                    byte[] raw = Files.readAllBytes(path); scannedBytes += raw.length;
                    if (scannedBytes > 1_073_741_824L) throw new IllegalStateException("consumer byte bound exceeded");
                    String text = new String(raw, StandardCharsets.UTF_8);
                    for (String id : allowed) if (text.contains(id) || text.contains(new ResourceLocation(id).getPath())) configHits.get(id).add(root.relativize(path).toString());
                }
            }
        }
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        for (String id : allowed) {
            JsonObject row = contexts.get(id); ResourceLocation key = new ResourceLocation(id);
            Map<String, Boolean> conjunction = new LinkedHashMap<>();
            conjunction.put("no_known_pinned_live_code_or_external_data_callers", row.getAsJsonArray("live_known_code_callers").isEmpty()
                    && row.getAsJsonArray("external_data_consumers").isEmpty() && row.getAsJsonArray("pack_configuration_consumers").isEmpty());
            boolean archiveMatches = false, classMatches = false;
            var mod = ModList.get().getModFileById(key.getNamespace());
            if (mod != null) {
                Path archive = mod.getFile().getFilePath();
                if (Files.isRegularFile(archive)) {
                    archiveMatches = sha256(Files.readAllBytes(archive)).equals(row.get("archive_sha256").getAsString());
                    try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
                        var item = zip.getEntry(row.get("class_path").getAsString());
                        if (item != null) try (var input = zip.getInputStream(item)) { classMatches = sha256(input.readAllBytes()).equals(row.get("class_sha256").getAsString()); }
                    }
                }
            }
            conjunction.put("loaded_owner_archive_matches_reviewed_pin", archiveMatches);
            conjunction.put("exclusive_datagen_class_hash_matches", classMatches);
            conjunction.put("own_definition_resource_hash_matches", nativeResourceHash(player, row.get("definition_resource").getAsString(), row.get("definition_sha256").getAsString()));
            conjunction.put("no_reachable_native_data_consumer", !reachable.contains("table:" + id));
            conjunction.put("no_selected_data_or_physical_template_consumer", selectedHits.get(id).isEmpty());
            conjunction.put("no_selected_code_configuration_consumer", configHits.get(id).isEmpty());
            if (id.equals("rats:chest/plague_doctor_hut")) {
                JsonObject witness = row.getAsJsonObject("actual_template_witness");
                boolean emptyChest = false;
                var template = player.server.getStructureManager().get(new ResourceLocation("rats:plague_doctor_hut"));
                if (template.isPresent()) {
                    var blocks = template.get().save(new CompoundTag()).getList("blocks", 10);
                    for (int i = 0; i < blocks.size(); i++) {
                        var block = blocks.getCompound(i); var nbt = block.getCompound("nbt"); var pos = block.getList("pos", 3);
                        if (pos.size() == 3 && pos.getInt(0) == 3 && pos.getInt(1) == 4 && pos.getInt(2) == 6)
                            emptyChest = nbt.getString("id").equals("minecraft:chest") && nbt.contains("Items", 9)
                                    && nbt.getList("Items", 10).isEmpty() && !nbt.contains("LootTable") && !nbt.contains("specialLootChest_table");
                    }
                }
                conjunction.put("actual_native_template_has_reviewed_empty_unassigned_chest", emptyChest
                        && nativeResourceHash(player, witness.get("resource").getAsString(), witness.get("sha256").getAsString()));
            }
            boolean inactive = conjunction.values().stream().allMatch(Boolean.TRUE::equals);
            results.put(id, Map.of("inactive", inactive, "conjuncts", conjunction, "selected_positive_consumers", selectedHits.get(id),
                    "code_configuration_positive_consumers", configHits.get(id), "selected_resources_examined", resourceCount,
                    "configuration_files_examined", configCount, "limits", source.get("limits").getAsString()));
        }
        return results;
    }

    private static List<String> chain(String node, Map<String, String> predecessor) {
        ArrayList<String> result = new ArrayList<>(); Set<String> visited = new HashSet<>();
        while (node != null && visited.add(node) && result.size() < 64) { result.add(0, node); node = predecessor.get(node); }
        return result;
    }

    private static void collectEdges(JsonElement value, String field, String kind, Set<String> nodes, Set<String> out) {
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) collectEdges(entry.getValue(), entry.getKey(), kind, nodes, out);
        } else if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) collectEdges(child, field, kind, nodes, out);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String id = value.getAsString(); if (!RESOURCE.matcher(id).matches()) return;
            if (field.equals("location") && kind.equals("template_pool")) out.add("template:" + id);
            if (field.equals("structure")) out.add("structure:" + id);
            if (field.equals("start_pool") || field.equals("fallback")) out.add("template_pool:" + id);
            if (field.equals("processors")) out.add("processor_list:" + id);
            if (field.equals("loot_table") || field.equals("LootTable")) out.add("table:" + id);
            if (field.equals("feature")) {
                if (nodes.contains("placed_feature:" + id)) out.add("placed_feature:" + id);
                if (nodes.contains("configured_feature:" + id)) out.add("configured_feature:" + id);
            }
        }
    }

    private static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private record PlayerState(ServerPlayer player, ServerLevel level, Vec3 position, float yaw, float pitch,
                               int selected, ItemStack held, boolean shift) {
        PlayerState(ServerPlayer player) { this(player, player.serverLevel(), player.position(), player.getYRot(), player.getXRot(), player.getInventory().selected, player.getMainHandItem().copy(), player.isShiftKeyDown()); }
        void restore() {
            player.closeContainer(); player.getInventory().selected = selected; player.setItemInHand(InteractionHand.MAIN_HAND, held);
            player.setShiftKeyDown(shift); player.teleportTo(level, position.x, position.y, position.z, yaw, pitch);
        }
    }

    private static final class Policy {
        final Set<String> forbidden = new HashSet<>();
        static Policy read(ServerPlayer player, JsonObject review) throws Exception {
            Policy result = new Policy();
            Path root = player.server.getServerDirectory().toPath().toAbsolutePath().normalize();
            Map<String, String> sources = new HashMap<>();
            for (var entry : review.getAsJsonObject("policy_sha256").entrySet()) {
                Path path = root.resolve(entry.getKey()).normalize();
                if (!path.startsWith(root)) throw new IllegalStateException("escaping acquisition input");
                for (Path p = path; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IllegalStateException("symlink acquisition input");
                byte[] bytes = Files.readAllBytes(path);
                if (!sha256(bytes).equals(entry.getValue().getAsString())) throw new IllegalStateException("candidate acquisition policy differs from reviewed input " + entry.getKey());
                sources.put(entry.getKey(), new String(bytes, StandardCharsets.UTF_8));
            }
            String script = sources.get("kubejs/server_scripts/policy/global_loot_progression.js");
            for (String name : List.of("BC_LOOT_REMOVE_ITEMS", "BC_CHEST_LOOT_REMOVE_SEEDS", "BC_TECH01_LOOT_REMOVE_ITEMS")) {
                var matcher = Pattern.compile("var\\s+" + name + "\\s*=\\s*\\[([\\s\\S]*?)\\]").matcher(script);
                if (!matcher.find()) throw new IllegalStateException("reviewed progression source array missing " + name);
                var ids = Pattern.compile("'([a-z0-9_.-]+:[a-z0-9_./-]+)'").matcher(matcher.group(1));
                while (ids.find()) result.forbidden.add(ids.group(1));
            }
            JsonObject quarantine = JsonParser.parseString(sources.get("kubejs/config/quarantined_items.json")).getAsJsonObject();
            for (JsonElement id : quarantine.getAsJsonArray("items")) result.forbidden.add(id.getAsString());
            JsonObject crafting = JsonParser.parseString(sources.get("kubejs/config/crafting_policy.json")).getAsJsonObject();
            for (JsonElement familyElement : crafting.getAsJsonArray("families")) {
                var family = familyElement.getAsJsonObject();
                if (!Set.of("cut", "authority", "canonical_duplicate").contains(family.get("disposition").getAsString())) continue;
                for (JsonElement selectorElement : family.getAsJsonArray("selectors")) {
                    var selector = selectorElement.getAsJsonObject(); if (selector.has("tag")) continue; // production deliberately skips tag selectors too
                    for (ResourceLocation id : ForgeRegistries.ITEMS.getKeys()) {
                        boolean matches = selector.has("exact_id") ? id.toString().equals(selector.get("exact_id").getAsString())
                                : (selector.has("namespace") || selector.has("id_prefix"))
                                  && (!selector.has("namespace") || id.getNamespace().equals(selector.get("namespace").getAsString()))
                                  && (!selector.has("id_prefix") || id.getPath().startsWith(selector.get("id_prefix").getAsString()));
                        if (matches) result.forbidden.add(id.toString());
                    }
                }
            }
            return result;
        }
        void check(ItemStack stack) {
            if (stack.isEmpty()) return;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null || forbidden.contains(id.toString())) throw new IllegalStateException("native loot leaked reviewed progression cut " + stack);
            if (stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION) || stack.is(Items.TIPPED_ARROW)
                    || stack.is(Items.POTION) && (PotionUtils.getPotion(stack) != Potions.WATER || !PotionUtils.getCustomEffects(stack).isEmpty()))
                throw new IllegalStateException("native loot leaked disabled potion delivery " + stack);
        }
    }
}
