package com.bettercontent.runtimetestsupport.gameplay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.client.event.sound.SoundEvent.SoundSourceEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Nonshipping native observations and explicitly authored native interactions. No preview renderer,
 * input injection, provider reflection, private-state writes or claims of physical audio acceptance. */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support", value = Dist.CLIENT)
public final class GameplayClientProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final boolean ENABLED = Boolean.getBoolean("bc.pack_test.gameplay_client");
    private static final Map<String, Long> PARTICLES = new LinkedHashMap<>();
    private static final List<Map<String, Object>> SOUNDS = new ArrayList<>();
    private static final List<Map<String, Object>> SOURCES = new ArrayList<>();
    private static final List<Map<String, Object>> CUTS = new ArrayList<>();
    private static final List<Map<String, Object>> PARTICLE_SAMPLES = new ArrayList<>();
    private static int sequence;
    private static volatile long ticks;
    private static long frames, startedTick;
    private static volatile boolean stopped;
    private static JsonObject pending;
    private static long pendingFrame;
    private static long soundsDropped, sourcesDropped, cutsDropped, particleSamplesDropped;
    private static final List<Map<String, Object>> COMPAT_CHIPS = new ArrayList<>();
    private static long chipSamplesDropped, chipAttributionDeadline;
    private GameplayClientProbe() {}

    private static String run() { return System.getProperty("bc.pack_test.diagnostics.run_id", ""); }
    private static String nonce() { return System.getProperty("bc.pack_test.gameplay_client.nonce", ""); }
    private static Path bridge() {
        if (!"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode"))
                || !run().matches("[A-Za-z0-9_.-]{1,128}") || !UUID.fromString(nonce()).toString().equals(nonce()))
            throw new IllegalStateException("invalid gameplay client Debug binding");
        for (String kind : new String[]{"client", "server", "runtime", "support"})
            if (!System.getProperty("bc.pack_test.diagnostics." + kind + "_sha256", "").matches("[a-f0-9]{64}"))
                throw new IllegalStateException("missing exact gameplay candidate/support binding");
        Path dir = Path.of(System.getProperty("bc.pack_test.diagnostics.dir")).toAbsolutePath().normalize();
        for (Path p = dir; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IllegalStateException("symlink gameplay bridge");
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("missing gameplay bridge");
        return dir;
    }
    public static boolean observing() { return ENABLED && !stopped; }
    public static synchronized long nativeParticleCount(String type) { return PARTICLES.getOrDefault(type, 0L); }

    /** Called only by the native ParticleEngine observer, never creates or replaces particles. */
    public static synchronized void particle(String type, double x, double y, double z) {
        if (!observing()) return;
        PARTICLES.merge(type, 1L, Long::sum);
        if (PARTICLE_SAMPLES.size() < 256) PARTICLE_SAMPLES.add(Map.of("tick", ticks, "nanos", System.nanoTime(), "type", type, "x", x, "y", y, "z", z));
        else particleSamplesDropped++;
    }
    /** Approved bounded exact-call-site attribution, active only in an explicitly armed cut window. */
    public static synchronized void submittedParticle(String type, double x, double y, double z) {
        particle("submitted:" + type, x, y, z);
        if (!observing() || System.nanoTime() >= chipAttributionDeadline || !type.equals("net.minecraft.client.particle.TerrainParticle")) return;
        boolean compat = StackWalker.getInstance().walk(frames -> frames.limit(12).anyMatch(frame ->
                frame.getClassName().equals("com.bettercontent.bettercompatfixes.compat.ChoppingParticles") && frame.getMethodName().equals("emit")));
        if (!compat) return;
        if (COMPAT_CHIPS.size() < 4096) COMPAT_CHIPS.add(Map.of("tick", ticks, "nanos", System.nanoTime(), "x", x, "y", y, "z", z));
        else chipSamplesDropped++;
    }
    /** Read-only native cut progress at continueDestroyBlock return, including completion reset. */
    public static synchronized void cut(BlockPos pos, Direction face, float progress, boolean accepted) {
        if (!observing()) return;
        var mc = Minecraft.getInstance();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tick", ticks); row.put("nanos", System.nanoTime()); row.put("position", List.of(pos.getX(), pos.getY(), pos.getZ()));
        row.put("face", face.getName()); row.put("progress_after_native_call", progress); row.put("accepted", accepted);
        if (mc.level != null) row.put("block_after_native_call", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString());
        if (CUTS.size() < 4096) CUTS.add(row); else cutsDropped++;
    }
    @SubscribeEvent
    public static synchronized void sound(PlaySoundEvent event) {
        if (!observing() || event.getSound() == null) return;
        var sound = event.getSound();
        if (SOUNDS.size() < 4096) SOUNDS.add(Map.of("tick", ticks, "nanos", System.nanoTime(),
                "id", sound.getLocation().toString(), "source", sound.getSource().getName(), "volume", "UNKNOWN: public getter resamples native RNG",
                "pitch", "UNKNOWN: public getter resamples native RNG", "x", sound.getX(), "y", sound.getY(), "z", sound.getZ()));
        else soundsDropped++;
    }
    /** Forge fires after Channel.play on the channel's own executor/context. Query immediately on that
     * event thread, before acquiring the evidence lock. No context swaps or OpenAL error clearing. */
    @SubscribeEvent
    public static void sourceStarted(SoundSourceEvent event) {
        if (!observing()) return;
        boolean playing = event.getChannel().playing();
        long now = System.nanoTime(), tick = ticks;
        var sound = event.getSound(); var asset = sound.getSound();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tick", tick); row.put("nanos", now); row.put("id", sound.getLocation().toString());
        row.put("native_event_thread", Thread.currentThread().getName());
        row.put("resolved_asset", asset.getLocation().toString()); row.put("resolved_asset_path", asset.getPath().toString());
        row.put("instance_volume", "UNKNOWN: AbstractSoundInstance.getVolume resamples native RNG; not queried");
        row.put("instance_pitch", "UNKNOWN: AbstractSoundInstance.getPitch resamples native RNG; not queried");
        row.put("category", sound.getSource().getName());
        row.put("channel_playing_on_native_event_thread", playing); row.put("streaming", asset.shouldStream());
        row.put("verdict", playing ? "NATIVE_CUE_SOURCE_PLAYING" : "NATIVE_SOURCE_NOT_PLAYING");
        row.put("effective_channel_gain", "UNKNOWN: no public getter; no private fields read");
        synchronized (GameplayClientProbe.class) {
            if (SOURCES.size() < 4096) SOURCES.add(row); else sourcesDropped++;
        }
    }
    @SubscribeEvent
    public static synchronized void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        ticks++;
        GameplayCutDriver.tick(Minecraft.getInstance(), ticks);
        Path root = bridge();
        Path request = root.resolve("gameplay-client.request");
        if (pending != null || !Files.exists(request, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS) || Files.size(request) > 4096)
            throw new IllegalStateException("invalid gameplay client request file");
        JsonObject input = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
        var mc = Minecraft.getInstance();
        int next = input.get("sequence").getAsInt();
        String label = input.get("label").getAsString();
        if (!"bc.gameplay_client_request.v1".equals(input.get("schema").getAsString())
                || !run().equals(input.get("run").getAsString()) || !nonce().equals(input.get("nonce").getAsString())
                || !System.getProperty("bc.pack_test.diagnostics.client_sha256").equals(input.get("client_sha256").getAsString())
                || !System.getProperty("bc.pack_test.diagnostics.server_sha256").equals(input.get("server_sha256").getAsString())
                || mc.player == null || mc.level == null || mc.gameMode == null
                || !mc.player.getUUID().toString().equals(input.get("player").getAsString())
                || next <= sequence || !label.matches("[A-Za-z0-9_.-]{1,80}"))
            throw new IllegalStateException("gameplay client request identity/replay mismatch");
        String action = input.get("action").getAsString();
        if (!List.of("arm", "capture", "look", "start-cut", "continue-cut", "stop-cut", "use-block", "close-menu", "bee-window-start", "bee-window-observe", "bee-window-finish", "plant-stock-ready", "emi-personal-open", "emi-equipment-ready", "emi-menu-ready", "emi-order", "emi-snapshot-held", "emi-snapshot-reopen", "emi-inputs-ready", "emi-explicit-focus", "emi-fill", "lifecycle-respawn", "lifecycle-observe", "exit", "stop").contains(action))
            throw new IllegalStateException("unknown gameplay client action");
        if (stopped) throw new IllegalStateException("gameplay client session already stopped");
        Files.move(request, root.resolve("gameplay-client-" + next + "-" + label + ".consumed"));
        sequence = next;
        Map<String, Object> result = identity(input);
        if (action.equals("arm")) {
            // Startup/login observations are first checkpointed, not silently discarded.
            result.put("prior_window", observations());
            GameplayCutDriver.stop(mc);
            PARTICLES.clear(); SOUNDS.clear(); SOURCES.clear(); CUTS.clear(); PARTICLE_SAMPLES.clear(); COMPAT_CHIPS.clear();
            soundsDropped = sourcesDropped = cutsDropped = particleSamplesDropped = chipSamplesDropped = 0; startedTick = ticks;
            chipAttributionDeadline = label.matches("v[12]-cut-(log|dt)-arm") ? System.nanoTime() + 90_000_000_000L : 0;
        } else if (action.equals("capture")) {
            pending = input; pendingFrame = frames; return;
        } else if (action.equals("stop") || action.equals("exit")) {
            GameplayCutDriver.stop(mc);
            result.put("observations", observations()); stopped = true;
        } else {
            boolean colonyUnloadPosition = action.startsWith("bee-window-") && Math.abs(mc.player.getX() - 999488) < 2
                    && Math.abs(mc.player.getZ() - 1000000) < 2 && Math.abs(mc.player.getY() - 316) < 4
                    && mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
            if (!List.of("lifecycle-respawn", "lifecycle-observe").contains(action) && !colonyUnloadPosition) requireFixturePosition(mc);
            if (List.of("look", "close-menu", "use-block").contains(action)) GameplayCutDriver.stop(mc);
            if (NativeKineticOverloadClientProbe.ACTIONS.contains(action)) {
                result.put("native_probe", NativeKineticOverloadClientProbe.run(mc,input));
            } else if (SystemsInteractionClientProbe.ACTIONS.contains(action)) {
                result.put("native_probe", SystemsInteractionClientProbe.run(mc,input));
            } else if (WorldHooksInteractionClientProbe.ACTIONS.contains(action)) {
                result.put("native_probe", WorldHooksInteractionClientProbe.run(mc,input));
            } else if (List.of("lifecycle-respawn", "lifecycle-observe").contains(action)) {
                result.put("native_probe", PlayerLifecycleClientProbe.run(mc, input));
            } else if (action.equals("plant-stock-ready")) {
                var items = input.getAsJsonArray("items");
                if (items.size() != 36) throw new IllegalStateException("native stock page needs exact inventory slot vector");
                boolean ready = mc.player.containerMenu == mc.player.inventoryMenu && mc.screen == null;
                for (int i = 0; i < 36; i++) {
                    var stack = mc.player.getInventory().getItem(i);
                    String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                    ready &= id.equals(items.get(i).getAsString()) && stack.getCount() == (stack.isEmpty() ? 0 : 1);
                }
                result.put("native_probe", Map.of("ready", ready, "origin", "native main inventory slot packet values and closed authoritative menu"));
            } else if (action.equals("emi-personal-open")) {
                result.put("native_probe", EmiClientProbe.openPersonal(mc));
            } else if (action.equals("emi-equipment-ready")) {
                result.put("native_probe", EmiClientProbe.equipmentReady(mc, input.get("path").getAsString()));
            } else if (action.equals("emi-snapshot-held") || action.equals("emi-snapshot-reopen")) {
                result.put("native_probe", EmiClientProbe.snapshotChanged(mc, action.equals("emi-snapshot-reopen")));
            } else if (action.equals("emi-inputs-ready")) {
                result.put("native_probe", EmiClientProbe.inputsReady(mc));
            } else if (action.equals("emi-menu-ready")) {
                result.put("native_probe", EmiClientProbe.ready(mc, input.get("path").getAsString()));
            } else if (action.equals("emi-order")) {
                result.put("native_probe", EmiClientProbe.order(mc));
            } else if (action.equals("emi-explicit-focus")) {
                result.put("native_probe", EmiClientProbe.explicitFocus(mc));
            } else if (action.equals("emi-fill")) {
                result.put("native_probe", EmiClientProbe.fill(mc, input.get("separate").getAsBoolean()));
            } else if (action.equals("bee-window-start")) {
                result.put("native_probe", ColonyClientProbe.start(mc, input.get("state").getAsString(), input.get("control").getAsString()));
            } else if (action.equals("bee-window-observe")) {
                result.put("native_probe", ColonyClientProbe.observe(mc));
            } else if (action.equals("bee-window-finish")) {
                result.put("native_probe", ColonyClientProbe.finish(mc));
            } else if (action.equals("look")) {
                float yaw = input.get("yaw").getAsFloat(), pitch = input.get("pitch").getAsFloat();
                if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90)
                    throw new IllegalStateException("invalid authored camera orientation");
                mc.player.setYRot(yaw); mc.player.setXRot(pitch);
                result.put("origin", "authored_native_camera_fixture");
            } else if (action.equals("close-menu")) {
                mc.player.closeContainer(); mc.setScreen(null);
                result.put("origin", "authored_public_native_menu_close");
            } else if (action.equals("stop-cut")) {
                GameplayCutDriver.stop(mc); mc.gameMode.stopDestroyBlock();
                chipAttributionDeadline = 0;
            }
            else {
                if (mc.gameMode.getPlayerMode() != GameType.SURVIVAL || mc.screen != null)
                    throw new IllegalStateException("native block action requires real Survival world view");
                BlockPos pos = new BlockPos(input.get("x").getAsInt(), input.get("y").getAsInt(), input.get("z").getAsInt());
                if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > 36 || !mc.level.hasChunkAt(pos))
                    throw new IllegalStateException("authored native action exceeds six-block local reach");
                Direction face = Direction.valueOf(input.has("face") ? input.get("face").getAsString() : "UP");
                result.put("block_before", mc.level.getBlockState(pos).toString());
                if (action.equals("start-cut")) GameplayCutDriver.start(mc, input, pos, face, ticks);
                if (action.equals("start-cut") || action.equals("continue-cut")) {
                    result.put("authored_driver_active", GameplayCutDriver.active());
                    result.put("driver_mutation", "reviewed test-only suppression of unrelated no-input release; public native continue once per actual ClientTick END, fixed 90-second deadline; no synthetic input");
                }
                if (action.equals("use-block")) result.put("native_result", mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos).relative(face, .5), face, pos, false)).toString());
                result.put("block_after", mc.level.getBlockState(pos).toString());
                result.put("native_hit_result", mc.hitResult == null ? "none" : mc.hitResult.toString());
                result.put("origin", "authored_public_native_block_interaction");
            }
        }
        write(root, input, result);
        if (action.equals("exit")) mc.stop();
    }
    private static void requireFixturePosition(Minecraft mc) {
        if (!mc.level.dimension().location().toString().equals("minecraft:overworld")
                || Math.abs(mc.player.getX() - 1_000_000) > 128 || Math.abs(mc.player.getZ() - 1_000_000) > 128
                || Math.abs(mc.player.getY() - 316) > 32)
            throw new IllegalStateException("authored gameplay action outside bounded fixture");
    }
    @SubscribeEvent
    public static synchronized void render(TickEvent.RenderTickEvent event) throws Exception {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        frames++;
        if (pending == null || frames <= pendingFrame + 1) return;
        var mc = Minecraft.getInstance();
        JsonObject input = pending;
        if (mc.player == null || mc.level == null || !mc.player.getUUID().toString().equals(input.get("player").getAsString()))
            throw new IllegalStateException("gameplay capture player disappeared");
        Path root = bridge();
        Path image = root.resolve(stem(input) + ".png");
        if (Files.exists(image, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("capture overwrite");
        try (var pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { pixels.writeToFile(image); }
        Map<String, Object> result = identity(input);
        result.put("framebuffer", image.getFileName().toString());
        result.put("framebuffer_width", mc.getMainRenderTarget().width); result.put("framebuffer_height", mc.getMainRenderTarget().height);
        result.put("gui_scale", mc.getWindow().getGuiScale());
        result.put("renderer", Map.of("vendor", GL11.glGetString(GL11.GL_VENDOR), "renderer", GL11.glGetString(GL11.GL_RENDERER),
                "version", GL11.glGetString(GL11.GL_VERSION), "gl_current_program", GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)));
        result.put("native_post_effect", mc.gameRenderer.currentEffect() == null ? "none" : mc.gameRenderer.currentEffect().getName());
        result.put("third_party_shader_state", GameplayShaderObservation.observe(input.has("require_shader") && input.get("require_shader").getAsBoolean()));
        result.put("installed_mods", ModList.get().getMods().stream().map(m -> m.getModId() + "@" + m.getVersion()).sorted().toList());
        result.put("screen_class", mc.screen == null ? "none" : mc.screen.getClass().getName());
        result.put("menu_class", mc.player.containerMenu.getClass().getName());
        result.put("menu_id", mc.player.containerMenu.containerId);
        result.put("slots", mc.player.containerMenu.slots.stream().map(s -> Map.of("index", s.index, "x", s.x, "y", s.y,
                "item", BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString(), "count", s.getItem().getCount())).toList());
        result.put("dimension", mc.level.dimension().location().toString());
        result.put("position", List.of(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        result.put("yaw", mc.player.getYRot()); result.put("pitch", mc.player.getXRot());
        result.put("native_day", mc.level.isDay()); result.put("native_night", mc.level.isNight()); result.put("native_sky_darken", mc.level.getSkyDarken());
        result.put("game_time", mc.level.getGameTime());
        result.put("day_time", mc.level.getDayTime()); result.put("rain", mc.level.getRainLevel(1)); result.put("thunder", mc.level.getThunderLevel(1));
        result.put("game_mode", mc.gameMode.getPlayerMode().getName());
        List<Map<String, Object>> blocks = new ArrayList<>();
        BlockPos center = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-8, -4, -8), center.offset(8, 8, 8))) {
            var state = mc.level.getBlockState(pos);
            if (!state.isAir()) blocks.add(Map.of("position", List.of(pos.getX(), pos.getY(), pos.getZ()), "state", state.toString(),
                    "shape_boxes", state.getShape(mc.level, pos).toAabbs().stream().map(Object::toString).toList()));
        }
        result.put("local_native_block_shapes", blocks); result.put("observations", observations());
        write(root, input, result); pending = null;
    }
    private static Map<String, Object> identity(JsonObject input) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema", "bc.gameplay_client_observation.v1"); row.put("run", run()); row.put("nonce", nonce());
        Map<String, String> binding = new LinkedHashMap<>();
        for (String kind : new String[]{"client", "server", "runtime", "support"}) binding.put(kind + "_sha256", System.getProperty("bc.pack_test.diagnostics." + kind + "_sha256"));
        row.put("candidate_binding", binding);
        row.put("sequence", sequence); row.put("label", input.get("label").getAsString()); row.put("action", input.get("action").getAsString());
        row.put("player", input.get("player").getAsString()); row.put("utc", Instant.now().toString()); row.put("tick", ticks); row.put("frame", frames);
        row.put("audio_backend_environment", System.getenv().getOrDefault("ALSOFT_DRIVERS", "unspecified"));
        row.put("physical_audio_verdict", "NOT_PERFORMED");
        row.put("physical_audio_reason", "No physical listening performed; native sound dispatch is not proof of audible output");
        row.put("human_pointer_verdict", "NOT_PERFORMED");
        row.put("human_pointer_reason", "No physical or synthetic pointer/mouse/scroll/resize performed");
        return row;
    }
    private static Map<String, Object> observations() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("native_source_events", new ArrayList<>(SOURCES)); result.put("native_sources_dropped", sourcesDropped);
        result.put("compat_chip_samples", new ArrayList<>(COMPAT_CHIPS)); result.put("compat_chip_samples_dropped", chipSamplesDropped);
        result.putAll(Map.of("start_tick", startedTick, "end_tick", ticks, "native_particle_counts", new LinkedHashMap<>(PARTICLES),
                "native_particle_samples", new ArrayList<>(PARTICLE_SAMPLES), "sound_dispatch_events", new ArrayList<>(SOUNDS),
                "native_cut_calls", new ArrayList<>(CUTS), "sounds_dropped", soundsDropped, "cut_calls_dropped", cutsDropped,
                "particle_samples_dropped", particleSamplesDropped,
                "coverage", "ParticleEngine creation and submission recorded separately (do not sum); third-party custom renderers outside ParticleEngine not intercepted. Sound dispatch before downstream cancellation/output; not audible verdict."));
        return result;
    }
    private static String stem(JsonObject input) { return "gameplay-client-" + input.get("sequence").getAsInt() + "-" + input.get("label").getAsString(); }
    private static void write(Path root, JsonObject input, Map<String, Object> result) throws Exception {
        Path pendingOutput = root.resolve(stem(input) + ".output-pending");
        Files.writeString(pendingOutput, JSON.toJson(result), StandardOpenOption.CREATE_NEW);
        Files.move(pendingOutput, root.resolve(stem(input) + ".json"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
