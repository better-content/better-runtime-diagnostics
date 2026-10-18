package com.bettercontent.runtimetestsupport;

import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

/** Observe production presence only. Never writes campaign data or changes a gameplay clock. */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support")
public final class ObservedCampaignPresenceProbe {
    private static final Gson JSON = new Gson();
    private static State state;
    private static Path bridge, checkpoint;
    private static long previousNanos, previousTick;
    private static int visit;
    private static final long VISIT_NANOS = 900_000_000_000L;
    private static final int X = 1_000_000, Y = 316, Z = 1_000_000;
    private static final class Request { String schema, run, nonce, player, action; }
    private static final class State {
        String schema = "bc.observed_campaign_presence.v1", run, nonce, player, world, worldIdentity;
        String candidateClient, candidateServer, supportHash;
        String history_origin = "observed_native_online_ticks";
        long firstTicks, secondTicks, firstNanos, secondNanos, lastNativeTick;
        long firstNativeStart, firstNativeEnd, secondNativeStart, secondNativeEnd;
        long firstMonotonicStart, firstMonotonicEnd, secondMonotonicStart, secondMonotonicEnd;
        boolean beforeFamiliar, halfFamiliar, resumeFamiliar, finalFamiliar, half, resumed, complete;
        int offline_ticks_counted = 0;
    }
    private ObservedCampaignPresenceProbe() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent event) throws Exception {
        if (!Boolean.getBoolean("bc.pack_test.observed_campaign_presence") || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (!"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode"))) fail("Debug binding required");
        String run = System.getProperty("bc.pack_test.diagnostics.run_id", "");
        if (!run.matches("[A-Za-z0-9_.-]{1,128}")) fail("invalid run binding");
        bridge = Path.of(System.getProperty("bc.pack_test.diagnostics.dir")).toAbsolutePath().normalize();
        safe(bridge);
        Path request = bridge.resolve("observed-presence.request");
        if (Files.exists(request, LinkOption.NOFOLLOW_LINKS)) {
            safe(request);
            if (!Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS) || Files.size(request) > 1024) fail("invalid request");
            Request input = JSON.fromJson(Files.readString(request), Request.class);
            if (!"bc.observed_campaign_request.v1".equals(input.schema) || !run.equals(input.run)
                    || !Set.of("arm", "resume").contains(input.action)) fail("invalid request binding");
            canonical(input.nonce); canonical(input.player);
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            safe(world);
            checkpoint = world.resolve("observed-campaign-presence.json");
            if (input.action.equals("arm")) {
                if (state != null || Files.exists(checkpoint)) fail("replayed arm or preexisting measurement");
                ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(input.player));
                if (player == null || player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR || inCore(player))
                    fail("arm before Survival entry and outside fresh core");
                state = new State(); state.run = run; state.nonce = input.nonce; state.player = input.player;
                state.world = world.toString(); state.worldIdentity = UUID.randomUUID().toString();
                state.candidateClient = hashProperty("client_sha256"); state.candidateServer = hashProperty("server_sha256");
                state.supportHash = hashProperty("support_sha256");
                state.beforeFamiliar = familiar(server);
                if (state.beforeFamiliar) fail("fresh core is already familiar; preserve history and choose fresh fixture");
                visit = 1;
            } else {
                if (state != null || !Files.isRegularFile(checkpoint) || Files.size(checkpoint) > 16384)
                    fail("missing same-world restart checkpoint");
                safe(checkpoint);
                state = JSON.fromJson(Files.readString(checkpoint), State.class);
                if (!run.equals(state.run) || !input.nonce.equals(state.nonce) || !input.player.equals(state.player)
                        || !world.toString().equals(state.world) || !state.half || state.resumed || state.complete
                        || state.firstTicks < 18000 || state.firstTicks >= 36000 || state.firstNanos < VISIT_NANOS
                        || state.secondTicks != 0 || state.secondNanos != 0 || state.offline_ticks_counted != 0
                        || state.beforeFamiliar || state.halfFamiliar
                        || !"bc.observed_campaign_presence.v1".equals(state.schema)
                        || !"observed_native_online_ticks".equals(state.history_origin)
                        || !hashProperty("client_sha256").equals(state.candidateClient)
                        || !hashProperty("server_sha256").equals(state.candidateServer)
                        || !hashProperty("support_sha256").equals(state.supportHash)) fail("invalid restart checkpoint");
                canonical(state.worldIdentity);
                // Reconnect is deliberately later: offline intervals are never credited.
                if (server.getPlayerList().getPlayer(UUID.fromString(state.player)) != null) fail("resume before reconnect");
                state.resumeFamiliar = familiar(server);
                if (state.resumeFamiliar) fail("first half unexpectedly sufficient for familiarity");
                state.resumed = true; visit = 2;
            }
            previousNanos = 0;
            Files.move(request, bridge.resolve("observed-presence-" + input.action + "-" + input.nonce + ".consumed"));
            persist(); publish(input.action);
        }
        if (state == null || state.complete) return;
        ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(state.player));
        boolean eligible = player != null && player.isAlive() && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && player.serverLevel().dimension().equals(Level.OVERWORLD) && inCore(player);
        if (!eligible) { previousNanos = 0; return; }
        long tick = server.overworld().getGameTime(), now = System.nanoTime();
        long elapsed = previousNanos == 0 ? 0 : now - previousNanos;
        if (elapsed < 0 || (previousNanos != 0 && tick != previousTick + 1)) fail("non-native tick progression");
        previousNanos = now; previousTick = tick; state.lastNativeTick = tick;
        if (visit == 1) {
            if (state.firstTicks == 0) { state.firstNativeStart = tick; state.firstMonotonicStart = now; }
            state.firstNativeEnd = tick; state.firstMonotonicEnd = now;
            state.firstTicks++; state.firstNanos += elapsed;
        } else {
            if (state.secondTicks == 0) { state.secondNativeStart = tick; state.secondMonotonicStart = now; }
            state.secondNativeEnd = tick; state.secondMonotonicEnd = now;
            state.secondTicks++; state.secondNanos += elapsed;
        }
        if (visit == 1 && !state.half && state.firstTicks >= 18000 && state.firstNanos >= VISIT_NANOS) {
            state.halfFamiliar = familiar(server);
            if (state.halfFamiliar) fail("first visit alone is familiar");
            state.half = true; persist(); publish("half");
        }
        if (visit == 1 && state.firstTicks >= 36000) fail("restart was not performed before full familiarity");
        if (visit == 2 && state.secondTicks >= 36000) fail("second visit alone could explain familiarity");
        if (visit == 2 && state.secondTicks >= 18000 && state.secondNanos >= VISIT_NANOS) {
            state.finalFamiliar = familiar(server);
            if (!state.finalFamiliar) fail("retained first visit plus second visit did not become familiar");
            state.complete = true; persist(); publish("complete");
        }
        if ((state.firstTicks + state.secondTicks) % 200 == 0) { persist(); publish("progress"); }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) throws Exception {
        if (state != null) { persist(); publish("stopped"); }
    }
    private static boolean inCore(ServerPlayer player) {
        double dx = player.getX() - X, dz = player.getZ() - Z;
        return dx * dx + dz * dz <= 128 * 128 && Math.abs(player.getY() - Y) <= 32;
    }
    private static boolean familiar(MinecraftServer server) throws Exception {
        Class<?> dataClass = Class.forName("com.bettercontent.betterpillagercampaigns.data.PillagerWorldData");
        Class<?> pointClass = Class.forName("com.bettercontent.betterpillagercampaigns.core.BlockPoint");
        Object companion = dataClass.getField("Companion").get(null);
        Object data = companion.getClass().getMethod("get", MinecraftServer.class).invoke(companion, server);
        Object point = pointClass.getConstructor(String.class, int.class, int.class, int.class)
                .newInstance("minecraft:overworld", X, Y, Z);
        return (Boolean) dataClass.getMethod("regionFamiliar", String.class, pointClass).invoke(data, state.player, point);
    }
    private static void persist() throws Exception { atomic(checkpoint, JSON.toJson(state)); }
    private static void publish(String kind) throws Exception {
        atomic(bridge.resolve("observed-presence-" + kind + ".json"), JSON.toJson(state));
    }
    private static void atomic(Path path, String text) throws Exception {
        safe(path); Path temporary = path.resolveSibling(path.getFileName() + ".next"); safe(temporary);
        Files.writeString(temporary, text);
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void safe(Path path) {
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) fail("symlink measurement boundary");
    }
    private static String hashProperty(String key) {
        String value = System.getProperty("bc.pack_test.diagnostics." + key, "");
        if (!value.matches("[a-f0-9]{64}")) fail("missing candidate/support hash binding");
        return value;
    }
    private static void canonical(String uuid) {
        if (uuid == null || !UUID.fromString(uuid).toString().equals(uuid)) fail("noncanonical UUID");
    }
    private static void fail(String reason) { throw new IllegalStateException("observed campaign: " + reason); }
}
