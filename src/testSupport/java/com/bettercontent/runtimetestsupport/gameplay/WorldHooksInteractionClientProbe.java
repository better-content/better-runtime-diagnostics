package com.bettercontent.runtimetestsupport.gameplay;

import com.bettercontent.betterwildfire.CharredModel;
import com.bettercontent.betterwildfire.RemnantMemory;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only actual client block-entity/model readiness; no preview, input synthesis or capture.
 * Parent moves real clients within16 blocks of FIRE=(1000064,316,999936), uses existing native
 * framebuffer capture and LLM review. case0=x-6(log/X),1=x(stair/E),2=x+6(open trapdoor).
 * Ready establishes native client state/quad availability only, never visible-pixel acceptance.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid="better_runtime_test_support",value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class WorldHooksInteractionClientProbe {
    public static final Set<String> ACTIONS = Set.of("world-hooks-client-ready");
    private static Map<String, String> bound;
    private static UUID owner;
    private static long startedNanos;
    private static int lastSequence, requests;
    private static net.minecraft.client.multiplayer.ClientLevel observedLevel;
    private static BlockPos observedTarget;
    private static boolean observedSmoldering, targetInFrustum;
    private static int matchedTick=-1, renderTick=-1, renders;
    private WorldHooksInteractionClientProbe() {}

    public static Map<String, Object> run(Minecraft mc, JsonObject input) {
        check(Boolean.getBoolean("bc.pack_test.gameplay_client") && mc.isSameThread(), "native client driver/thread required");
        check(ACTIONS.contains(WorldHooksInteractionWorldProbe.text(input, "action")), "unknown world-hooks client action");
        check(mc.player != null && mc.level != null && mc.gameMode != null && mc.getConnection() != null,
                "world-hooks requires actual connected client");
        check(mc.player.getUUID().toString().equals(WorldHooksInteractionWorldProbe.text(input, "player")), "world-hooks client UUID mismatch");
        check(mc.gameMode.getPlayerMode() == GameType.SURVIVAL && mc.level.dimension().equals(Level.OVERWORLD)
                && Math.abs(mc.player.getX() - 1000000) <= 128 && Math.abs(mc.player.getZ() - 1000000) <= 128
                && Math.abs(mc.player.getY() - 316) <= 32, "world-hooks client outside bounded Survival core");
        Map<String, String> binding = WorldHooksInteractionWorldProbe.binding(input, "gameplay_client");
        if (bound == null) { bound = binding; owner = mc.player.getUUID(); startedNanos = System.nanoTime(); }
        check(bound.equals(binding) && owner.equals(mc.player.getUUID()), "world-hooks client session/candidate drift");
        int sequence = WorldHooksInteractionWorldProbe.integer(input, "sequence");
        check(sequence > lastSequence && ++requests <= 128 && System.nanoTime() - startedNanos <= 1_200_000_000_000L,
                "world-hooks client replay/finite request budget");
        lastSequence = sequence;
        int index = WorldHooksInteractionWorldProbe.integer(input, "case");
        check(index < 3, "world-hooks client fire case outside authored palette");
        BlockPos target = new BlockPos(1000064 + index * 6 - 6, 316, 999936);
        BlockState expected = switch (index) {
            case 0 -> Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
            case 1 -> Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            case 2 -> Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
            default -> throw new IllegalStateException("unreachable authored case");
        };
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema", "bc.world_hooks_client_observation.v1"); row.put("binding", binding);
        row.put("player", owner.toString()); row.put("case", index);
        row.put("position", List.of(target.getX(), target.getY(), target.getZ()));
        row.put("client_tick", mc.player.tickCount); row.put("gui_scale", mc.getWindow().getGuiScale());
        row.put("framebuffer_size", List.of(mc.getWindow().getWidth(), mc.getWindow().getHeight()));
        row.put("native_screen", mc.screen == null ? "none" : mc.screen.getClass().getName());
        row.put("expected_source", expected.toString()); row.put("ready", false);
        row.put("visual_acceptance", "PENDING_ACTUAL_FRAMEBUFFER_LLM_REVIEW");
        row.put("limitations", "Native model state/quad readiness is not pixel correctness, second-client synchronization, process-restart or human input evidence. Parent must collect separate same-candidate observations for both real clients and review actual frames.");
        if (mc.screen != null || mc.options.hideGui) { row.put("state", "NEEDS_UNOBSTRUCTED_NATIVE_WORLD_VIEW"); return row; }
        if (mc.player.position().distanceToSqr(Vec3.atCenterOf(target)) > 16 * 16) { row.put("state", "NEEDS_NATIVE_CAMERA_WITHIN_16_BLOCKS"); return row; }
        if (!mc.level.hasChunkAt(target)) { row.put("state", "WAITING_NATIVE_CHUNK"); return row; }
        if (!(mc.level.getBlockEntity(target) instanceof RemnantMemory memory)) {
            row.put("state", "WAITING_NATIVE_REMNANT_BLOCK_ENTITY"); return row;
        }
        row.put("native_source", memory.source().toString());
        if (!memory.source().equals(expected)) { row.put("state", "WAITING_OR_WRONG_SOURCE_SYNC"); return row; }
        BlockState state = mc.level.getBlockState(target);
        var model = mc.getBlockRenderer().getBlockModel(state);
        check(model instanceof CharredModel, "native remnant is using non-source-derived/generic baked model");
        ModelData data = model.getModelData(mc.level, target, state, memory.getModelData());
        check(expected.equals(data.get(RemnantMemory.SOURCE)), "actual native model data lost source state");
        List<Direction> faces = new ArrayList<>(); faces.add(null); faces.addAll(List.of(Direction.values()));
        Set<String> sprites = new LinkedHashSet<>();
        int quads = 0;
        for (Direction face : faces) {
            // Query the actual reloaded terrain baked model, never a fixture preview renderer.
            var values = model.getQuads(state, face, RandomSource.create(expected.getSeed(target)), data, null);
            quads += values.size();
            for (var quad : values) sprites.add(quad.getSprite().contents().name().toString());
        }
        check(quads > 0 && !sprites.isEmpty(), "native source-derived model has no terrain quads");
        check(sprites.stream().noneMatch(value -> value.equals("minecraft:missingno")), "native remnant uses missing sprite");
        check(state.hasProperty(com.bettercontent.betterwildfire.RemnantBlock.SMOLDERING), "native remnant has no smolder phase");
        boolean smoldering = state.getValue(com.bettercontent.betterwildfire.RemnantBlock.SMOLDERING);
        String expectedPrefix = "better_wildfire:" + (smoldering ? "smoldering/" : "charred/");
        check(sprites.stream().allMatch(value -> value.startsWith(expectedPrefix)), "native source palette fell back to ungenerated/generic sprite instead of source-derived charred phase");
        row.put("native_smoldering", smoldering);
        row.put("native_remnant_state", state.toString()); row.put("native_model_class", model.getClass().getName());
        row.put("native_source_nbt", memory.saveWithFullMetadata().toString()); row.put("terrain_quads", quads);
        row.put("terrain_sprites", List.copyOf(sprites));
        if(observedLevel!=mc.level || !target.equals(observedTarget) || observedSmoldering!=smoldering) {
            observedLevel=mc.level;observedTarget=target;observedSmoldering=smoldering;
            matchedTick=mc.player.tickCount;renderTick=-1;renders=0;targetInFrustum=false;
        }
        boolean ready=mc.player.tickCount>matchedTick;
        row.put("ready",ready);row.put("framebuffer_ready",ready&&renderTick>matchedTick&&renders>=2&&targetInFrustum&&!mc.isPaused());
        row.put("render_events_after_match",renders);row.put("last_render_client_tick",renderTick);row.put("target_in_native_frustum",targetInFrustum);
        row.put("state", "NATIVE_MODEL_READY_NOT_VISUALLY_REVIEWED");
        return row;
    }
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void rendered(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if(bound==null || observedTarget==null || event.getStage()!=net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level!=observedLevel || mc.player==null || !mc.player.getUUID().equals(owner)
                || !Boolean.getBoolean("bc.pack_test.gameplay_client") || !"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode"))) return;
        renderTick=mc.player.tickCount;renders=Math.min(1000,renders+1);
        targetInFrustum=event.getFrustum().isVisible(new net.minecraft.world.phys.AABB(observedTarget).inflate(.5));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
