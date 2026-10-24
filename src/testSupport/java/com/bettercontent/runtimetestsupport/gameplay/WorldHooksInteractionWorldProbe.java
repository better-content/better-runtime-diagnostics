package com.bettercontent.runtimetestsupport.gameplay;

import com.bettercontent.betterburiedencounters.blockentity.SealedMonsterBlockEntity;
import com.bettercontent.betterregolithfarming.compat.RegolithFarmlandDefinitions;
import com.bettercontent.bettermalumdynamictrees.BlightLocusPolicy;
import com.bettercontent.betterwildfire.RemnantMemory;
import com.ferreusveritas.dynamictrees.worldgen.DynamicTreeFeature;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import com.bettercontent.runtimetestsupport.mixin.gameplay.ExplosionRadiusAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Nonshipping authored native-hook fixtures. No direct product handler calls, history/claim edits,
 * world-clock changes, sleep, reflection, process control or acceptance inferred from setup.
 *
 * Protocol: start; [tnt-ignite(case=0..4), observe until case_ready] x5; till; fire;
 * observe (includes scheduled fire state); tree-features(optional, may report BLOCKED); finish.
 * Client readiness/capture is separate. Finish certifies only the explicit assertions below, not
 * tree worldgen, automatic Malum bootstrap, actual restart or visual review.
 *
 * Integration MUST register mixin.gameplay.ExplosionRadiusAccess in the nonshipping mixin JSON.
 * Also register the four top-level observation mixins in mixin.gameplay; all five are read-only witnesses.
 * Accessor is read-only radius:F on native Explosion, not TNT NBT.
 */
public final class WorldHooksInteractionWorldProbe {
    public static final Set<String> ACTIONS = Set.of("world-hooks-start", "world-hooks-tnt-ignite",
            "world-hooks-observe", "world-hooks-till", "world-hooks-fire", "world-hooks-tree-features",
            "world-hooks-finish", "world-hooks-abort");
    public static final BlockPos BLAST = new BlockPos(999936, 316, 999936);
    public static final BlockPos WITHDRAWN = new BlockPos(1000064, 316, 1000064);
    public static final BlockPos TILL = new BlockPos(1000024, 316, 999936);
    public static final BlockPos FIRE = new BlockPos(1000064, 316, 999936);
    private static final BlockPos TREE = new BlockPos(999936, 316, 1000064);
    private static final String[] TNT_IDS = {"scalable_tnt:low_yield_tnt", "scalable_tnt:high_yield_tnt",
            "minecraft:tnt", "scalable_tnt:low_yield_tnt", "scalable_tnt:low_yield_tnt"};
    private static final float[] POWERS = {3, 4.5f, 4, 3, 3};
    private static volatile Session session;
    private static final ThreadLocal<ServerLevel> NATIVE_TRACKER_TICK = new ThreadLocal<>();
    private static boolean registered;
    private WorldHooksInteractionWorldProbe() {}

    // Public bridges are required: injected methods run in the product target, not in this
    // probe class. They never invoke/mutate product logic or construct Forge events.
    public static void recordNativeWatch(ServerLevel level, BlockPos pos, ResourceLocation id) {
        Session s = session; Blast b = s == null ? null : s.blast;
        if (b != null && controlsActive() && level == s.level && pos.equals(b.sealedPos)) {
            synchronized (b) { b.watchCalls++; }
        }
    }
    public static void beginNativeTrackerTick(ServerLevel level) {
        if (controlsActive()) {
            NATIVE_TRACKER_TICK.set(level);
            Session s=session; Blast b=s.blast;
            if(b!=null && level==s.level && b.eventTick>=0) b.nativeTrackerTicks++;
        } else NATIVE_TRACKER_TICK.remove();
    }
    public static void endNativeTrackerTick() { NATIVE_TRACKER_TICK.remove(); }
    public static void recordNativeSpawnerCall(Level level, BlockPos pos, ResourceLocation id) {
        Session s = session; Blast b = s == null ? null : s.blast;
        if (b != null && controlsActive() && level == s.level && pos.equals(b.sealedPos)) {
            synchronized (b) {
                if (NATIVE_TRACKER_TICK.get() == level) b.deferredSpawnCalls++;
                else b.callbackSpawnCalls++;
            }
        }
    }

    private static final ThreadLocal<Explosion> NATIVE_ENGINE = new ThreadLocal<>();
    public static void beginNativeEngine(ServerLevel level, Explosion explosion) {
        if(controlsActive() && matching(level,explosion)!=null && level.getServer().isSameThread()) NATIVE_ENGINE.set(explosion);
        else NATIVE_ENGINE.remove();
    }
    public static void recordNativeEngineReturn(ServerLevel level, Explosion explosion, List<BlockPos> positions) {
        try {
            Blast b=matching(level,explosion);
            if(b!=null && controlsActive() && level.getServer().isSameThread() && NATIVE_ENGINE.get()==explosion) {
                b.engineReturns++;b.finalEngineContainsSealed|=positions.contains(b.sealedPos);
            }
        } finally { NATIVE_ENGINE.remove(); }
    }
    public static void recordNativeCraterReturn(ServerLevel level, Vec3 center, float power, Set<BlockPos> positions) {
        Explosion producer=NATIVE_ENGINE.get();Blast b=producer==null?null:matching(level,producer);
        if(b!=null && controlsActive() && level.getServer().isSameThread() && b.explosionCenter!=null
                && center.distanceToSqr(b.explosionCenter)<1e-8) {
            if(positions==null || !Float.isFinite(power) || power<=0) { fail(new IllegalStateException("invalid native crater observation"));return; }
            b.craterReturns++;b.craterContainsSealed|=positions.contains(b.sealedPos);
            b.finalEnginePower=Float.isFinite(b.finalEnginePower)?Math.max(b.finalEnginePower,power):power;
        }
    }

    private static final class Blast {
        final int index, startedTick;
        final long startedNanos = System.nanoTime();
        final UUID tnt;
        final BlockPos sealedPos;
        final net.minecraft.world.level.block.entity.BlockEntity sealedEntity;
        final CompoundTag sealedBefore;
        Vec3 explosionCenter;
        int engineReturns, craterReturns, negativeLoadedTicks, nativeTrackerTicks;
        float finalEnginePower = Float.NaN;
        boolean authoredCandidateAdded, finalEngineContainsSealed, craterContainsSealed;
        final Set<UUID> candidateReleases = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<UUID> released = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile int starts, detonates, eventTick = -1, affectedCount, watchCalls, deferredSpawnCalls, callbackSpawnCalls;
        volatile boolean canceled, sealedAffected, filterApplied;
        volatile float radius = Float.NaN;
        Blast(int index, int tick, UUID tnt, BlockPos sealedPos, ServerLevel level) {
            this.index = index; startedTick = tick; this.tnt = tnt; this.sealedPos = sealedPos.immutable();
            sealedEntity = level.getBlockEntity(sealedPos); sealedBefore = sealedEntity.saveWithFullMetadata().copy();
        }
    }
    private static final class Session {
        final MinecraftServer server;
        final ServerLevel level;
        final UUID owner;
        final Map<String, String> binding;
        final String family;
        final Map<String,Object> eoConfig;
        final long startedNanos = System.nanoTime();
        final int startedTick;
        final List<Map<String, Object>> blasts = new ArrayList<>();
        final List<Map<String, Object>> till = new ArrayList<>();
        final List<Map<String, Object>> fire = new ArrayList<>();
        final List<Map<String, Object>> chunkLoads = new java.util.concurrent.CopyOnWriteArrayList<>();
        int sequence, actions, nextCase;
        volatile Blast blast;
        volatile String failure = "", phase = "active";
        boolean cancelTill, tillCancelObserved, firePrepared, treeAttempted;
        net.minecraft.world.level.block.entity.BlockEntity protectedNest;
        ChestBlockEntity protectedChest;
        Session(ServerPlayer p, JsonObject input, Map<String, String> binding) {
            server = p.server; level = p.serverLevel(); owner = p.getUUID(); this.binding = binding;
            startedTick = server.getTickCount(); sequence = integer(input, "sequence");
            family = input.has("family") ? text(input,"family") : "all";
            check(Set.of("all","tnt-buried","regolith","fire").contains(family),"unknown closed world-hooks family");
            eoConfig = family("tnt-buried") ? eoConfiguration() : Map.of("applicability","NOT_EVALUATED_FOR_THIS_FAMILY");
            if(family("tnt-buried")) check(eoConfig.get("config_sha256").equals(text(input,"eo_config_sha256")),"selected candidate EO config identity mismatch");
        }
        boolean family(String name) {return family.equals("all") || family.equals(name);}
    }

    public static Map<String, Object> run(ServerPlayer player, JsonObject input) {
        check(Boolean.getBoolean("bc.pack_test.gameplay_world"), "world-hooks driver disabled");
        check(player.server.isSameThread() && player.connection != null && !(player instanceof FakePlayer), "native real server player required");
        requireCore(player);
        Map<String, String> binding = binding(input, "gameplay_world");
        check(player.getUUID().toString().equals(text(input, "player")), "world-hooks owner mismatch");
        String action = text(input, "action");
        check(ACTIONS.contains(action), "unknown world-hooks action");
        if (action.equals("world-hooks-start")) {
            check(session == null, "world-hooks session cannot replay in same JVM");
            session = new Session(player, input, binding);
            if (!registered) { MinecraftForge.EVENT_BUS.register(Events.class); registered = true; }
            try {
                if(session.family("tnt-buried")) { preparePad(session.level, BLAST, 7); preparePad(session.level, WITHDRAWN, 1); }
                if(session.family("regolith")) preparePad(session.level, TILL, 2);
                if(session.family("fire")) preparePad(session.level, FIRE, 10);
            } catch (RuntimeException failure) { session.failure = failure.toString(); throw failure; }
            return observe();
        }
        Session s = session;
        check(s != null && s.server == player.server && s.level == player.serverLevel()
                && s.owner.equals(player.getUUID()) && s.binding.equals(binding), "world-hooks session/candidate drift");
        int sequence = integer(input, "sequence");
        check(sequence > s.sequence && ++s.actions <= 512, "world-hooks replay/action budget");
        s.sequence = sequence;
        if (action.equals("world-hooks-abort")) {
            s.cancelTill = false; s.phase = "aborted"; unregister(); return observe();
        }
        check(s.phase.equals("active"), "world-hooks session closed");
        check(s.failure.isEmpty(), s.failure);
        budget(s);
        switch (action) {
            case "world-hooks-tnt-ignite" -> ignite(player, integer(input, "case"));
            case "world-hooks-till" -> till(player);
            case "world-hooks-fire" -> fire(player);
            case "world-hooks-tree-features" -> { return treeFeatures(player); }
            case "world-hooks-finish" -> {
                if(s.family("tnt-buried")) {
                    check(s.blasts.size() == 5 && s.nextCase == 5 && s.blast == null, "five native TNT cases incomplete");
                    check(s.eoConfig.equals(eoConfiguration()),"effective EO configuration drift");
                    if(Boolean.TRUE.equals(s.eoConfig.get("deferred_required")))
                        check(s.blasts.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("deferred_boundary_exercised"))),"active deferred composition was not exercised");
                }
                if(s.family("regolith")) check(s.till.size() == RegolithFarmlandDefinitions.entries().size() * 2 * 5, "regolith action matrix incomplete");
                if(s.family("fire")) { check(s.firePrepared, "native wildfire fixture incomplete"); assertFire(); }
                s.phase = "complete_explicit_hooks_only"; unregister();
            }
            case "world-hooks-observe" -> { }
            default -> throw new IllegalStateException("invalid world-hooks phase action");
        }
        check(s.failure.isEmpty(), s.failure);
        return observe();
    }

    private static void ignite(ServerPlayer p, int index) {
        Session s = session;
        check(s.family("tnt-buried") && s.blast == null && index == s.nextCase && index >= 0 && index < 5, "TNT case family/order/in-flight mismatch");
        check(s.eoConfig.equals(eoConfiguration()),"effective EO configuration drift before native producer");
        // Every case starts with identical authored geometry. No entity budget/history/claim mutation.
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            s.level.setBlockAndUpdate(BLAST.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        }
        BlockPos sealedPos = index == 4 ? WITHDRAWN : BLAST.east(2);
        s.level.setBlockAndUpdate(sealedPos, block("better_buried_encounters:sealed_monster").defaultBlockState());
        check(s.level.getBlockEntity(sealedPos) instanceof SealedMonsterBlockEntity, "native sealed block entity absent");
        ((SealedMonsterBlockEntity) s.level.getBlockEntity(sealedPos)).setEntityId(new ResourceLocation("minecraft", "silverfish"));
        s.level.setBlockAndUpdate(BLAST, block(TNT_IDS[index]).defaultBlockState());
        ItemStack previous = p.getMainHandItem();
        double x = p.getX(), y = p.getY(), z = p.getZ(); float yaw = p.getYRot(), pitch = p.getXRot();
        try {
            p.teleportTo(s.level, BLAST.getX() + .5, 316, BLAST.getZ() - 3.5, 0, 0);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
            ItemStack tool = p.getMainHandItem();
            var outcome = p.gameMode.useItemOn(p, s.level, tool, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(BLAST), Direction.NORTH, BLAST, false));
            check(outcome.consumesAction() && tool.getDamageValue() == 1 && ItemStack.matches(tool, p.getMainHandItem())
                    && s.level.getBlockState(BLAST).isAir(), "native TNT ignition/cost failed");
            List<PrimedTnt> tnt = s.level.getEntitiesOfClass(PrimedTnt.class, new AABB(BLAST).inflate(1.5));
            check(tnt.size() == 1 && tnt.get(0).getOwner() == p && tnt.get(0).getFuse() > 0, "exactly one owned native primed TNT required");
            s.blast = new Blast(index, s.server.getTickCount(), tnt.get(0).getUUID(), sealedPos, s.level);
            // Public ignition, native fuse and native explode run unmodified after this action returns.
        } finally {
            p.setItemInHand(InteractionHand.MAIN_HAND, previous);
            p.teleportTo(s.level, x, y, z, yaw, pitch);
        }
        // Move the real player to a bounded safe observation point; parent fixture owns protection.
        p.teleportTo(s.level, 999980.5, 316, 999936.5, 90, 0);
    }

    private static void tickBlast() {
        Session s = session; Blast b = s.blast;
        if (b == null) return;
        int now = s.server.getTickCount();
        check(s.level.hasChunkAt(b.sealedPos),"actual watched sealed chunk unloaded during native case");
        if(b.index==4) {
            b.negativeLoadedTicks++;
            check(s.level.getBlockEntity(b.sealedPos)==b.sealedEntity
                    && b.sealedBefore.equals(b.sealedEntity.saveWithFullMetadata()),"controlled withdrawn sealed block/NBT changed");
        }
        for (UUID id : b.candidateReleases) {
            Entity entity = s.level.getEntity(id);
            if (entity instanceof Silverfish && !entity.isRemoved()) b.released.add(id);
        }
        check(now - b.startedTick <= 1800 && System.nanoTime() - b.startedNanos <= 120_000_000_000L, "native TNT/async-removal deadline exceeded");
        // Keep the non-removing watched block through more than the source tracker's1200-tick
        // expiry interval; this is actual elapsed native ticks, not a manually called tracker.
        if (b.eventTick < 0 || now - b.eventTick < (b.index == 4 ? 1220 : 40)) return;
        boolean removed = !s.level.getBlockState(b.sealedPos).is(block("better_buried_encounters:sealed_monster"));
        if (b.index < 3 && !removed) return; // Real asynchronous engine may still own its removal work.
        if (b.index < 3 && b.released.isEmpty()) return;
        check(b.starts == 1, "TNT explosion entry missing/duplicated");
        check(Float.compare(b.radius, POWERS[b.index]) == 0, "actual native explosion radius drift " + b.radius);
        if (b.index == 3) {
            check(b.canceled && b.detonates == 0 && b.watchCalls == 0 && !removed && b.released.isEmpty(), "canceled native TNT released/removed sealed block");
        } else if (b.index == 4) {
            check(!Boolean.TRUE.equals(s.eoConfig.get("async_crater")),"async negative requires actual owned native-job completion proof; handler return alone is insufficient");
            check(b.detonates == 1 && b.authoredCandidateAdded && b.filterApplied && b.watchCalls == 1 && !b.sealedAffected
                    && b.engineReturns == 1 && !b.finalEngineContainsSealed && b.craterReturns >= 1 && !b.craterContainsSealed
                    && Float.isFinite(b.finalEnginePower) && b.finalEnginePower > 0
                    && b.finalEnginePower <= ((Number)s.eoConfig.get("max_cluster_power")).floatValue()
                    && b.negativeLoadedTicks >= 1220 && b.nativeTrackerTicks >= 1220
                    && b.deferredSpawnCalls == 0 && b.callbackSpawnCalls == 0 && !removed && b.released.isEmpty(), "controlled watched-withdrawn native candidate/TTL negative failed");
        } else {
            check(b.detonates == 1 && b.watchCalls == 1 && b.sealedAffected && removed && b.released.size() == 1
                    && b.deferredSpawnCalls + b.callbackSpawnCalls == 1, "successful sealed TNT release not exactly once");
        }
        Map<String, Object> row = blastRow(b); row.put("settled_ticks", now - b.eventTick);
        row.put("sealed_removed", removed); row.put("state", "ASSERTED_NATIVE_CASE");
        s.blasts.add(Map.copyOf(row)); s.nextCase++; s.blast = null;
        // Own recorded fixture mobs only; never clear unrelated entities.
        for (UUID id : b.released) { Entity e = s.level.getEntity(id); if (e != null) e.discard(); }
    }

    private static void till(ServerPlayer p) {
        Session s = session;
        check(s.family("regolith") && s.blast == null && (s.family.equals("regolith") || s.nextCase == 5) && s.till.isEmpty(), "tilling family/order/replay mismatch");
        ItemStack previous = p.getMainHandItem();
        double x = p.getX(), y = p.getY(), z = p.getZ(); float yaw = p.getYRot(), pitch = p.getXRot();
        try {
            p.teleportTo(s.level, TILL.getX() + 2.5, 316, TILL.getZ() + .5, 90, 0);
            for (var entry : RegolithFarmlandDefinitions.entries()) {
                for (ResourceLocation source : List.of(entry.grassyRegolithId(), entry.plainRegolithId())) {
                    for (String mode : List.of("positive", "down-face", "blocked", "nonhoe", "canceled")) {
                        BlockState original = block(source.toString()).defaultBlockState();
                        s.level.setBlockAndUpdate(TILL, original);
                        s.level.setBlockAndUpdate(TILL.above(), mode.equals("blocked") ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(mode.equals("nonhoe") ? Items.PAPER : Items.DIAMOND_HOE));
                        ItemStack tool = p.getMainHandItem();
                        s.cancelTill = mode.equals("canceled"); s.tillCancelObserved = false;
                        var outcome = p.gameMode.useItemOn(p, s.level, tool, InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(TILL), mode.equals("down-face") ? Direction.DOWN : Direction.UP, TILL, false));
                        s.cancelTill = false;
                        BlockState after = s.level.getBlockState(TILL);
                        boolean positive = mode.equals("positive");
                        check(positive ? after.is(block(entry.farmlandId().toString())) : after.equals(original), "regolith native action state drift " + source + ":" + mode);
                        check(tool.getDamageValue() == (positive ? 1 : 0) && tool.getCount() == 1
                                && ItemStack.matches(tool, p.getMainHandItem()), "regolith exact tool cost drift " + mode);
                        check(!mode.equals("canceled") || s.tillCancelObserved, "native canceled-use negative not observed");
                        s.till.add(Map.of("source", source.toString(), "case", mode, "result", outcome.toString(),
                                "after", after.toString(), "tool_damage", tool.getDamageValue(), "native_event_cancel_observed", s.tillCancelObserved));
                    }
                }
            }
        } finally {
            s.cancelTill = false; s.level.setBlockAndUpdate(TILL.above(), Blocks.AIR.defaultBlockState());
            p.setItemInHand(InteractionHand.MAIN_HAND, previous); p.teleportTo(s.level, x, y, z, yaw, pitch);
        }
    }

    private static void fire(ServerPlayer p) {
        Session s = session;
        check(s.family("fire") && s.blast == null && (s.family.equals("fire") || s.nextCase == 5) && !s.firePrepared, "wildfire family/order/replay mismatch");
        BlockState[] sources = {Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
                Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true)};
        for (int i = 0; i < sources.length; i++) {
            BlockPos target = FIRE.offset(i * 6 - 6, 0, 0);
            s.level.setBlockAndUpdate(target, sources[i]);
            int drops = s.level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(2)).size();
            nativeFire(s.level, target);
            check(s.level.getBlockEntity(target) instanceof RemnantMemory, "native FireBlock consumption did not create remnant " + target);
            RemnantMemory memory = (RemnantMemory) s.level.getBlockEntity(target);
            check(memory.source().equals(sources[i]), "native fire lost source state");
            check(s.level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(2)).size() == drops, "native char conversion emitted original destruction loot");
            CompoundTag disk = memory.saveWithFullMetadata();
            s.fire.add(Map.of("position", xyz(target), "source", sources[i].toString(), "remnant", s.level.getBlockState(target).toString(),
                    "native_disk_nbt", disk.toString(), "update_tag", memory.getUpdateTag().toString(), "server_tick", s.server.getTickCount()));
        }
        BlockPos container = FIRE.south(6);
        s.level.setBlockAndUpdate(container, Blocks.CHEST.defaultBlockState());
        check(s.level.getBlockEntity(container) instanceof ChestBlockEntity, "authored native chest absent");
        ChestBlockEntity chest = (ChestBlockEntity) s.level.getBlockEntity(container);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        nativeFire(s.level, container);
        check(s.level.getBlockEntity(container) == chest && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 7, "wildfire consumed native container contents");
        s.protectedChest = chest;
        // A chest is ordinarily nonflammable. Include a genuinely flammable block-entity source
        // so the protection assertion would fail if the native consumption hook disappeared.
        BlockPos nestPos = FIRE.offset(6, 0, 6);
        s.level.setBlockAndUpdate(nestPos, Blocks.BEE_NEST.defaultBlockState());
        s.protectedNest = s.level.getBlockEntity(nestPos);
        check(s.protectedNest != null && s.level.getBlockState(nestPos).getFlammability(s.level, nestPos, Direction.WEST) > 0,
                "flammable native block-entity positive control invalid");
        s.protectedNest.getPersistentData().putString("bc_world_hooks_fixture", s.binding.get("run") + ":" + s.owner);
        nativeFire(s.level, nestPos);
        check(s.level.getBlockEntity(nestPos) == s.protectedNest && s.level.getBlockState(nestPos).is(Blocks.BEE_NEST)
                && s.protectedNest.getPersistentData().getString("bc_world_hooks_fixture").equals(s.binding.get("run") + ":" + s.owner),
                "native fire consumed genuinely flammable block entity");
        s.firePrepared = true;
        p.teleportTo(s.level, FIRE.getX() + .5, 318, FIRE.getZ() - 8.5, 0, 18);
    }
    private static void nativeFire(ServerLevel level, BlockPos target) {
        BlockPos fire = target.west();
        level.setBlockAndUpdate(fire.below(), Blocks.NETHERRACK.defaultBlockState());
        level.setBlockAndUpdate(fire, Blocks.FIRE.defaultBlockState());
        // Deliberately authored deterministic native tick RNG, NOT naturally observed spread/rate.
        ((FireBlock) Blocks.FIRE).tick(Blocks.FIRE.defaultBlockState(), level, fire,
                new net.minecraft.world.level.levelgen.LegacyRandomSource(0) { @Override public int nextInt(int bound) { return 0; } });
    }
    private static void assertFire() {
        check(session.level.getBlockEntity(FIRE.south(6)) == session.protectedChest
                && session.protectedChest.getItem(0).is(Items.DIAMOND) && session.protectedChest.getItem(0).getCount() == 7,
                "native protected chest/contents changed before finish");
        check(session.level.getBlockEntity(FIRE.offset(6, 0, 6)) == session.protectedNest
                && session.protectedNest.getPersistentData().getString("bc_world_hooks_fixture").equals(session.binding.get("run") + ":" + session.owner),
                "native flammable protected block entity changed before finish");
        for (Map<String, Object> row : session.fire) {
            @SuppressWarnings("unchecked") List<Integer> pos = (List<Integer>) row.get("position");
            BlockPos at = new BlockPos(pos.get(0), pos.get(1), pos.get(2));
            check(session.level.getBlockEntity(at) instanceof RemnantMemory, "remnant disappeared before finish");
            check(((RemnantMemory) session.level.getBlockEntity(at)).source().toString().equals(row.get("source")), "remnant source changed before finish");
            BlockState state = session.level.getBlockState(at);
            check(state.hasProperty(com.bettercontent.betterwildfire.RemnantBlock.SMOLDERING)
                    && !state.getValue(com.bettercontent.betterwildfire.RemnantBlock.SMOLDERING), "native scheduled smolder finish not yet observed");
        }
    }

    private static Map<String, Object> treeFeatures(ServerPlayer p) {
        Session s = session; check(s.blast == null && !s.treeAttempted, "tree fixture in-flight/replay"); s.treeAttempted = true;
        List<Map<String, Object>> sites = new ArrayList<>();
        for (int cx = (1000000 - 112) >> 4; cx <= (1000000 + 112) >> 4; cx++) {
            for (int cz = (1000000 - 112) >> 4; cz <= (1000000 + 112) >> 4; cz++) {
                final int x = cx, z = cz;
                BlightLocusPolicy.siteForChunk(s.level.getSeed(), cx, cz).ifPresent(site -> sites.add(Map.of(
                        "chunk_x", x, "chunk_z", z, "cell_key", site.key(), "already_loaded", s.level.getChunkSource().getChunkNow(x, z) != null)));
            }
        }
        // Actual native configured-feature execution on an authored loaded-biome platform. No forced
        // species, injected root, direct district selector call or claimed-locus/finalization mutation.
        preparePad(s.level, TREE, 12);
        var features = s.level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        List<Map<String, Object>> attempted = new ArrayList<>();
        var candidates = features.keySet().stream().filter(id -> features.get(id) != null
                && features.get(id).feature() instanceof DynamicTreeFeature).sorted().toList();
        // Do not silently select a different feature or execute an unbounded set of variants.
        if (candidates.size() == 1) {
            var id = candidates.get(0);
            boolean placed = features.get(id).place(s.level, s.level.getChunkSource().getGenerator(), RandomSource.create(62417), TREE);
            attempted.add(Map.of("configured_feature", id.toString(), "native_return", placed));
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (int cx = (TREE.getX() - 32) >> 4; cx <= (TREE.getX() + 32) >> 4; cx++) for (int cz = (TREE.getZ() - 32) >> 4; cz <= (TREE.getZ() + 32) >> 4; cz++) {
            var chunk = s.level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            for (var be : chunk.getBlockEntities().values()) {
                if (be instanceof com.ferreusveritas.dynamictrees.block.entity.SpeciesBlockEntity
                        && be.getBlockPos().distSqr(TREE) <= 32 * 32) {
                    roots.add(Map.of("position", xyz(be.getBlockPos()), "native_nbt", be.saveWithFullMetadata().toString()));
                }
            }
        }
        Map<String, Object> result = observe();
        result.put("tree_fixture_scope", "actual native loaded-biome configured feature on authored ground, NOT automatic new-chunk generation or natural rate");
        result.put("native_feature_candidates", candidates.stream().map(ResourceLocation::toString).toList());
        result.put("native_feature_attempts", attempted); result.put("nearby_native_root_observations", roots);
        result.put("malum_source_selected_core_sites", sites);
        result.put("tree_acceptance", "UNCOVERED: root observations do not alone identify new target species or establish biome/district selector branch");
        result.put("malum_bootstrap_acceptance", "UNCOVERED: real eligible new-chunk load/admission and durable claim not established; never fabricate claim or invoke bootstrap handler");
        return result;
    }

    public static final class Events {
        private Events() {}
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void cancelStart(ExplosionEvent.Start event) {
            Blast b = matching(event.getLevel(), event.getExplosion());
            if (b != null && b.index == 3 && controlsActive()) event.setCanceled(true);
        }
        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void start(ExplosionEvent.Start event) {
            Blast b = matching(event.getLevel(), event.getExplosion()); if (b == null) return;
            try {
                check(event.getExplosion() instanceof ExplosionRadiusAccess, "nonshipping read-only Explosion radius accessor not registered");
                b.starts++; b.radius = ((ExplosionRadiusAccess) event.getExplosion()).worldHooks$radius();
                b.explosionCenter = event.getExplosion().getPosition();
                b.canceled = event.isCanceled(); b.eventTick = session.server.getTickCount();
            } catch (RuntimeException failure) { fail(failure); }
        }
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void authoredWithdrawalCandidate(ExplosionEvent.Detonate event) {
            Blast b=matching(event.getLevel(),event.getExplosion());
            if(b==null || b.index!=4 || !controlsActive()) return;
            try {
                check(!event.getAffectedBlocks().contains(b.sealedPos),"far authored negative unexpectedly in original affected list");
                check(session.level.hasChunkAt(b.sealedPos),"withdrawn candidate must remain actually loaded");
                event.getAffectedBlocks().add(b.sealedPos);b.authoredCandidateAdded=true;
            } catch(RuntimeException failure) { fail(failure); }
        }
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void detonate(ExplosionEvent.Detonate event) {
            Blast b = matching(event.getLevel(), event.getExplosion()); if (b == null) return;
            synchronized (b) {
                // Explicit controlled fault: HIGH adds only our FAR owned sealed position,
                // real Buried NORMAL watches it, LOW withdraws it. NOT a natural blast shape
                // or a veto against EO recomputation: actual engine candidate absence is observed.
                if (b.index == 4 && controlsActive()) b.filterApplied = event.getAffectedBlocks().removeIf(b.sealedPos::equals);
                b.detonates++; b.sealedAffected = event.getAffectedBlocks().contains(b.sealedPos);
                b.affectedCount = event.getAffectedBlocks().size(); b.eventTick = session.server.getTickCount();
            }
        }
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void joined(EntityJoinLevelEvent event) {
            Session s = session; if (s == null || s.blast == null || !controlsActive() || event.getLevel() != s.level) return;
            if (event.getEntity() instanceof Silverfish && event.getEntity().position().distanceToSqr(Vec3.atCenterOf(s.blast.sealedPos)) <= 64) {
                s.blast.candidateReleases.add(event.getEntity().getUUID());
            }
        }
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void cancelTill(PlayerInteractEvent.RightClickBlock event) {
            Session s = session;
            if (s != null && s.cancelTill && controlsActive() && event.getLevel() == s.level
                    && event.getEntity().getUUID().equals(s.owner) && event.getPos().equals(TILL)) {
                event.setCanceled(true); s.tillCancelObserved = true;
            }
        }
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void chunk(ChunkEvent.Load event) {
            Session s = session; if (s == null || !controlsActive() || event.getLevel() != s.level || s.chunkLoads.size() >= 32) return;
            int x = event.getChunk().getPos().x, z = event.getChunk().getPos().z;
            if (Math.abs(x * 16 + 8 - 1000000) > 128 || Math.abs(z * 16 + 8 - 1000000) > 128) return;
            BlightLocusPolicy.siteForChunk(s.level.getSeed(), x, z).ifPresent(site -> s.chunkLoads.add(Map.of(
                    "chunk_x", x, "chunk_z", z, "new_chunk", event.isNewChunk(), "cell_key", site.key(), "server_tick", s.server.getTickCount())));
        }
        @SubscribeEvent
        public static void tick(TickEvent.ServerTickEvent event) {
            Session s = session; if (s == null || event.phase != TickEvent.Phase.END || event.getServer() != s.server || !s.phase.equals("active")) return;
            try { budget(s); if (s.failure.isEmpty()) tickBlast(); }
            catch (RuntimeException failure) { fail(failure); s.cancelTill = false; unregister(); }
        }
    }
    private static Blast matching(Level level, Explosion explosion) {
        Session s = session; if (s == null || s.blast == null || level != s.level || !s.phase.equals("active")) return null;
        Entity source = explosion.getDirectSourceEntity();
        return source instanceof PrimedTnt && source.getUUID().equals(s.blast.tnt) ? s.blast : null;
    }
    private static boolean controlsActive() {
        Session s = session;
        if (s == null || !s.phase.equals("active") || !s.failure.isEmpty()
                || !Boolean.getBoolean("bc.pack_test.gameplay_world") || !"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode"))
                || System.nanoTime() - s.startedNanos > 1_200_000_000_000L || s.server.getTickCount() - s.startedTick > 24000) return false;
        if (!s.binding.get("run").equals(System.getProperty("bc.pack_test.diagnostics.run_id"))
                || !s.binding.get("nonce").equals(System.getProperty("bc.pack_test.gameplay_world.nonce"))) return false;
        for (String kind : List.of("client", "server", "runtime", "support"))
            if (!s.binding.get(kind + "_sha256").equals(System.getProperty("bc.pack_test.diagnostics." + kind + "_sha256"))) return false;
        return true;
    }
    private static void budget(Session s) { check(controlsActive(), "world-hooks finite session budget/failure"); }
    private static void fail(RuntimeException failure) { if (session != null && session.failure.isEmpty()) session.failure = failure.toString(); }
    private static void unregister() { if (registered) { MinecraftForge.EVENT_BUS.unregister(Events.class); registered = false; } }
    private static Map<String, Object> blastRow(Blast b) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case", b.index); row.put("kind", b.index == 3 ? "canceled" : b.index == 4 ? "non-removing" : TNT_IDS[b.index]);
        row.put("sealed_position",List.of(b.sealedPos.getX(),b.sealedPos.getY(),b.sealedPos.getZ()));
        row.put("authored_withdrawal_candidate_added",b.authoredCandidateAdded);row.put("authored_candidate_removed_at_lowest",b.filterApplied);
        row.put("intervention",b.index==4?"HIGH adds exactly owned FAR sealed position to real Detonate list; Buried NORMAL native watch; LOW withdraws; actual EO candidate absence + loaded unchanged native TTL; not natural blast shape":"public native TNT ignition only");
        row.put("native_engine_handler_returns",b.engineReturns);row.put("final_native_engine_contains_sealed",b.finalEngineContainsSealed);
        row.put("native_crater_candidate_returns",b.craterReturns);row.put("native_crater_contains_sealed",b.craterContainsSealed);
        row.put("actual_final_engine_power",Float.isFinite(b.finalEnginePower)?b.finalEnginePower:"NOT_OBSERVED");row.put("negative_actual_loaded_ticks",b.negativeLoadedTicks);row.put("native_tracker_ticks_after_event",b.nativeTrackerTicks);
        row.put("primed_uuid", b.tnt.toString()); row.put("starts", b.starts); row.put("detonates", b.detonates);
        row.put("actual_explosion_radius", Float.isFinite(b.radius) ? b.radius : "NOT_OBSERVED");
        row.put("canceled", b.canceled); row.put("sealed_affected", b.sealedAffected); row.put("affected_count", b.affectedCount);
        row.put("native_watch_calls", b.watchCalls); row.put("deferred_tracker_spawn_calls", b.deferredSpawnCalls);
        row.put("ordinary_callback_spawn_calls", b.callbackSpawnCalls);
        row.put("deferred_boundary_exercised", b.deferredSpawnCalls == 1);
        row.put("join_candidate_uuids", b.candidateReleases.stream().map(UUID::toString).toList());
        row.put("released_uuids", b.released.stream().map(UUID::toString).toList()); return row;
    }
    private static Map<String, Object> observe() {
        Session s = session; Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema", "bc.world_hooks_native_observation.v1"); row.put("binding", s.binding);
        row.put("selected_family",s.family);row.put("complete",s.phase.equals("complete_explicit_hooks_only"));
        row.put("acceptance_scope","selected explicit family only; unselected families NOT_PERFORMED; tree/bootstrap/restart/global-engine-completion remain uncovered");
        row.put("effective_eo_configuration",s.eoConfig);
        row.put("deferred_applicability",!s.family("tnt-buried")?"NOT_EVALUATED_FOR_THIS_FAMILY":Boolean.TRUE.equals(s.eoConfig.get("deferred_required"))?"REQUIRED":"INACTIVE_CONDITION");
        row.put("player", s.owner.toString()); row.put("phase", s.phase); row.put("failure", s.failure);
        row.put("server_tick", s.server.getTickCount()); row.put("server_process", ProcessHandle.current().pid());
        row.put("world_seed", s.level.getSeed()); row.put("native_day_time", s.level.getDayTime());
        row.put("native_blast_cases", List.copyOf(s.blasts)); row.put("next_case", s.nextCase); row.put("case_ready", s.blast == null);
        row.put("native_deferred_path_exercised", s.blasts.stream().anyMatch(value -> Boolean.TRUE.equals(value.get("deferred_boundary_exercised"))));
        row.put("explosion_engine_global_queue_completion", "NOT_OBSERVED: bounded sealed-site removal and40-tick settling are not a global engine completion oracle");
        if (s.blast != null) row.put("in_flight", blastRow(s.blast));
        row.put("regolith_cases", List.copyOf(s.till)); row.put("fire_sources", List.copyOf(s.fire)); row.put("eligible_native_chunk_events", List.copyOf(s.chunkLoads));
        List<Map<String, Object>> liveFire = new ArrayList<>();
        boolean fireFinished = s.firePrepared;
        for (Map<String, Object> source : s.fire) {
            @SuppressWarnings("unchecked") List<Integer> pos = (List<Integer>) source.get("position");
            BlockPos at = new BlockPos(pos.get(0), pos.get(1), pos.get(2));
            BlockState state = s.level.getBlockState(at);
            boolean finished = state.hasProperty(com.bettercontent.betterwildfire.RemnantBlock.SMOLDERING)
                    && !state.getValue(com.bettercontent.betterwildfire.RemnantBlock.SMOLDERING);
            fireFinished &= finished;
            liveFire.add(Map.of("position", pos, "state", state.toString(), "scheduled_finish_observed", finished));
        }
        row.put("fire_live_states", liveFire); row.put("fire_scheduled_complete", fireFinished);
        if (s.firePrepared) {
            row.put("protected_chest_nbt", s.protectedChest.saveWithFullMetadata().toString());
            row.put("protected_flammable_nest_nbt", s.protectedNest.saveWithFullMetadata().toString());
        }
        row.put("limitations", List.of("authored bounded pads/items and public native API actions, not human input",
                "TNT entry radius/event/removal/unique release assertions, not an exhaustive explosion-engine workload/performance proof; ordinary-only release observations do not cover deferred tracker removal",
                "no restart performed here; parent owns restart and framebuffer LLM review",
                "trees/Malum automatic generation/bootstrap remain explicitly uncovered"));
        return row;
    }
    private static void preparePad(ServerLevel level, BlockPos center, int radius) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            BlockPos at = center.offset(x, 0, z);
            check(level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null, "world-hooks pad requires already-loaded chunk; parent must prepare core view");
            check(level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir(), "world-hooks refuses foreign fixture headroom " + at);
            BlockState below = level.getBlockState(at.below());
            check(below.isAir() || below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.STONE), "world-hooks refuses foreign fixture support " + at);
        }
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            level.setBlockAndUpdate(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState());
    }
    static Map<String, String> binding(JsonObject input, String side) {
        check("debug".equals(System.getProperty("bc.pack_test.diagnostics.mode")), "world-hooks requires Debug");
        Map<String, String> values = new LinkedHashMap<>();
        String run = System.getProperty("bc.pack_test.diagnostics.run_id", "");
        String nonce = System.getProperty("bc.pack_test." + side + ".nonce", "");
        check(run.matches("[A-Za-z0-9_.-]{1,128}") && UUID.fromString(nonce).toString().equals(nonce), "invalid world-hooks binding");
        check(run.equals(text(input, "run")) && nonce.equals(text(input, "nonce")), "world-hooks run/nonce drift");
        values.put("run", run); values.put("nonce", nonce);
        for (String kind : List.of("client", "server", "runtime", "support")) {
            String hash = System.getProperty("bc.pack_test.diagnostics." + kind + "_sha256", "");
            check(hash.matches("[a-f0-9]{64}"), "world-hooks missing digest " + kind);
            if (kind.equals("client") || kind.equals("server") || input.has(kind + "_sha256"))
                check(hash.equals(text(input, kind + "_sha256")), "world-hooks artifact drift " + kind);
            values.put(kind + "_sha256", hash);
        }
        return Map.copyOf(values);
    }
    private static Map<String,Object> eoConfiguration() {
        check(com.vinlanx.explosionoverhaul.Config.COMMON_SPEC.isLoaded(),"native EO common spec not loaded");
        var c=com.vinlanx.explosionoverhaul.Config.COMMON;
        boolean async=c.enableAsyncCrater.get(), direct=c.enableDirectChunkWrites.get(), destruction=c.enableCraterDestruction.get();
        String mode=com.vinlanx.explosionoverhaul.ExplosionOverhaul.getSourceMode("minecraft:tnt").name();
        var provider=net.minecraftforge.fml.ModList.get().getModFileById("explosionoverhaul");
        check(provider!=null,"selected native EO provider missing");
        String jar=hashRegular(provider.getFile().getFilePath(),64L*1024*1024);
        check(jar.equals("feac2ed262604f01e74609acdeba83c9b0bbe2b602ba0da40196fb1041f2c64f"),"selected native EO provider differs from reviewed engine");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("mod_sha256",jar);result.put("config_sha256",hashRegular(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("explosionoverhaul/explosionoverhaul-common.toml"),1024*1024));
        result.put("common_spec_loaded",true);result.put("async_crater",async);result.put("direct_chunk_writes",direct);
        result.put("crater_destruction",destruction);result.put("tnt_source_mode",mode);result.put("clustering",c.enableExplosionClustering.get());
        result.put("max_cluster_power",c.maxClusterPower.get());result.put("crater_size_multiplier",c.craterSizeMultiplier.get());
        result.put("deferred_required",async&&direct&&destruction&&mode.equals("DEFAULT"));
        return Map.copyOf(result);
    }
    private static String hashRegular(java.nio.file.Path path,long limit) {
        try {
            check(!java.nio.file.Files.isSymbolicLink(path)&&java.nio.file.Files.isRegularFile(path)
                    &&java.nio.file.Files.size(path)>0&&java.nio.file.Files.size(path)<=limit,"native reviewed file missing/oversized/symlink: "+path);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path)));
        } catch(java.io.IOException|java.security.NoSuchAlgorithmException failure) {throw new IllegalStateException(failure);}
    }
    private static void requireCore(ServerPlayer p) {
        check(p.isAlive() && p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && p.serverLevel().dimension().equals(Level.OVERWORLD)
                && Math.abs(p.getX() - 1000000) <= 128 && Math.abs(p.getZ() - 1000000) <= 128 && Math.abs(p.getY() - 316) <= 32, "world-hooks outside bounded real Survival core");
    }
    private static Block block(String id) { Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id)); check(b != null && b != Blocks.AIR, "missing native block " + id); return b; }
    private static List<Integer> xyz(BlockPos p) { return List.of(p.getX(), p.getY(), p.getZ()); }
    static String text(JsonObject input, String key) { check(input.has(key) && input.get(key).isJsonPrimitive(), "missing world-hooks input " + key); return input.get(key).getAsString(); }
    static int integer(JsonObject input, String key) { String raw = text(input, key); check(raw.matches("[0-9]{1,9}"), "invalid world-hooks integer " + key); return Integer.parseInt(raw); }
    static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
