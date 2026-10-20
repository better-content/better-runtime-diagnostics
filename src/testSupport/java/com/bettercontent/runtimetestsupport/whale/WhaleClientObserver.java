package com.bettercontent.runtimetestsupport.whale;

import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.LoggerFactory;

/** Read-only native client observer: no mixin, entity mutation, packet or event cancellation. */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WhaleClientObserver {
    private static WhaleObservation.State state;
    private static boolean initialized;
    private static boolean connected;
    private static long tick;
    private static Entity joinedEntity;
    private WhaleClientObserver() {}

    private static boolean active(Minecraft mc) {
        if (!initialized) {
            initialized = true;
            if (!"true".equals(System.getProperty("bc.pack_test.whale.enabled"))) return false;
            try {
                var binding = WhaleObservation.Binding.read(System.getProperties());
                binding.validateClientDirectory(mc.gameDirectory.toPath().toAbsolutePath().normalize());
                state = WhaleObservation.State.idle(binding);
            } catch (Exception invalid) { return false; }
        }
        if (state == null || state.closed()) return false;
        try {
            var binding = WhaleObservation.Binding.read(System.getProperties());
            binding.validateClientDirectory(mc.gameDirectory.toPath().toAbsolutePath().normalize());
            if (!state.binding().equals(binding)) { clear(); return false; }
        } catch (Exception invalid) { clear(); return false; }
        boolean online = networkConnected(mc) && mc.level != null && mc.player != null;
        if (connected && !online) { clear(); return false; }
        if (online) {
            connected = true;
            if (!state.binding().playerUuid().equals(mc.player.getUUID().toString())) { clear(); return false; }
        }
        return online;
    }
    private static boolean networkConnected(Minecraft mc) {
        return mc.getConnection() != null && mc.getConnection().getConnection().isConnected();
    }
    private static void clear() {
        if (state != null) state = state.disconnect();
        joinedEntity = null;
    }
    private static WhaleObservation.Observation observation(Minecraft mc, Entity entity) {
        return new WhaleObservation.Observation(mc.level, entity,
            mc.player == null ? null : mc.player.getUUID().toString(),
            entity == null ? null : entity.getUUID().toString(),
            entity == null ? null : String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType())),
            mc.level == null ? null : mc.level.dimension().location().toString(),
            networkConnected(mc), mc.level instanceof ClientLevel && entity != null
                && entity.level() == mc.level && mc.level.getEntity(entity.getId()) == entity && !entity.isRemoved());
    }
    @SubscribeEvent
    public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        if (initialized && state != null) clear();
    }
    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!active(mc) || event.getLevel() != mc.level || !(event.getLevel() instanceof ClientLevel)) return;
        // EntityJoinLevelEvent fires before insertion; exact reference is checked on subsequent ticks.
        Entity entity = event.getEntity();
        var observed = new WhaleObservation.Observation(mc.level, entity, mc.player.getUUID().toString(),
            entity.getUUID().toString(), String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType())),
            mc.level.dimension().location().toString(), networkConnected(mc), entity.level() == mc.level);
        var next = state.join(observed, tick, System.nanoTime());
        if (next != state) { state = next; joinedEntity = entity; }
    }
    @SubscribeEvent
    public static void endTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick++;
        Minecraft mc = Minecraft.getInstance();
        if (!active(mc)) return;
        state = state.tick(observation(mc, joinedEntity), tick, System.nanoTime());
        if (state.receipt() != null) {
            LoggerFactory.getLogger("runtime-fixture/whale").info("BC_WHALE_CLIENT_OBSERVED {}", state.receipt());
            joinedEntity = null;
        } else if (state.closed()) joinedEntity = null;
    }
}
