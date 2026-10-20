package com.bettercontent.runtimetestsupport.gameplay;

import com.bettercontent.betterarenatrials.block.ArenaTotemBlockEntity;
import com.bettercontent.betterarenatrials.registry.ArenaBlocks;
import com.bettercontent.betterarenatrials.server.ArenaMatchService;
import com.bettercontent.betterdeathsdoor.RevivalManager;
import com.bettercontent.betterdeathsdoor.api.event.InjuryEvent;
import com.bettercontent.betterdeathsdoor.records.LifeMetric;
import com.bettercontent.betterdeathsdoor.records.LifeRecord;
import com.bettercontent.betterdeathsdoor.records.LifeRecords;
import com.bettercontent.betterdeathsdoor.records.LifeRecordsData;
import com.bettercontent.betterplayertraces.storage.DeathTraceSavedData;
import com.bettercontent.betterrpgprogression.common.data.StatsCap;
import com.bettercontent.betterspawns.respawn.PersonalRespawnPoint;
import com.bettercontent.betterspawns.respawn.PersonalRespawnService;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.registries.ForgeRegistries;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import top.theillusivec4.curios.api.CuriosApi;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Nonshipping, one-player authored native lifecycle. Never calls Clone/onRespawn itself and never
 * copies kit/player data into a replacement. A restoration failure is evidence, not repaired here. */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support")
public final class PlayerLifecycleInteractionProbe {
    private static final BlockPos TOTEM = new BlockPos(1_000_048, 316, 1_000_048);
    private static final BlockPos PERSONAL_SPAWN = new BlockPos(999_960, 316, 1_000_048);
    private static final long WALL_BUDGET_NANOS = 600_000_000_000L;
    private static final int TICK_BUDGET = 12_000;
    private static final String SNAPSHOT = "better_arena_trials:match_snapshot";
    private static Session session;
    private PlayerLifecycleInteractionProbe() {}

    private static final class Session {
        final MinecraftServer server;
        final UUID player;
        final ServerPlayer original;
        final Map<String, String> binding;
        final long startedNanos = System.nanoTime();
        final int startedTick;
        final List<Map<String, Object>> events = new ArrayList<>();
        final ListTag inventory;
        final CuriosSnapshot curios;
        final int xpLevel, xpTotal;
        final float xpProgress;
        final UUID life;
        final double[] metrics;
        final String recap;
        final String rpgBefore;
        final int poolsBefore;
        ServerPlayer replacement;
        String phase = "preparing";
        int lastSequence, clones, respawns, finals, killTick;
        boolean canceling, lateCanceled, ledgerAtClone;
        Session(ServerPlayer p, JsonObject input) {
            server = p.server; player = p.getUUID(); original = p;
            binding = binding(); startedTick = server.getTickCount();
            lastSequence = input.get("sequence").getAsInt();
            inventory = p.getInventory().save(new ListTag()).copy();
            curios = curiosSnapshot(p);
            check(!curios.backpack().isEmpty() && !curios.backpackUuid().isEmpty(),
                    "lifecycle requires actual equipped better_backpack[0] with an already native-initialized contents UUID");
            xpLevel = p.experienceLevel; xpTotal = p.totalExperience; xpProgress = p.experienceProgress;
            LifeRecord record = record(p);
            life = record.lifeId(); metrics = metrics(record); recap = recap(record);
            var stats = StatsCap.INSTANCE.get(p);
            check(stats != null, "native RPG capability missing"); rpgBefore = stats.serializeNBT().toString();
            poolsBefore = DeathTraceSavedData.Companion.get(p.serverLevel()).poolCount();
        }
    }

    /** Called only by the existing candidate-bound file dispatcher. Actions remain independently bound here. */
    public static Map<String, Object> run(ServerPlayer player, JsonObject input) {
        check(Boolean.getBoolean("bc.pack_test.gameplay_world"), "lifecycle world driver disabled");
        Map<String, String> binding = binding();
        check(player.connection != null && !(player instanceof FakePlayer), "real network player required");
        check(input.get("player").getAsString().equals(player.getUUID().toString()), "lifecycle UUID mismatch");
        check(binding.get("run").equals(input.get("run").getAsString())
                && binding.get("nonce").equals(input.get("nonce").getAsString()), "lifecycle run/nonce mismatch");
        check(binding.get("client_sha256").equals(input.get("client_sha256").getAsString())
                && binding.get("server_sha256").equals(input.get("server_sha256").getAsString()), "lifecycle candidate mismatch");
        String action = input.get("action").getAsString();
        if (action.equals("lifecycle-start")) {
            check(session == null, "lifecycle episode already exists; no replay");
            requireCore(player); check(player.isAlive(), "living lifecycle starter required");
            check(!ArenaMatchService.containsLifeRecordParticipant(player) && !LifeRecords.inArena(player), "foreign existing Arena membership");
            check(!player.getPersistentData().contains(SNAPSHOT), "foreign existing Arena snapshot");
            check(!RevivalManager.isFinalized(player), "starter is finalized");
            int slot = -1;
            for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).isEmpty()) { slot = i; break; }
            check(slot >= 0, "sentinel needs an empty native slot; no foreign stack is discarded");
            ItemStack sentinel = new ItemStack(Items.PAPER, 7);
            sentinel.setHoverName(Component.literal("Native lifecycle sentinel"));
            sentinel.getOrCreateTag().putString("bc_lifecycle_episode", binding.get("run") + ":" + input.get("sequence").getAsInt());
            player.getInventory().setItem(slot, sentinel);
            player.setExperienceLevels(17); // Authored native XP fixture, not earned progression.
            prepareWorld(player);
            session = new Session(player, input);
            player.teleportTo(player.serverLevel(), TOTEM.getX() + .5, TOTEM.getY(), TOTEM.getZ() + 2.5, 0, 0);
            var entity = player.serverLevel().getBlockEntity(TOTEM);
            check(entity instanceof ArenaTotemBlockEntity, "native totem missing");
            boolean accepted = ArenaMatchService.handleButton(player, (ArenaTotemBlockEntity) entity, 1);
            check(accepted && ArenaMatchService.containsLifeRecordParticipant(player), "native trial did not start");
            check(LifeRecords.inArena(player), "actual Arena -> Door typed eligibility bridge not registered/consumed");
            check(player.getPersistentData().contains(SNAPSHOT, 10), "native kit snapshot absent");
            check(!session.inventory.equals(player.getInventory().save(new ListTag())), "native loaned kit did not replace ordinary inventory");
            check(player.experienceLevel == 0, "native kit must stash ordinary XP");
            session.phase = "arena_active";
            addEvent("native_trial_start", player, Map.of("typed_door_bridge", true));
            return observe(player);
        }
        Session s = requireSession(player, input, binding);
        return switch (action) {
            case "lifecycle-kill" -> {
                requireCore(player);
                check(s.phase.equals("arena_active") && player == s.original && player.isAlive(), "wrong native death stage");
                check(player.server.getTickCount() - s.startedTick >= 2, "allow native collection ticks before death");
                check(LifeRecords.inArena(player), "Door lost native match membership before death");
                s.phase = "awaiting_native_respawn"; s.killTick = player.server.getTickCount();
                // Public native HP-damage/void bypass, not direct die/finalDeath or a forged Forge event.
                boolean accepted = player.hurt(player.damageSources().fellOutOfWorld(), 100_000f);
                check(accepted && !player.isAlive(), "native void damage did not actually kill real player");
                check(s.finals == 1 && RevivalManager.isFinalized(player), "exactly one actual confirmed FinalDeath required");
                assertLedger(player);
                addEvent("native_void_death_returned", player, Map.of("damage_accepted", accepted));
                yield observe(player);
            }
            case "lifecycle-observe" -> observe(player);
            case "lifecycle-cancel-negative" -> {
                requireCore(player);
                check(s.phase.equals("respawn_verified") && player == s.replacement && player.isAlive(), "cancellation negative must follow verified native respawn");
                yield cancelNegative(player);
            }
            case "lifecycle-finish" -> {
                requireCore(player);
                check(s.phase.equals("negative_verified"), "finish requires positive and negative lifecycle assertions");
                check(!ArenaMatchService.containsLifeRecordParticipant(player), "native match survived fixture");
                s.phase = "complete";
                // Keep observations in the result; the dedicated fixture owns authored platform/spawn/sentinel.
                yield observe(player);
            }
            default -> throw new IllegalStateException("unknown lifecycle action " + action);
        };
    }

    private static void prepareWorld(ServerPlayer player) {
        var level = player.serverLevel();
        check(level.getBlockState(TOTEM).isAir(), "would replace foreign totem fixture block");
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) {
            BlockPos feet = TOTEM.offset(dx, 0, dz);
            check(level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir(), "foreign block in authored trial headroom");
            check(level.getBlockState(feet.below()).isAir() || level.getBlockState(feet.below()).is(Blocks.GRASS_BLOCK), "foreign trial support");
            level.setBlockAndUpdate(feet.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        }
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos feet = PERSONAL_SPAWN.offset(dx, 0, dz);
            check(level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir(), "foreign native respawn headroom");
            check(level.getBlockState(feet.below()).isAir() || level.getBlockState(feet.below()).is(Blocks.GRASS_BLOCK), "foreign native respawn support");
            level.setBlockAndUpdate(feet.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        }
        level.setBlockAndUpdate(TOTEM, ArenaBlocks.ARENA_TOTEM.get().defaultBlockState());
        // Use actual owner API, not guessed persistent tags or direct event calls. This is an authored fixture spawn.
        var prepared = PersonalRespawnService.INSTANCE.setRespawnPoint(player,
                new PersonalRespawnPoint("minecraft:overworld", PERSONAL_SPAWN.getX(), PERSONAL_SPAWN.getY(), PERSONAL_SPAWN.getZ()));
        check(prepared.getPoint().getDim().equals("minecraft:overworld"), "native personal spawn changed dimension");
        player.setRespawnPosition(Level.OVERWORLD, PERSONAL_SPAWN, 0, true, false);
    }

    private static Map<String, Object> cancelNegative(ServerPlayer player) {
        Session s = session;
        int finalBefore = s.finals;
        int poolBefore = DeathTraceSavedData.Companion.get(player.serverLevel()).poolCount();
        CompoundTag heartsBefore = heartState(player);
        CuriosSnapshot curiosBefore = curiosSnapshot(player);
        int tickBefore = player.server.getTickCount();
        UUID lifeBefore = record(player).lifeId();
        float health = player.getHealth();
        class LateCancel {
            @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
            public void cancel(LivingDeathEvent event) {
                if (event.getEntity() != player) return;
                addEvent("late_lowest_cancel_before", player, Map.of("already_canceled", event.isCanceled()));
                event.setCanceled(true);
                player.setHealth(health); // Test cancellation also supplies its native health recovery, as a real saver must.
                s.lateCanceled = true;
            }
        }
        Object listener = new LateCancel();
        MinecraftForge.EVENT_BUS.register(listener);
        s.canceling = true;
        try {
            player.invulnerableTime = 0; // Explicit authored damage admission; not a hidden production immunity change.
            player.hurt(player.damageSources().fellOutOfWorld(), 100_000f);
        } finally {
            s.canceling = false;
            MinecraftForge.EVENT_BUS.unregister(listener);
        }
        boolean noFinal = s.finals == finalBefore && player.isAlive() && !RevivalManager.isFinalized(player);
        boolean noPools = poolBefore == DeathTraceSavedData.Companion.get(player.serverLevel()).poolCount();
        boolean noHearts = heartsBefore.equals(heartState(player));
        boolean sameLife = record(player).lifeId().equals(lifeBefore);
        CuriosSnapshot curiosAfter = curiosSnapshot(player);
        boolean noCuriosMutation = curiosBefore.slots().equals(curiosAfter.slots())
                && curiosBefore.backpackUuid().equals(curiosAfter.backpackUuid())
                && curiosBefore.contents().equals(curiosAfter.contents());
        int tickAfter = player.server.getTickCount();
        Map<String, Object> result = observe(player);
        Map<String, Object> negative = new LinkedHashMap<>(Map.of("listener_observed", s.lateCanceled, "no_final_death", noFinal,
                "no_new_trace_pool", noPools, "heart_entitlements_unchanged", noHearts, "same_life", sameLife,
                "pool_before", poolBefore, "pool_after", DeathTraceSavedData.Companion.get(player.serverLevel()).poolCount(),
                "heart_state_before", heartsBefore.toString(), "heart_state_after", heartState(player).toString()));
        negative.put("curios_slots_unchanged", noCuriosMutation);
        negative.put("curios_slots_before", curiosBefore.slots().toString());
        negative.put("curios_slots_after", curiosAfter.slots().toString());
        negative.put("curios_full_before", curiosBefore.full().toString());
        negative.put("curios_full_after", curiosAfter.full().toString());
        negative.put("curios_full_equal", curiosBefore.full().equals(curiosAfter.full()));
        negative.put("backpack_uuid_before", curiosBefore.backpackUuid());
        negative.put("backpack_uuid_after", curiosAfter.backpackUuid());
        negative.put("backpack_contents_before", curiosBefore.contents().toString());
        negative.put("backpack_contents_after", curiosAfter.contents().toString());
        negative.put("backpack_contents_unchanged", curiosBefore.contents().equals(curiosAfter.contents()));
        negative.put("before_tick", tickBefore); negative.put("after_tick", tickAfter);
        negative.put("same_native_tick", tickBefore == tickAfter);
        result.put("late_cancellation", negative);
        boolean passed = s.lateCanceled && noFinal && noPools && noHearts && sameLife && noCuriosMutation && tickBefore == tickAfter;
        result.put("ready", true); result.put("passed", passed);
        s.phase = passed ? "negative_verified" : "negative_failed";
        result.put("phase", s.phase);
        // Return the complete independent failures so the harness can retain evidence THEN fail; never silently repair them.
        return result;
    }

    private static Map<String, Object> observe(ServerPlayer player) {
        Session s = session;
        boolean replaced = s.replacement != null && s.replacement != s.original && player == s.replacement;
        boolean witnessed = replaced && s.clones == 1 && s.respawns == 1 && player.isAlive()
                && player.server.getPlayerList().getPlayer(s.player) == player && player.connection.player == player;
        boolean sameInventory = s.inventory.equals(player.getInventory().save(new ListTag()));
        CuriosSnapshot curiosNow = curiosSnapshot(player);
        boolean sameCurios = s.curios.slots().equals(curiosNow.slots())
                && s.curios.backpackUuid().equals(curiosNow.backpackUuid())
                && s.curios.contents().equals(curiosNow.contents());
        boolean sameXp = player.experienceLevel == s.xpLevel && player.totalExperience == s.xpTotal
                && Math.abs(player.experienceProgress - s.xpProgress) < 0.00001f;
        // Collection legitimately resumes after native return. Exact counters are asserted at death/Clone,
        // not several ordinary-world ticks later; imposing a permanent freeze would invent product semantics.
        boolean ledger = record(player).lifeId().equals(s.life) && recap(record(player)).equals(s.recap);
        boolean contained = ArenaMatchService.containsLifeRecordParticipant(player);
        boolean snapshotGone = !player.getPersistentData().contains(SNAPSHOT);
        boolean settled = witnessed && player.server.getTickCount() - s.killTick >= 3;
        if (s.phase.equals("awaiting_native_respawn") && settled) {
            check(s.finals == 1, "native replacement must follow exactly one accepted death");
            check(sameInventory && sameXp && snapshotGone, "native Arena replacement lost ordinary inventory/XP or leaked snapshot/kit");
            check(sameCurios, "native replacement changed Curios equipment/slot contents or equipped backpack NBT/UUID");
            check(ledger && s.ledgerAtClone && !contained && !LifeRecords.inArena(player), "native replacement did not preserve/release typed Door ledger containment");
            s.phase = "respawn_verified";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", s.phase);
        result.put("ready", s.phase.equals("arena_active") ? player.server.getTickCount() - s.startedTick >= 2
                : List.of("respawn_verified", "negative_verified", "complete").contains(s.phase));
        result.put("passed", List.of("respawn_verified", "negative_verified", "complete").contains(s.phase));
        result.put("candidate_binding", s.binding); result.put("player", s.player.toString());
        result.put("server_tick", player.server.getTickCount());
        result.put("old_player_identity", System.identityHashCode(s.original));
        result.put("current_player_identity", System.identityHashCode(player));
        result.put("replacement_witnessed", witnessed); result.put("native_clone_events", s.clones);
        result.put("native_respawn_events", s.respawns); result.put("confirmed_final_deaths", s.finals);
        result.put("native_events", List.copyOf(s.events));
        result.put("inventory_exact", sameInventory); result.put("xp_exact", sameXp);
        result.put("curios_slots_exact", sameCurios);
        result.put("curios_slots_before", s.curios.slots().toString()); result.put("curios_slots_now", curiosNow.slots().toString());
        result.put("curios_full_before", s.curios.full().toString()); result.put("curios_full_now", curiosNow.full().toString());
        result.put("curios_full_equal", s.curios.full().equals(curiosNow.full()));
        result.put("backpack_stack_before", s.curios.backpack().toString()); result.put("backpack_stack_now", curiosNow.backpack().toString());
        result.put("backpack_uuid_before", s.curios.backpackUuid()); result.put("backpack_uuid_now", curiosNow.backpackUuid());
        result.put("backpack_contents_before", s.curios.contents().toString()); result.put("backpack_contents_now", curiosNow.contents().toString());
        result.put("backpack_contents_exact", s.curios.contents().equals(curiosNow.contents()));
        result.put("curios_comparison_scope", "Strict: all native slot identifiers/counts, functional/cosmetic ItemStack.save NBT (including stack capabilities), equipped better_backpack[0] contents UUID and actual native wrapper inventory count/slot ItemStack.save vector (not merely root NBT/UUID). Full native writeTag retained but informational: modifier/cache/render synchronization lifetime is not item preservation. No native initialization, root repair, substitution or content writes by observer.");
        result.put("inventory_before", s.inventory.toString()); result.put("inventory_now", player.getInventory().save(new ListTag()).toString());
        result.put("xp_before", List.of(s.xpLevel, s.xpTotal, s.xpProgress));
        result.put("xp_now", List.of(player.experienceLevel, player.totalExperience, player.experienceProgress));
        result.put("arena_snapshot_absent", snapshotGone); result.put("native_arena_member", contained);
        result.put("door_typed_arena_member", LifeRecords.inArena(player)); result.put("door_life_and_recap_preserved", ledger);
        result.put("door_counters_exact_at_clone", s.ledgerAtClone);
        result.put("door_life_id_before", s.life.toString()); result.put("door_life_id_now", record(player).lifeId().toString());
        result.put("position", List.of(player.getX(), player.getY(), player.getZ()));
        result.put("arena_return_in_range", ArenaMatchService.nearTotem(player, TOTEM));
        result.put("return_precedence_verdict", "native final position observed; Arena versus personal spawn precedence has no settled policy assertion");
        result.put("trace_pool_delta", DeathTraceSavedData.Companion.get(player.serverLevel()).poolCount() - s.poolsBefore);
        var stats = StatsCap.INSTANCE.get(player);
        result.put("rpg_before", s.rpgBefore); result.put("rpg_now", stats == null ? "MISSING" : stats.serializeNBT().toString());
        result.put("rpg_arena_semantics", "RPG README death clears Life allocations; Arena Door bridge promises ledger containment only; no preservation rule fabricated");
        result.put("origin", "authored_native_trial_void_damage_client_respawn_packet; no direct Clone/Respawn callback or replacement-state repair");
        return result;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void cloned(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer next) || !observing(next)) return;
        Session s = session;
        check(event.getOriginal() == s.original && next != s.original && event.isWasDeath(), "not the real native death replacement");
        s.clones++; s.replacement = next; s.ledgerAtClone = ledgerEqual(next);
        addEvent("native_player_clone", next, Map.of("was_death", event.isWasDeath(), "distinct_java_identity", true));
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void respawned(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer next) || !observing(next)) return;
        Session s = session;
        check(next == s.replacement && next != s.original && !event.isEndConquered(), "respawn was not native death replacement");
        s.respawns++;
        addEvent("native_player_respawn", next, Map.of("end_conquered", event.isEndConquered()));
    }
    @SubscribeEvent
    public static void finalDeath(InjuryEvent.FinalDeath event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !observing(p)) return;
        session.finals++;
        addEvent("door_confirmed_final_death", p, Map.of("source", event.source().getMsgId()));
    }
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void deathEvent(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !observing(p)) return;
        addEvent("forge_lowest_death_observation", p, Map.of("canceled_at_observer", event.isCanceled(), "source", event.getSource().getMsgId()));
    }
    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || session == null || event.getServer() != session.server) return;
        // Independent wall/native tick budgets checked on every request; this observer never triggers respawn/repair.
        if (System.nanoTime() - session.startedNanos > WALL_BUDGET_NANOS
                || event.getServer().getTickCount() - session.startedTick > TICK_BUDGET) session.phase = "expired";
    }

    private static boolean observing(ServerPlayer p) {
        return session != null && !List.of("complete", "expired").contains(session.phase)
                && p.server == session.server && p.getUUID().equals(session.player);
    }
    private static Session requireSession(ServerPlayer p, JsonObject input, Map<String, String> binding) {
        Session s = session; check(s != null, "no armed lifecycle episode");
        check(s.server == p.server && s.player.equals(p.getUUID()) && s.binding.equals(binding), "lifecycle episode/candidate drift");
        check(System.nanoTime() - s.startedNanos <= WALL_BUDGET_NANOS && p.server.getTickCount() - s.startedTick <= TICK_BUDGET, "lifecycle deadline expired");
        int sequence = input.get("sequence").getAsInt();
        check(sequence > s.lastSequence, "replayed lifecycle action"); s.lastSequence = sequence;
        return s;
    }
    private static Map<String, String> binding() {
        check("debug".equals(System.getProperty("bc.pack_test.diagnostics.mode")), "lifecycle requires Debug fixture");
        Map<String, String> binding = new LinkedHashMap<>();
        String run = System.getProperty("bc.pack_test.diagnostics.run_id", "");
        String nonce = System.getProperty("bc.pack_test.gameplay_world.nonce", "");
        check(run.matches("[A-Za-z0-9_.-]{1,128}") && UUID.fromString(nonce).toString().equals(nonce), "invalid lifecycle binding");
        binding.put("run", run); binding.put("nonce", nonce);
        for (String kind : List.of("client", "server", "runtime", "support")) {
            String hash = System.getProperty("bc.pack_test.diagnostics." + kind + "_sha256", "");
            check(hash.matches("[a-f0-9]{64}"), "missing lifecycle artifact digest"); binding.put(kind + "_sha256", hash);
        }
        return Map.copyOf(binding);
    }
    private static void requireCore(ServerPlayer p) {
        check(p.serverLevel().dimension().equals(Level.OVERWORLD) && p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && Math.abs(p.getX() - 1_000_000) <= 128 && Math.abs(p.getZ() - 1_000_000) <= 128 && Math.abs(p.getY() - 316) <= 32,
                "lifecycle authored action outside bounded real Survival fixture");
    }
    private static LifeRecord record(ServerPlayer p) {
        LifeRecord record = LifeRecordsData.get(p.server).find(p.getUUID());
        check(record != null, "real native login did not establish Door ledger"); return record;
    }
    private static double[] metrics(LifeRecord record) {
        return java.util.Arrays.stream(LifeMetric.values()).mapToDouble(record::value).toArray();
    }
    private static String recap(LifeRecord record) { return record.latest() == null ? "none" : record.latest().toString(); }
    private static boolean ledgerEqual(ServerPlayer p) {
        LifeRecord record = record(p);
        return record.lifeId().equals(session.life) && java.util.Arrays.equals(metrics(record), session.metrics)
                && recap(record).equals(session.recap);
    }
    private static void assertLedger(ServerPlayer p) { check(ledgerEqual(p), "contained native death changed ordinary Door ledger"); }
    private record CuriosSnapshot(Tag full, CompoundTag slots, CompoundTag backpack, String backpackUuid, CompoundTag contents) {}
    private static CuriosSnapshot curiosSnapshot(ServerPlayer player) {
        var nativeInventory = CuriosApi.getCuriosInventory(player).resolve().orElseThrow(
                () -> new IllegalStateException("native Curios capability missing during lifecycle"));
        Tag full = nativeInventory.writeTag().copy();
        CompoundTag slots = new CompoundTag();
        for (var entry : new java.util.TreeMap<>(nativeInventory.getCurios()).entrySet()) {
            var handler = entry.getValue();
            CompoundTag values = new CompoundTag();
            values.putInt("functional_count", handler.getStacks().getSlots());
            values.putInt("cosmetic_count", handler.getCosmeticStacks().getSlots());
            ListTag functional = new ListTag(), cosmetic = new ListTag();
            for (int i = 0; i < handler.getStacks().getSlots(); i++) {
                CompoundTag slot = new CompoundTag(); slot.putInt("slot", i);
                slot.put("stack", handler.getStacks().getStackInSlot(i).save(new CompoundTag())); functional.add(slot);
            }
            for (int i = 0; i < handler.getCosmeticStacks().getSlots(); i++) {
                CompoundTag slot = new CompoundTag(); slot.putInt("slot", i);
                slot.put("stack", handler.getCosmeticStacks().getStackInSlot(i).save(new CompoundTag())); cosmetic.add(slot);
            }
            values.put("functional", functional); values.put("cosmetic", cosmetic); slots.put(entry.getKey(), values);
        }
        var backpackHandler = nativeInventory.getStacksHandler("better_backpack").orElseThrow(
                () -> new IllegalStateException("native dedicated backpack slot missing"));
        check(backpackHandler.getStacks().getSlots() == 1, "native dedicated backpack schema must contain exactly slot zero");
        ItemStack bag = backpackHandler.getStacks().getStackInSlot(0);
        CompoundTag bagTag = new CompoundTag(), contents = new CompoundTag(); String uuid = "";
        if (!bag.isEmpty()) {
            var id = ForgeRegistries.ITEMS.getKey(bag.getItem());
            check(id != null && id.getNamespace().equals("sophisticatedbackpacks") && id.getPath().endsWith("backpack"),
                    "foreign item in native dedicated backpack slot");
            var wrapper = bag.getCapability(CapabilityBackpackWrapper.getCapabilityInstance()).resolve().orElseThrow(
                    () -> new IllegalStateException("equipped native backpack has no wrapper capability"));
            uuid = wrapper.getContentsUuid().map(UUID::toString).orElse(""); // Read only; missing UUID is never repaired.
            if (!uuid.isEmpty()) {
                var storage = wrapper.getInventoryHandler(); // Native UUID-backed storage, not bag root NBT.
                contents.putInt("slot_count", storage.getSlots());
                ListTag vector = new ListTag();
                for (int i = 0; i < storage.getSlots(); i++) {
                    CompoundTag slot = new CompoundTag(); slot.putInt("slot", i);
                    slot.put("stack", storage.getStackInSlot(i).save(new CompoundTag())); vector.add(slot);
                }
                contents.put("slots", vector);
            }
            bagTag = bag.save(new CompoundTag());
        }
        return new CuriosSnapshot(full, slots, bagTag, uuid, contents);
    }
    private static CompoundTag heartState(ServerPlayer p) {
        CompoundTag copy = new CompoundTag();
        for (String key : List.of("better_rpg_progression_captured_heart_entitlement", "better_rpg_progression_pending_heart_fragment_entitlements",
                "better_rpg_progression_next_heart_entitlement_sequence", "better_rpg_progression_completed_heart_entitlement_through",
                "better_rpg_progression_unresolved_heart_entitlement")) {
            if (p.getPersistentData().contains(key)) copy.put(key, p.getPersistentData().get(key).copy());
        }
        return copy;
    }
    private static void addEvent(String kind, ServerPlayer p, Map<String, Object> details) {
        check(session.events.size() < 24, "lifecycle observation event budget exceeded");
        Map<String, Object> event = new LinkedHashMap<>(details);
        event.put("kind", kind); event.put("server_tick", p.server.getTickCount());
        event.put("ordinal", session.events.size()); event.put("player_identity", System.identityHashCode(p));
        session.events.add(Map.copyOf(event));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
