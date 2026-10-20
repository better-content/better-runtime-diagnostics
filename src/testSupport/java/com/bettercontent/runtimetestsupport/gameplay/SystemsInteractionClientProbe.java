package com.bettercontent.runtimetestsupport.gameplay;

import com.bettercontent.betterairtightmachines.chemistry.AirtightUpgradeHolder;
import com.google.gson.JsonObject;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** Read-only native client observation. No view/input synthesis, fake frames, screenshot writes,
 * server access, custom sync or private access. ready means two distinct actual client ticks agree;
 * framebuffer_ready additionally needs a subsequent real render event with the target in frustum.
 * Neither flag is a screenshot verdict. Parent must capture the ACTUAL Minecraft framebuffer,
 * bind its digest to this support/candidate and independently review pixels.
 * Sequence: systems-client-observe(stage=sealed|released|kinetic32|kinetic64|topology|sticker),
 * poll same stage until ready, capture when framebuffer_ready; then next stage. Finish explicitly.
 */
@Mod.EventBusSubscriber(modid="better_runtime_test_support",value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class SystemsInteractionClientProbe {
    public static final Set<String> ACTIONS=Set.of("systems-client-observe","systems-client-finish");
    private static final Set<String> STAGES=Set.of("sealed","released","kinetic32","kinetic64","topology","sticker");
    private static final BlockPos BASIN=new BlockPos(999_936,316,999_920);
    private static final BlockPos MOTOR=new BlockPos(999_952,316,999_920);
    private static final BlockPos STICKER=new BlockPos(999_952,316,999_932);
    private static Session session;
    private SystemsInteractionClientProbe() {}
    private static final class Session {
        final ClientLevel level;
        final UUID player;
        final Map<String,String> binding;
        final long started=System.nanoTime();
        final int startTick;
        String stage="";
        int sequence,requests,matchedTick=-1,renderTick=-1,renders;
        boolean targetInFrustum;
        Session(Minecraft mc,JsonObject in){level=mc.level;player=mc.player.getUUID();binding=binding(in);startTick=mc.player.tickCount;}
    }
    public static Map<String,Object> run(Minecraft mc,JsonObject in) {
        need(mc.isSameThread()&&mc.player!=null&&mc.level!=null&&mc.getConnection()!=null&&mc.gameMode!=null,"native connected client thread required");
        need(Boolean.getBoolean("bc.pack_test.gameplay_client")&&"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode")),"systems client fixture disabled");
        need(mc.player.getGameProfile().getName().matches("SmokeClient[12]")&&mc.gameMode.getPlayerMode()==GameType.SURVIVAL
                &&mc.level.dimension().equals(Level.OVERWORLD)&&Math.abs(mc.player.getX()-1_000_000)<=128
                &&Math.abs(mc.player.getZ()-1_000_000)<=128&&Math.abs(mc.player.getY()-316)<=32,"client outside normal Survival core");
        String action=in.get("action").getAsString();need(ACTIONS.contains(action),"unknown systems client action");
        need(in.get("player").getAsString().equals(mc.player.getUUID().toString()),"systems client player mismatch");
        if(session==null){need(action.equals("systems-client-observe"),"no client episode");session=new Session(mc,in);}
        Session s=session;need(s.level==mc.level&&s.player.equals(mc.player.getUUID())&&s.binding.equals(binding(in)),"client systems session/candidate drift");
        int seq=in.get("sequence").getAsInt();need(seq>s.sequence,"client systems replay");s.sequence=seq;
        need(++s.requests<=256&&mc.player.tickCount-s.startTick<=12_000&&System.nanoTime()-s.started<=600_000_000_000L,"client systems finite budget exceeded");
        if(action.equals("systems-client-finish")){
            Map<String,Object> result=Map.of("ready",true,"complete",false,"origin","observer closed; server transactions and framebuffer review remain independent");
            session=null;return result;
        }
        String stage=in.get("stage").getAsString();need(STAGES.contains(stage),"unknown systems client stage");
        if(!stage.equals(s.stage)){s.stage=stage;s.matchedTick=-1;s.renderTick=-1;s.renders=0;s.targetInFrustum=false;}
        Map<String,Object> observed=new LinkedHashMap<>();
        boolean matches=false;
        if(stage.equals("sealed")||stage.equals("released")){
            var be=mc.level.getBlockEntity(BASIN);
            boolean nativeHolder=be instanceof AirtightUpgradeHolder;
            boolean sealed=nativeHolder&&((AirtightUpgradeHolder)be).isAirtight();
            observed.put("native_tile",be==null?"MISSING":be.getClass().getName());observed.put("airtight",sealed);
            observed.put("native_basin_state",mc.level.getBlockState(BASIN).toString());
            observed.put("release_target_state",mc.level.getBlockState(BASIN.above()).toString());
            matches=nativeHolder&&(stage.equals("sealed")==sealed);
            // AdPother can move between network updates; no invented client persistence of one gas voxel.
        }else if(stage.equals("sticker")){
            var state=mc.level.getBlockState(STICKER);
            boolean exists=String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock())).equals("create:sticker");
            observed.put("native_sticker_state",state.toString());
            matches=exists&&state.hasProperty(BlockStateProperties.EXTENDED)&&!state.getValue(BlockStateProperties.EXTENDED);
        }else{
            int rpm=stage.equals("kinetic32")?32:64;int count=stage.equals("topology")?1:2;
            var motor=mc.level.getBlockState(MOTOR);
            if(motor.hasProperty(BlockStateProperties.FACING)){
                Direction direction=motor.getValue(BlockStateProperties.FACING);
                List<Map<String,Object>> shafts=new ArrayList<>();matches=true;
                for(int i=1;i<=count;i++){
                    BlockPos q=MOTOR.relative(direction,i);var be=mc.level.getBlockEntity(q);
                    boolean shaft=String.valueOf(ForgeRegistries.BLOCKS.getKey(mc.level.getBlockState(q).getBlock())).equals("create:shaft");
                    float actual=be instanceof KineticBlockEntity k?k.getTheoreticalSpeed():Float.NaN;
                    boolean match=shaft&&Float.isFinite(actual)&&Math.abs(Math.abs(actual)-rpm)<.01;
                    matches&=match;shafts.add(Map.of("position",List.of(q.getX(),q.getY(),q.getZ()),"native_rpm",Float.isFinite(actual)?actual:"MISSING","matches",match));
                }
                if(count==1)matches&=mc.level.getBlockState(MOTOR.relative(direction,2)).isAir();
                observed.put("shafts",shafts);observed.put("motor_state",motor.toString());
            }
        }
        if(!matches){s.matchedTick=-1;s.renderTick=-1;s.renders=0;}
        else if(s.matchedTick<0)s.matchedTick=mc.player.tickCount;
        boolean ready=matches&&s.matchedTick>=0&&mc.player.tickCount>s.matchedTick;
        boolean framebuffer=ready&&s.renderTick>s.matchedTick&&s.renders>=2&&s.targetInFrustum
                &&mc.screen==null&&!mc.isPaused()&&mc.getMainRenderTarget().width>0&&mc.getMainRenderTarget().height>0;
        Map<String,Object> out=new LinkedHashMap<>();out.put("ready",ready);out.put("passed",ready);out.put("complete",false);
        out.put("stage",stage);out.put("candidate_binding",s.binding);out.put("client_tick",mc.player.tickCount);
        out.put("first_matching_native_tick",s.matchedTick);out.put("native_observation",observed);
        out.put("framebuffer_ready",framebuffer);out.put("render_events_after_match",s.renders);out.put("last_render_client_tick",s.renderTick);
        out.put("target_in_native_frustum",s.targetInFrustum);BlockPos target=target(stage);
        out.put("capture_target",List.of(target.getX(),target.getY(),target.getZ()));
        out.put("framebuffer_size",List.of(mc.getMainRenderTarget().width,mc.getMainRenderTarget().height));
        out.put("limitation","Native BE/state arrival only, NOT server conservation, packet ordering adversarial cases, visible lid/rope semantics or screenshot acceptance. No frames captured by this observer; complete stays false until parent composes server checks and reviews real frames. Parent must aim camera without this observer synthesizing keys/pointers.");
        return out;
    }
    @SubscribeEvent public static void rendered(RenderLevelStageEvent event){
        Session s=session;if(s==null||s.matchedTick<0||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES)return;
        Minecraft mc=Minecraft.getInstance();if(mc.level!=s.level||mc.player==null||!s.player.equals(mc.player.getUUID()))return;
        s.renderTick=mc.player.tickCount;s.renders=Math.min(1_000,s.renders+1);
        s.targetInFrustum=event.getFrustum().isVisible(new AABB(target(s.stage)).inflate(.5));
    }
    private static BlockPos target(String stage){return stage.equals("sealed")||stage.equals("released")?BASIN:stage.equals("sticker")?STICKER:MOTOR;}
    private static Map<String,String> binding(JsonObject in){
        Map<String,String>b=new LinkedHashMap<>();
        for(String k:List.of("run","nonce","player")){need(in.has(k)&&in.get(k).isJsonPrimitive(),"missing client binding "+k);b.put(k,in.get(k).getAsString());}
        need(b.get("run").equals(System.getProperty("bc.pack_test.diagnostics.run_id"))
                &&b.get("nonce").equals(System.getProperty("bc.pack_test.gameplay_client.nonce")),"client systems run/nonce mismatch");
        for(String kind:List.of("client","server","runtime","support")){
            String key=kind+"_sha256",hash=System.getProperty("bc.pack_test.diagnostics."+key,"");
            need(hash.matches("[a-f0-9]{64}")&&(!in.has(key)||hash.equals(in.get(key).getAsString())),"client systems candidate mismatch "+key);b.put(key,hash);
        }
        return Map.copyOf(b);
    }
    private static void need(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
}
