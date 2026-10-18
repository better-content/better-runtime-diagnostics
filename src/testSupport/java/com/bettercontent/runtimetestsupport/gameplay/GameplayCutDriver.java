package com.bettercontent.runtimetestsupport.gameplay;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Reviewed test-only authored held-cut driver. This is explicitly MUTATING fixture control,
 * not an observer or human-pointer acceptance. Only native no-input release is suppressed;
 * all progress/chips/server packets still come from actual public native mining calls at 20Hz. */
public final class GameplayCutDriver {
    private static BlockPos target;
    private static Direction face;
    private static UUID player;
    private static String run, nonce, candidate;
    private static long deadline, lastTick = -1;
    private GameplayCutDriver() {}
    public static void start(Minecraft mc, JsonObject request, BlockPos pos, Direction direction, long tick) {
        stop(mc);
        if (!Boolean.getBoolean("bc.pack_test.gameplay_client") || mc.player == null
                || !"bc.gameplay_client_request.v1".equals(request.get("schema").getAsString())
                || !mc.player.getUUID().toString().equals(request.get("player").getAsString())
                || !System.getProperty("bc.pack_test.diagnostics.run_id", "").equals(request.get("run").getAsString())
                || !System.getProperty("bc.pack_test.gameplay_client.nonce", "").equals(request.get("nonce").getAsString())
                || !UUID.fromString(request.get("nonce").getAsString()).toString().equals(request.get("nonce").getAsString()))
            throw new IllegalStateException("held-cut action binding mismatch");
        String client = System.getProperty("bc.pack_test.diagnostics.client_sha256", "");
        String server = System.getProperty("bc.pack_test.diagnostics.server_sha256", "");
        for (String kind : new String[]{"client", "server", "runtime", "support"})
            if (!System.getProperty("bc.pack_test.diagnostics." + kind + "_sha256", "").matches("[a-f0-9]{64}"))
                throw new IllegalStateException("held-cut requires exact candidate/support binding");
        if (!client.equals(request.get("client_sha256").getAsString()) || !server.equals(request.get("server_sha256").getAsString()))
            throw new IllegalStateException("held-cut candidate mismatch");
        if (!pos.equals(new BlockPos(1_000_000, 316, 1_000_020))) throw new IllegalStateException("held-cut exact fixture target mismatch");
        mc.gameMode.startDestroyBlock(pos, direction);
        target = pos.immutable(); face = direction; player = mc.player.getUUID(); lastTick = tick;
        run = request.get("run").getAsString(); nonce = request.get("nonce").getAsString(); candidate = client;
        deadline = System.nanoTime() + 90_000_000_000L;
        if (!valid(mc)) { stop(mc); throw new IllegalStateException("invalid held-cut start"); }
    }
    private static boolean valid(Minecraft mc) {
        return target != null && GameplayClientProbe.observing() && System.nanoTime() < deadline
                && "debug".equals(System.getProperty("bc.pack_test.diagnostics.mode"))
                && run.equals(System.getProperty("bc.pack_test.diagnostics.run_id"))
                && nonce.equals(System.getProperty("bc.pack_test.gameplay_client.nonce"))
                && candidate.equals(System.getProperty("bc.pack_test.diagnostics.client_sha256"))
                && mc.level != null && mc.level.dimension().equals(Level.OVERWORLD) && mc.getConnection() != null
                && mc.player != null && mc.player.isAlive() && player.equals(mc.player.getUUID())
                && mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SURVIVAL && mc.screen == null
                && !mc.options.keyAttack.isDown() && !mc.options.keyUse.isDown()
                && mc.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target)) <= 36
                && mc.level.hasChunkAt(target);
    }
    /** Called at most once per actual ClientTick END, never from rendering or polling frequency. */
    public static void tick(Minecraft mc, long tick) {
        if (target == null) return;
        if (!valid(mc) || mc.level.getBlockState(target).isAir()) { stop(mc); return; }
        if (tick <= lastTick) return;
        lastTick = tick;
        mc.gameMode.continueDestroyBlock(target, face);
    }
    /** The sole cancellation predicate consumed by the separately audited driver mixin. */
    public static boolean suppressUnrelatedRelease() {
        if (target == null) return false;
        if (!valid(Minecraft.getInstance())) { clear(); return false; }
        return true;
    }
    public static boolean active() { return target != null; }
    public static void stop(Minecraft mc) {
        boolean wasActive = target != null;
        clear(); // Always clear BEFORE native stop; explicit stop/world/deadline never gets cancelled.
        if (wasActive && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }
    private static void clear() { target = null; face = null; player = null; run = nonce = candidate = null; deadline = 0; lastTick = -1; }
}
