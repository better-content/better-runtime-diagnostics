package com.bettercontent.runtimetestsupport;

import net.mcreator.thedeepvoid.configuration.DeepVoidConfigConfiguration;
import net.mcreator.thedeepvoid.network.TheDeepVoidModVariables;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One-shot same-dimension setup and observation around the REAL native END player tick.
 * Does not call the upstream procedure, replace eligibility, or teleport into the target. */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support")
public final class DeepVoidTickProbe {
    private static final boolean ENABLED = Boolean.getBoolean("bc.pack_test.deepvoid");
    private static final String RUN = System.getProperty("bc.pack_test.diagnostics.run_id", "");
    private static final Set<UUID> consumed = new HashSet<>();
    private static Armed armed;
    private record Armed(UUID nonce, String kind, UUID player, int tick, String source, int bottom,
                         double threshold, double originalX, double originalY, double originalZ, boolean voidEnabled) {}
    private DeepVoidTickProbe() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void before(TickEvent.PlayerTickEvent event) throws Exception {
        if (!ENABLED || event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player) || armed != null) return;
        if (!player.getGameProfile().getName().equals(System.getProperty("bc.pack_test.diagnostics.player"))) return;
        Path request = Path.of(System.getProperty("bc.pack_test.diagnostics.dir")).resolve("deepvoid.request");
        if (!Files.isRegularFile(request)) return;
        if (Files.isSymbolicLink(request) || !System.getProperty("bc.pack_test.diagnostics.mode", "").equals("debug"))
            throw new IllegalStateException("native Deep Void request is outside its Debug fixture");
        String[] fields = Files.readString(request).split("\n");
        if (fields.length != 4 || !fields[0].equals(RUN) || !UUID.fromString(fields[2]).equals(player.getUUID()))
            throw new IllegalStateException("native Deep Void request identity mismatch");
        UUID nonce = UUID.fromString(fields[1]);
        if (consumed.contains(nonce)) return;
        String kind = fields[3];
        if (!Set.of("above", "disabled", "entry").contains(kind) || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL
                || !player.serverLevel().dimension().equals(Level.OVERWORLD))
            throw new IllegalStateException("native Deep Void arm requires an eligible real Survival Overworld player");
        int bottom = player.serverLevel().getMinBuildHeight();
        if (bottom >= -64) throw new IllegalStateException("native Deep Void scenario did not load the packaged extended-bottom Overworld");
        boolean enabled = (boolean) DeepVoidConfigConfiguration.PLAYERSTELEPORTINVOID.get();
        if (!enabled) throw new IllegalStateException("packaged native void route is disabled; do not override its eligibility");
        var variables = player.getCapability(TheDeepVoidModVariables.PLAYER_VARIABLES_CAPABILITY)
            .orElseThrow(() -> new IllegalStateException("real network player lacks native Deep Void capability"));
        if (variables.naturalTeleport) throw new IllegalStateException("native Deep Void case started with an already-set entry capability");
        if (consumed.size() >= 3) throw new IllegalStateException("native Deep Void case count exceeded its bounded contract");
        Files.delete(request); // No restart replay or automatically repeated native operation.
        consumed.add(nonce);
        double threshold = bottom - 6.0;
        armed = new Armed(nonce, kind, player.getUUID(), player.server.getTickCount(), player.serverLevel().dimension().location().toString(),
            bottom, threshold, player.getX(), player.getY(), player.getZ(), enabled);
        if (kind.equals("disabled")) DeepVoidConfigConfiguration.PLAYERSTELEPORTINVOID.set(false);
        // Same-dimension positioning is fixture setup; destination transfer belongs exclusively to the upstream player tick.
        double y = kind.equals("above") ? threshold + 0.001 : threshold - 0.001;
        player.setDeltaMovement(Vec3.ZERO);
        player.connection.teleport(player.getX(), y, player.getZ(), player.getYRot(), player.getXRot());
        LogManager.getLogger("DeepVoidTickProbe").info("BC_DEEPVOID_BEFORE run={} nonce={} case={} uuid={} tick={} dimension={} bottom={} threshold={} y={} mode=survival natural={} void_enabled={} other_enabled={} end_enabled={}",
            RUN, nonce, kind, player.getUUID(), armed.tick(), armed.source(), bottom, threshold, player.getY(), variables.naturalTeleport,
            DeepVoidConfigConfiguration.PLAYERSTELEPORTINVOID.get(), DeepVoidConfigConfiguration.ALLOWOTHERWORLDTP.get(), DeepVoidConfigConfiguration.PLAYERSTELEPORTINEND.get());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void after(TickEvent.PlayerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        Armed current = armed;
        if (current == null || !current.player().equals(player.getUUID())) return;
        try {
            var variables = player.getCapability(TheDeepVoidModVariables.PLAYER_VARIABLES_CAPABILITY)
                .orElseThrow(() -> new IllegalStateException("native Deep Void capability disappeared after its player tick"));
            LogManager.getLogger("DeepVoidTickProbe").info("BC_DEEPVOID_AFTER run={} nonce={} case={} uuid={} tick={} dimension={} bottom={} y={} natural={} mode={}",
                RUN, current.nonce(), current.kind(), player.getUUID(), player.server.getTickCount(),
                player.serverLevel().dimension().location(), player.serverLevel().getMinBuildHeight(), player.getY(), variables.naturalTeleport,
                player.gameMode.getGameModeForPlayer().getName());
            if (!current.kind().equals("entry") && !variables.naturalTeleport && player.serverLevel().dimension().equals(Level.OVERWORLD)) {
                player.setDeltaMovement(Vec3.ZERO);
                player.connection.teleport(current.originalX(), current.originalY(), current.originalZ(), player.getYRot(), player.getXRot());
            }
        } finally {
            DeepVoidConfigConfiguration.PLAYERSTELEPORTINVOID.set(current.voidEnabled());
            armed = null;
        }
    }
}
