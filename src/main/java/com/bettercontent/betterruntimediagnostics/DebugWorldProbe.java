package com.bettercontent.betterruntimediagnostics;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.UUID;
import net.minecraft.world.level.storage.LevelResource;
import com.bettercontent.betterruntimediagnostics.core.VerifyWorldExitRequest;

/** Inert outside the full-pack Debug fixture. */
@Mod.EventBusSubscriber(modid = RecipeGraphMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DebugWorldProbe {
    private static final String MODE = System.getProperty("bc.pack_test.world", "");
    private static final boolean DEBUG_MULTIPLAYER = Boolean.getBoolean("bc.pack_test.debug");
    private static int ticks;
    private static boolean completed;
    private static String lastPosition = "";
    private static final VerifyWorldExitRequest.Binding VERIFY_EXIT =
            VerifyWorldExitRequest.configured(System::getProperty).orElse(null);
    private static VerifyWorldExitRequest.Gate verifyExitGate;

    private static VerifyWorldExitRequest.LoadedIdentity actualIdentity(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null || client.level == null || client.player == null) return null;
        return new VerifyWorldExitRequest.LoadedIdentity(server, client.level, client.player,
                server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(),
                client.player.getUUID(), client.player.getGameProfile().getName());
    }

    private static void exitVerifiedFixtureIfRequested(Minecraft client) {
        var gate = verifyExitGate;
        if (gate == null || gate.started() || !ReflectiveEmiReadinessAdapter.isLoaded()) return;
        var identity = actualIdentity(client);
        if (identity == null || !gate.claim(identity, true)) return;
        var server = client.getSingleplayerServer();
        // claim is one-shot BEFORE these normal native operations (clearLevel re-enters ticks).
        client.level.disconnect();
        client.clearLevel();
        if (!server.isShutdown()) throw new IllegalStateException("Native verification exit returned with a live integrated server");
        var binding = gate.binding();
        RecipeGraphMod.LOGGER.info("BC_DEBUG_WORLD_EXITED mode=verify run_id={} nonce={} player={} player_uuid={} world_root={}",
                binding.runId(), binding.nonce(), binding.playerName(), binding.playerId(), binding.worldRoot());
    }

    private DebugWorldProbe() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (DEBUG_MULTIPLAYER && client.level != null && client.player != null) {
            var chunk = client.player.chunkPosition();
            String position = client.level.dimension().location() + " " + chunk.x + " " + chunk.z;
            if (!position.equals(lastPosition)) {
                lastPosition = position;
                RecipeGraphMod.LOGGER.info("BC_DEBUG_CLIENT_POSITION dimension={} chunk_x={} chunk_z={}",
                        client.level.dimension().location(), chunk.x, chunk.z);
            }
        }
        if (MODE.equals("verify") && VERIFY_EXIT != null) exitVerifiedFixtureIfRequested(client);
        if (completed || !(MODE.equals("save") || MODE.equals("verify"))) return;
        if (client.level == null || client.getSingleplayerServer() == null || ++ticks < 200) return;
        // Leaving while EMI is baking recipes clears JEMI's runtime under its worker and
        // loses fluid recipes. The debug save must wait for that client work to finish.
        if (MODE.equals("save") && !ReflectiveEmiReadinessAdapter.isLoaded()) return;
        completed = true;
        var server = client.getSingleplayerServer();
        if (MODE.equals("save")) RecipeGraphMod.LOGGER.info("BC_DEBUG_EMI_READY");
        server.execute(() -> {
            final DebugWorldMarker marker = DebugWorldMarker.get(server.overworld());
            if (MODE.equals("save")) marker.setValue(UUID.randomUUID().toString());
            final String markerValue = marker.value();
            long gameTime = server.overworld().getGameTime();
            RecipeGraphMod.LOGGER.info("BC_DEBUG_WORLD_LOADED mode={} game_time={} marker={}",
                    MODE, gameTime, markerValue);
            if (MODE.equals("verify") && VERIFY_EXIT != null) {
                // Capture on the client ONLY AFTER the actual server-thread LOADED marker.
                client.execute(() -> {
                    if (client.getSingleplayerServer() != server) return;
                    var identity = actualIdentity(client);
                    if (identity != null) verifyExitGate = VerifyWorldExitRequest.afterLoaded(VERIFY_EXIT, identity).orElse(null);
                });
            }
            if (MODE.equals("save")) {
                client.execute(() -> {
                    // Normal world exit saves and flushes the integrated server. Forcing every
                    // chunk through saveEverything here can stall this full-pack fixture.
                    client.level.disconnect();
                    client.clearLevel();
                    RecipeGraphMod.LOGGER.info("BC_DEBUG_WORLD_SAVED game_time={} marker={}", gameTime, markerValue);
                    RecipeGraphMod.LOGGER.info("BC_DEBUG_WORLD_EXITED");
                });
            }
        });
    }

}
