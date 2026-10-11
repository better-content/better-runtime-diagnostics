package com.bettercontent.runtimetestsupport;

import com.bettercontent.betterspawns.client.ClientOnboardingActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Supported native creation/onboarding driver, enabled only in a bound empty Debug fixture.
 * Never copies level.dat, writes spawn state, changes native settings after creation or teleports. */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support", value = Dist.CLIENT)
public final class FreshWorldProbe {
    private static final String NAME = System.getProperty("bc.pack_test.fresh.name", "");
    private static final String RUN = System.getProperty("bc.pack_test.diagnostics.run_id", "");
    private static final String NONCE = System.getProperty("bc.pack_test.fresh.nonce", "");
    private static boolean created, submitted, complete, exited;
    private static long started;
    private static int firstTick = -1;
    private static volatile int lastTick;
    private static volatile boolean serverLive;

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || NAME.isEmpty() || serverLive) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.isDedicatedServer()) return;
        Path root = Path.of(System.getProperty("bc.pack_test.fresh.root")).toAbsolutePath().normalize();
        if (!server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root)) return;
        var player = server.getPlayerList().getPlayer(UUID.fromString(System.getProperty("bc.pack_test.fresh.uuid")));
        if (player == null || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) return;
        int tick = server.getTickCount();
        if (firstTick < 0) firstTick = tick;
        if (tick - firstTick < 40) return;
        lastTick = tick;
        serverLive = true;
        LogManager.getLogger("FreshWorldProbe").info("BC_FRESH_SERVER_LIVE run={} nonce={} root={} uuid={} first_tick={} last_tick={}",
            RUN, NONCE, root, player.getUUID(), firstTick, lastTick);
    }
    private FreshWorldProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || NAME.isEmpty() || exited) return;
        Minecraft client = Minecraft.getInstance();
        Path root = Path.of(System.getProperty("bc.pack_test.fresh.root")).toAbsolutePath().normalize();
        Path bridge = Path.of(System.getProperty("bc.pack_test.diagnostics.dir")).toAbsolutePath().normalize();
        Path expected = client.gameDirectory.toPath().toAbsolutePath().normalize().resolve("saves").resolve(NAME);
        if (RUN.isBlank() || !UUID.fromString(NONCE).toString().equals(NONCE) || !NAME.matches("Fresh-[0-9a-f-]{36}")
                || !root.equals(expected) || !root.startsWith(bridge.getParent()))
            throw new IllegalStateException("fresh native world fixture binding is invalid");
        var binding = new com.bettercontent.betterruntimediagnostics.core.FreshWorldInput(RUN,
            UUID.fromString(NONCE), NAME, UUID.fromString(System.getProperty("bc.pack_test.fresh.uuid")), root);
        if (complete) {
            Path request = bridge.resolve("fresh-exit-" + NONCE + ".request");
            if (!Files.isRegularFile(request)) return;
            if (!binding.matchesExit(Files.readString(request)))
                throw new IllegalStateException("fresh native exit request does not match its bound world");
            var server = client.getSingleplayerServer();
            if (server == null || client.level == null ||
                    !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root))
                throw new IllegalStateException("fresh native exit world identity changed");
            exited = true;
            client.level.disconnect();
            client.clearLevel();
            if (!server.isShutdown()) throw new IllegalStateException("fresh native clearLevel left a live server");
            LogManager.getLogger("FreshWorldProbe").info("BC_FRESH_EXITED run={} nonce={} root={}", RUN, NONCE, root);
            return;
        }
        if (!created) {
            if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
            binding.requireEmptyNativeInput(client.gameDirectory.toPath(), bridge.getParent(),
                System.getProperty("bc.pack_test.diagnostics.mode", ""), client.getSingleplayerServer() != null);
            created = true;
            started = System.nanoTime();
            LogManager.getLogger("FreshWorldProbe").info("BC_FRESH_RENDERER vendor={} renderer={} version={}",
                org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR),
                org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),
                org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
            LogManager.getLogger("FreshWorldProbe").info("BC_FRESH_CREATE run={} nonce={} root={} preexisting=false", RUN, NONCE, root);
            client.createWorldOpenFlows().createFreshLevel(NAME,
                new LevelSettings(NAME, GameType.SURVIVAL, false, Difficulty.NORMAL, false,
                    new GameRules(), WorldDataConfiguration.DEFAULT), WorldOptions.defaultWithRandomSeed(),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                    .getOrThrow(WorldPresets.NORMAL).createWorldDimensions());
            return;
        }
        if (System.nanoTime() - started > 900_000_000_000L)
            throw new IllegalStateException("fresh native world startup exceeded 15 minutes");
        var server = client.getSingleplayerServer();
        if (server == null || client.level == null || client.player == null) return;
        if (!server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root))
            throw new IllegalStateException("fresh native world loaded a different save");
        Path state = root.resolve("data/better_world_management/initial-spawn-v1.tsv");
        // Observation only: the production path, not a fixture marker, must resolve this.
        if (!Files.isRegularFile(state) || !Files.readString(state).trim().equals("resolved")) return;
        if (!submitted) {
            if (!client.level.hasChunk(client.player.chunkPosition().x, client.player.chunkPosition().z)) return;
            if (!ClientOnboardingActions.INSTANCE.submitSpawnOnly(client))
                throw new IllegalStateException("normal native onboarding submission failed");
            submitted = true;
            LogManager.getLogger("FreshWorldProbe").info("BC_FRESH_ONBOARDING run={} nonce={} player={} uuid={}",
                RUN, NONCE, client.player.getGameProfile().getName(), client.player.getUUID());
        }
        if (client.player.isSpectator() || client.player.isCreative() || client.gameMode == null
                || client.gameMode.getPlayerMode() != GameType.SURVIVAL
                || !client.level.hasChunk(client.player.chunkPosition().x, client.player.chunkPosition().z)) return;
        if (!serverLive || !client.player.getUUID().toString().equals(System.getProperty("bc.pack_test.fresh.uuid"))) return;
        complete = true;
        LogManager.getLogger("FreshWorldProbe").info("BC_FRESH_READY run={} nonce={} root={} seed={} spawn_state=resolved mode=survival first_tick={} last_tick={} dimension={} chunk_x={} chunk_z={}",
            RUN, NONCE, root, server.overworld().getSeed(), firstTick, lastTick, client.level.dimension().location(),
            client.player.chunkPosition().x, client.player.chunkPosition().z);
    }
}
