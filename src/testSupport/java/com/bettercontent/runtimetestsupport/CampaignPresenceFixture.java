package com.bettercontent.runtimetestsupport;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Nonshipping authored campaign history, NOT evidence of thirty elapsed online minutes.
 * Calls only public production APIs; never changes eligibility, clocks or private snapshots.
 */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support")
public final class CampaignPresenceFixture {
    private static final int AUTHORED_TICKS = 36_000;
    private static final Map<UUID, Prepared> prepared = new HashMap<>();
    private static final Set<UUID> nonces = new HashSet<>();
    private static final Set<UUID> seeded = new HashSet<>();
    private record Prepared(UUID nonce, int x, int y, int z, boolean familiar) {}
    private CampaignPresenceFixture() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) throws Exception {
        if (!Boolean.getBoolean("bc.pack_test.campaign_presence") || event.phase != TickEvent.Phase.END
                || !(event.player instanceof ServerPlayer player)) return;
        if (!"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode")))
            throw new IllegalStateException("campaign presence fixture requires Debug binding");
        String run = System.getProperty("bc.pack_test.diagnostics.run_id", "");
        if (!run.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalStateException("missing campaign run binding");
        Path bridge = Path.of(System.getProperty("bc.pack_test.diagnostics.dir")).toAbsolutePath().normalize();
        Path request = bridge.resolve("campaign-presence.request");
        if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)) return;
        for (Path path = request; path != null; path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IllegalStateException("symlink campaign request boundary");
        if (!Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS) || Files.size(request) > 1_024)
            throw new IllegalStateException("invalid campaign presence request");
        String[] fields = Files.readString(request).split("\n");
        if (fields.length != 9 || !run.equals(fields[0])) throw new IllegalStateException("campaign request run mismatch");
        UUID nonce = UUID.fromString(fields[1]);
        UUID uuid = UUID.fromString(fields[2]);
        if (!nonce.toString().equals(fields[1]) || !uuid.toString().equals(fields[2]))
            throw new IllegalStateException("noncanonical campaign request identity");
        if (!uuid.equals(player.getUUID())) return;
        int index = Integer.parseInt(fields[4]);
        int x = Integer.parseInt(fields[5]), y = Integer.parseInt(fields[6]), z = Integer.parseInt(fields[7]);
        String kind = fields[8];
        if (index < 1 || index > 3 || !fields[3].equals("SmokeClient" + index)
                || !fields[3].equals(player.getGameProfile().getName()) || x != (index - 1) * 10_000 || y != 316 || z != 0
                || !Set.of("inspect", "seed").contains(kind)) throw new IllegalStateException("campaign fixture scope mismatch");
        if (player.server.getPlayerList().getPlayer(uuid) != player || !player.isAlive()
                || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL
                || !player.serverLevel().dimension().equals(Level.OVERWORLD)
                || player.getBlockX() != x || player.getBlockY() != y || player.getBlockZ() != z
                || !player.serverLevel().getBlockState(player.blockPosition().below()).is(Blocks.GRASS_BLOCK))
            throw new IllegalStateException("campaign history requires a grounded online living Survival player");

        // Exact public signatures, fail closed. No provider dependency or reflective private access.
        Class<?> dataClass = Class.forName("com.bettercontent.betterpillagercampaigns.data.PillagerWorldData");
        Class<?> pointClass = Class.forName("com.bettercontent.betterpillagercampaigns.core.BlockPoint");
        Object companion = dataClass.getField("Companion").get(null);
        Object data = companion.getClass().getMethod("get", MinecraftServer.class).invoke(companion, player.server);
        Object point = pointClass.getConstructor(String.class, int.class, int.class, int.class)
            .newInstance("minecraft:overworld", x, y, z);
        Method familiar = dataClass.getMethod("regionFamiliar", String.class, pointClass);
        boolean before = (Boolean) familiar.invoke(data, uuid.toString(), point);
        if (kind.equals("inspect")) {
            if (prepared.containsKey(uuid) || nonces.contains(nonce) || prepared.size() >= 3)
                throw new IllegalStateException("replayed or excessive campaign inspection");
            if (index > 1 && before) throw new IllegalStateException("fresh campaign negative already has familiar history");
            consume(request, bridge, kind, nonce);
            nonces.add(nonce);
            prepared.put(uuid, new Prepared(nonce, x, y, z, before));
            LogManager.getLogger("CampaignPresenceFixture").info(
                "BC_CAMPAIGN_PRESENCE_INSPECT run={} nonce={} uuid={} player={} dimension=minecraft:overworld x={} y={} z={} familiar={}",
                run, nonce, uuid, fields[3], x, y, z, before);
            return;
        }
        Prepared prior = prepared.get(uuid);
        if (prior == null || !prior.nonce().equals(nonce) || prior.x() != x || prior.y() != y || prior.z() != z
                || seeded.contains(uuid) || (index > 1 && before))
            throw new IllegalStateException("campaign seed missing matching unfamiliar inspection or already consumed");
        consume(request, bridge, kind, nonce);
        seeded.add(uuid);
        Method record = dataClass.getMethod("recordPresence", String.class, pointClass);
        for (int tick = 0; tick < AUTHORED_TICKS; tick++) record.invoke(data, uuid.toString(), point);
        if (!(Boolean) familiar.invoke(data, uuid.toString(), point)) throw new IllegalStateException("authored campaign seed failed");
        LogManager.getLogger("CampaignPresenceFixture").info(
            "BC_CAMPAIGN_PRESENCE_SEEDED run={} nonce={} uuid={} player={} dimension=minecraft:overworld x={} y={} z={} before_inspect={} before_seed={} after=true authored_ticks=36000 history_origin=authored_fixture",
            run, nonce, uuid, fields[3], x, y, z, prior.familiar(), before);
    }

    private static void consume(Path request, Path bridge, String kind, UUID nonce) throws Exception {
        // Retain request provenance, but remove the live name before mutation to prevent replay.
        Files.move(request, bridge.resolve("campaign-presence-" + kind + "-" + nonce + ".consumed"));
    }
}
