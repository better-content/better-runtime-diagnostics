package com.bettercontent.runtimetestsupport;

import com.google.gson.Gson;
import com.bettercontent.spiritcommerce.spirit.CurrencyIdentity;
import com.bettercontent.spiritcommerce.trader.TraderCampData;
import com.bettercontent.spiritcommerce.trader.TraderCampPostBlockEntity;
import com.bettercontent.spiritcommerce.trader.WanderingTraderVisits;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Narrow native ordinary-arrival/expiry witness. Never calls a spawn callback or alters time. */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support")
public final class GameplayTraderProbe {
    private static UUID playerId;
    private static final Map<UUID, Visit> visits = new LinkedHashMap<>();
    private static long armedTick;
    private static boolean finished;
    private static final Gson JSON = new Gson();
    private static final class Restart {
        String schema = "bc.gameplay_trader_restart.v1", run, nonce, player, world;
        String clientHash, serverHash, supportHash, trader, fireBlock;
        long process, expiry, post, fire, nativeTick;
        int countdown;
    }
    private static final class Visit {
        UUID id;
        long firstTick, lastTick, expiry;
        int firstCountdown, lastCountdown;
        String name, theme;
        BlockPos post, fire;
        boolean ownedPostSeen, ownedFireSeen, expiredRemoved, oldPostRetired, oldFireRetired;
    }
    private GameplayTraderProbe() {}

    public static Map<String, Object> run(ServerPlayer player, String action) throws Exception {
        if (!Boolean.getBoolean("bc.pack_test.gameplay_world") || !"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode")))
            fail("requires bound Debug world feature probe");
        if (!player.serverLevel().dimension().equals(Level.OVERWORLD) || !player.isAlive() || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL)
            fail("requires actual living Overworld Survival anchor");
        if (action.equals("trader-resume")) return resume(player);
        if (action.startsWith("trader-funds")) {
            int total = switch (action) { case "trader-funds0" -> 0; case "trader-funds9" -> 9; case "trader-funds10" -> 10;
                default -> throw new IllegalArgumentException("unknown trader funds action"); };
            if (playerId != null) fail("cannot mutate funds after native observer arm");
            for (Entity entity : player.serverLevel().getAllEntities())
                if (entity instanceof WanderingTrader trader && trader.isAlive()
                        && trader.getPersistentData().getLong("better_spirit_commerce:visit_expiry") > 0
                        && trader.distanceToSqr(player) <= 256.0 * 256.0)
                    fail("ordinary visit appeared before threshold10 authored funds");
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (CurrencyIdentity.fromItemId(ForgeRegistries.ITEMS.getKey(stack.getItem())) != null)
                    player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
            if (total > 0) {
                var item = ForgeRegistries.ITEMS.getValue(CurrencyIdentity.IMPACT.itemId());
                if (item == null || !player.getInventory().add(new ItemStack(item, total))) fail("fixture funds could not fit");
            }
            player.inventoryMenu.broadcastChanges();
            return Map.of("action", action, "funds_origin", "authored_inventory_fixture_not_pickup_history",
                    "total_loose_currency", funds(player), "arrival_threshold", 10,
                    "ordinary_visits_before_authoring", 0, "native_tick", player.serverLevel().getGameTime());
        }
        if (action.equals("trader-arm")) {
            if (playerId != null || funds(player) < 10) fail("arm once after retaining threshold funds");
            playerId = player.getUUID(); armedTick = player.serverLevel().getGameTime();
            sample(player); return report(player);
        }
        if (!player.getUUID().equals(playerId)) fail("missing matching trader observer");
        sample(player);
        if (action.equals("trader-complete")) {
            if (visits.size() < 2) fail("two successive actual production ordinary visits not observed");
            Visit first = visits.values().iterator().next();
            if (!first.expiredRemoved || !first.oldPostRetired || !first.oldFireRetired || !first.ownedPostSeen || !first.ownedFireSeen)
                fail("first visit lacks natural expiry/removal/owned post-fire retirement");
            if (player.serverLevel().getGameTime() < first.expiry) fail("no actual elapsed native expiry");
            saveRestart(player);
            finished = true;
        } else if (!action.equals("trader-observe")) fail("unknown trader action");
        return report(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || playerId == null || finished) return;
        ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) sample(player);
    }

    private static void sample(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!level.dimension().equals(Level.OVERWORLD)) fail("anchor left ordinary visit dimension");
        if (funds(player) < 10) fail("threshold funds were lost during natural arrival soak");
        long now = level.getGameTime();
        int nearby = 0;
        List<BlockPos> activeFires = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof WanderingTrader trader) || !trader.isAlive()) continue;
            long expiry = trader.getPersistentData().getLong("better_spirit_commerce:visit_expiry");
            if (expiry <= 0) continue; // A fair/command-authored vanilla trader is not an ordinary visit witness.
            if (trader.distanceToSqr(player) <= 256.0 * 256.0) nearby++;
            CompoundTag camp = TraderCampData.forTrader(trader).save(new CompoundTag());
            if (!camp.hasUUID("AnchorOwner") || !playerId.equals(camp.getUUID("AnchorOwner"))) continue;
            Visit visit = visits.get(trader.getUUID());
            int countdown = trader.getDespawnDelay();
            if (visit == null) {
                if (expiry <= now || expiry - now > WanderingTraderVisits.VISIT_TICKS || countdown <= 0 || countdown > 12000)
                    fail("ordinary visit lacks finite native countdown");
                visit = new Visit(); visit.id = trader.getUUID(); visit.firstTick = now; visit.expiry = expiry;
                visit.firstCountdown = countdown; visit.lastCountdown = countdown;
                visit.name = trader.getDisplayName().getString();
                visit.theme = trader.getPersistentData().getString(WanderingTraderVisits.THEME_TAG);
                visits.put(visit.id, visit);
            }
            if (visit.expiry != expiry || countdown > visit.lastCountdown || camp.getLong("ExpiresAt") != expiry)
                fail("ordinary expiry/countdown reset");
            visit.lastTick = now; visit.lastCountdown = countdown;
            visit.post = BlockPos.of(camp.getLong("TargetPos"));
            if (level.getBlockEntity(visit.post) instanceof TraderCampPostBlockEntity post) {
                if (!visit.id.equals(post.traderId()) || post.saveForItem().getLong("VisitExpiry") != expiry)
                    fail("native post ownership/expiry mismatch");
                visit.ownedPostSeen = true;
            }
            if (camp.contains("FirePos")) {
                visit.fire = BlockPos.of(camp.getLong("FirePos")); activeFires.add(visit.fire);
                if (!level.getBlockState(visit.fire).isAir()) visit.ownedFireSeen = true;
            }
        }
        if (nearby > 1) fail("multiple ordinary visits inside native local cap256");
        for (Visit visit : visits.values()) {
            if (now < visit.expiry) continue;
            Entity old = level.getEntity(visit.id);
            if (old != null && old.isAlive()) fail("ordinary visit survived actual native expiry");
            visit.expiredRemoved = true;
            if (visit.post != null && level.hasChunkAt(visit.post)) {
                var post = level.getBlockEntity(visit.post);
                visit.oldPostRetired = !(post instanceof TraderCampPostBlockEntity owned && visit.id.equals(owned.traderId()));
            }
            if (visit.fire != null && level.hasChunkAt(visit.fire))
                visit.oldFireRetired = level.getBlockState(visit.fire).isAir() || activeFires.contains(visit.fire);
        }
    }

    private static Path restartPath(ServerPlayer player) {
        Path root = player.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path path = root.resolve("observed-trader-restart.json");
        safe(path); return path;
    }
    private static void saveRestart(ServerPlayer player) throws Exception {
        ServerLevel level = player.serverLevel();
        Visit active = null;
        for (Visit visit : visits.values()) {
            Entity entity = level.getEntity(visit.id);
            if (entity instanceof WanderingTrader trader && trader.isAlive() && visit.expiry > level.getGameTime()) active = visit;
        }
        if (active == null || active.post == null || active.fire == null || !active.ownedPostSeen || !active.ownedFireSeen)
            fail("active second visit lacks native camp for restart checkpoint");
        Path path = restartPath(player);
        if (Files.exists(path)) fail("restart checkpoint already exists");
        Restart saved = new Restart();
        saved.run = property("run_id"); saved.nonce = System.getProperty("bc.pack_test.gameplay_world.nonce", "");
        if (!UUID.fromString(saved.nonce).toString().equals(saved.nonce)) fail("invalid restart nonce");
        saved.player = player.getUUID().toString(); saved.world = path.getParent().toString();
        saved.clientHash = property("client_sha256"); saved.serverHash = property("server_sha256"); saved.supportHash = property("support_sha256");
        saved.process = ProcessHandle.current().pid(); saved.trader = active.id.toString(); saved.expiry = active.expiry;
        saved.nativeTick = level.getGameTime(); saved.post = active.post.asLong(); saved.fire = active.fire.asLong();
        saved.fireBlock = ForgeRegistries.BLOCKS.getKey(level.getBlockState(active.fire).getBlock()).toString();
        saved.countdown = ((WanderingTrader) level.getEntity(active.id)).getDespawnDelay();
        Path pending = path.resolveSibling("observed-trader-restart.json.next"); safe(pending);
        Files.writeString(pending, JSON.toJson(saved)); Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE);
    }
    private static Map<String, Object> resume(ServerPlayer player) throws Exception {
        Path path = restartPath(player);
        if (!Files.isRegularFile(path) || Files.size(path) > 8192) fail("missing native trader restart checkpoint");
        Restart saved = JSON.fromJson(Files.readString(path), Restart.class);
        if (!"bc.gameplay_trader_restart.v1".equals(saved.schema)
                || !property("run_id").equals(saved.run)
                || !System.getProperty("bc.pack_test.gameplay_world.nonce", "").equals(saved.nonce)
                || !player.getUUID().toString().equals(saved.player) || !path.getParent().toString().equals(saved.world)
                || !property("client_sha256").equals(saved.clientHash) || !property("server_sha256").equals(saved.serverHash)
                || !property("support_sha256").equals(saved.supportHash) || saved.process == ProcessHandle.current().pid())
            fail("trader restart binding mismatch or no actual process restart");
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        Entity entity = level.getEntity(UUID.fromString(saved.trader));
        if (!(entity instanceof WanderingTrader trader) || !trader.isAlive()) fail("saved ordinary trader absent after process restart");
        WanderingTrader trader = (WanderingTrader) entity;
        CompoundTag camp = TraderCampData.forTrader(trader).save(new CompoundTag());
        if (now < saved.nativeTick || now >= saved.expiry
                || trader.getPersistentData().getLong("better_spirit_commerce:visit_expiry") != saved.expiry
                || trader.getDespawnDelay() <= 0 || trader.getDespawnDelay() > saved.countdown
                || !camp.hasUUID("AnchorOwner") || !player.getUUID().equals(camp.getUUID("AnchorOwner"))
                || camp.getLong("ExpiresAt") != saved.expiry || camp.getLong("TargetPos") != saved.post
                || !camp.contains("FirePos") || camp.getLong("FirePos") != saved.fire)
            fail("same ordinary visit expiry/owner/camp/countdown did not survive restart");
        BlockPos postPos = BlockPos.of(saved.post), firePos = BlockPos.of(saved.fire);
        if (!(level.getBlockEntity(postPos) instanceof TraderCampPostBlockEntity post)
                || !trader.getUUID().equals(post.traderId()) || post.saveForItem().getLong("VisitExpiry") != saved.expiry
                || level.getBlockState(firePos).isAir()
                || !ForgeRegistries.BLOCKS.getKey(level.getBlockState(firePos).getBlock()).toString().equals(saved.fireBlock))
            fail("same owned native post/fire did not survive process restart");
        return Map.of("history_origin", "actual_server_process_restart", "same_trader_uuid", saved.trader,
                "immutable_expiry", saved.expiry, "before_native_tick", saved.nativeTick, "after_native_tick", now,
                "before_process", saved.process, "after_process", ProcessHandle.current().pid(),
                "same_owned_post_fire", true, "countdown_after", trader.getDespawnDelay());
    }
    private static String property(String key) {
        String value = System.getProperty("bc.pack_test.diagnostics." + key, "");
        if (value.isBlank() || (key.endsWith("sha256") && !value.matches("[a-f0-9]{64}"))) fail("missing restart binding " + key);
        return value;
    }
    private static void safe(Path path) {
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) fail("symlink trader checkpoint boundary");
    }

    private static int funds(ServerPlayer player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (CurrencyIdentity.fromItemId(ForgeRegistries.ITEMS.getKey(stack.getItem())) != null) total += stack.getCount();
        }
        return total;
    }
    private static Map<String, Object> report(ServerPlayer player) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Visit v : visits.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", v.id.toString()); row.put("name", v.name); row.put("theme", v.theme);
            row.put("first_native_tick", v.firstTick); row.put("last_native_tick", v.lastTick); row.put("immutable_expiry", v.expiry);
            row.put("first_countdown", v.firstCountdown); row.put("last_countdown", v.lastCountdown);
            row.put("owned_post_seen", v.ownedPostSeen); row.put("owned_fire_seen", v.ownedFireSeen);
            row.put("expired_removed", v.expiredRemoved); row.put("old_post_retired", v.oldPostRetired); row.put("old_fire_retired", v.oldFireRetired);
            row.put("post", v.post == null ? "unobserved" : v.post.toShortString());
            row.put("fire", v.fire == null ? "unobserved" : v.fire.toShortString()); rows.add(row);
        }
        return Map.of("history_origin", "native_production_spawner_observation", "armed_native_tick", armedTick,
                "current_native_tick", player.serverLevel().getGameTime(), "funds_origin", "authored_inventory_fixture_not_pickup_history",
                "loose_currency", funds(player), "visits", rows, "complete", finished,
                "selection_scope", "observed_actual_names_only_not_all_eight_weight_distribution_acceptance");
    }
    private static void fail(String reason) { throw new IllegalStateException("gameplay trader: " + reason); }
}
