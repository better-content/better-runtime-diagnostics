package com.bettercontent.runtimetestsupport.fixture;

import com.bettercontent.betterspawns.client.ClassSelectionState;
import com.bettercontent.betterspawns.client.ClientOnboardingActions;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;

/** A requested real production onboarding action. No game-mode, flight, selection or server-state writes. */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support", value = Dist.CLIENT)
public final class DedicatedOnboardingProbe {
    private static boolean submitted;
    private static boolean complete;
    private DedicatedOnboardingProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || complete
                || System.getProperty("bc.pack_test.offline_client.run", "").isEmpty()) return;
        FixtureClientInput binding = FixtureClientInput.fromProperties(System.getProperties());
        var request = binding.bridge().resolve("onboarding-" + binding.nonce() + ".request");
        if (!Files.isRegularFile(request)) return;
        binding.requireOnboardingRequest(Files.readString(request));
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.gameMode == null || client.getConnection() == null) return;
        if (client.hasSingleplayerServer()) throw new IllegalStateException("dedicated onboarding cannot drive an integrated world");
        binding.requireActual(client.gameDirectory.toPath(), client.player.getUUID(),
            client.player.getGameProfile().getName(), client.getUser().getAccessToken());
        if (!client.level.hasChunk(client.player.chunkPosition().x, client.player.chunkPosition().z)) return;
        if (!submitted) {
            if (!ClassSelectionState.INSTANCE.getActiveInCurrentWorld() || !ClassSelectionState.INSTANCE.getSelectionRequired()) return;
            if (!client.player.isSpectator()) throw new IllegalStateException("fresh onboarding did not start as a production spectator");
            if (!ClientOnboardingActions.INSTANCE.submitSpawnOnly(client))
                throw new IllegalStateException("normal dedicated onboarding submission failed");
            submitted = true;
            LogManager.getLogger("DedicatedOnboardingProbe").info("BC_DEDICATED_ONBOARDING_SUBMITTED run={} nonce={} uuid={} action=ClientOnboardingActions.submitSpawnOnly",
                binding.run(), binding.nonce(), binding.playerId());
            return;
        }
        if (client.player.isSpectator() || client.player.isCreative() || client.gameMode.getPlayerMode() != GameType.SURVIVAL
                || ClassSelectionState.INSTANCE.getSelectionRequired()) return;
        complete = true;
        LogManager.getLogger("DedicatedOnboardingProbe").info("BC_DEDICATED_ONBOARDING_READY run={} nonce={} uuid={} mode=survival dimension={} chunk_loaded=true submitted=true",
            binding.run(), binding.nonce(), binding.playerId(), client.level.dimension().location());
    }
}
