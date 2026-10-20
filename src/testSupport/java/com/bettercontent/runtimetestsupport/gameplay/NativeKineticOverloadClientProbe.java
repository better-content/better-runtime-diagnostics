package com.bettercontent.runtimetestsupport.gameplay;

import com.google.gson.JsonObject;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Read-only native client observer, independent of old Systems. No camera/input synthesis,
 * screenshot capture, custom sync, fake frames, server reads or private access. AFTER_PARTICLES
 * is a real native render event; framebuffer_ready is readiness, NEVER a screenshot verdict.
 * Start once, observe stage during matching live <=40server-tick window; parent captures/reviews
 * actual framebuffer BEFORE server transitions/finish. Finish requires explicit parent ack only;
 * observer complete stays false because server composition and real pixel review are separate.
 */
@Mod.EventBusSubscriber(modid="better_runtime_test_support",value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class NativeKineticOverloadClientProbe {
    public static final Set<String> ACTIONS=Set.of("overload-client-start","overload-client-observe","overload-client-finish","overload-client-abort");
    private static final Set<String> STAGES=Set.of("below","boundary","repeat","base_excess","recovered","reentry");
    private static final BlockPos COG=new BlockPos(999888,317,999936), CRANK=COG.above(2),SHAFT=COG.above();
    private static Session session;
    private NativeKineticOverloadClientProbe(){}
    private static final class Session {
        final ClientLevel level;final UUID player;final Map<String,String> binding;final int startTick,critical;final long wall=System.nanoTime();
        final Set<String> completedStages=new LinkedHashSet<>();String stage="";int sequence,requests=1,firstMatch=-1,lastNative=-1,renderTick=-1,renders;
        boolean frustum,closed;Map<String,Object> last=Map.of();
        Session(Minecraft mc,JsonObject in){level=mc.level;player=mc.player.getUUID();binding=NativeSystemsFixture.binding(in,true);startTick=mc.player.tickCount;sequence=in.get("sequence").getAsInt();critical=in.get("critical_loads").getAsInt();}
    }
    public static Map<String,Object> run(Minecraft mc,JsonObject in){
        guard(mc,in);String action=in.get("action").getAsString();NativeSystemsFixture.need(ACTIONS.contains(action),"unknown overload client action");
        if(action.equals("overload-client-start")){NativeSystemsFixture.need(session==null&&in.has("critical_loads")&&in.get("critical_loads").getAsInt()>=2&&in.get("critical_loads").getAsInt()<=8,"one overload client episode with bounded actual server plan");session=new Session(mc,in);return report(mc,session,false,false);}
        Session s=session;NativeSystemsFixture.need(s!=null&&!s.closed&&s.level==mc.level&&s.player.equals(mc.player.getUUID())&&s.binding.equals(NativeSystemsFixture.binding(in,true)),"overload client native/candidate drift");
        int seq=in.get("sequence").getAsInt();NativeSystemsFixture.need(seq>s.sequence,"overload client replay");s.sequence=seq;
        boolean abort=action.equals("overload-client-abort");if(!abort)NativeSystemsFixture.need(++s.requests<=512&&mc.player.tickCount-s.startTick<=320&&System.nanoTime()-s.wall<=60_000_000_000L,"overload client finite320tick/60second/512request budget exceeded");
        if(action.equals("overload-client-finish")||abort){
            if(!abort)NativeSystemsFixture.need(in.has("capture_complete")&&in.get("capture_complete").getAsBoolean(),"parent real-frame capture acknowledgement required");
            s.closed=true;Map<String,Object> out=report(mc,s,true,false);out.put("aborted",abort);out.put("parent_capture_acknowledged",!abort);return out;
        }
        String stage=in.get("stage").getAsString();NativeSystemsFixture.need(STAGES.contains(stage),"unknown overload client stage");
        if(!stage.equals(s.stage)){NativeSystemsFixture.need(!s.completedStages.contains(stage),"overload client stage replay");if(!s.stage.isEmpty())s.completedStages.add(s.stage);s.stage=stage;s.firstMatch=-1;s.lastNative=-1;s.renderTick=-1;s.renders=0;s.frustum=false;}
        boolean expectedOver=Set.of("boundary","repeat","base_excess","reentry").contains(stage);int mills=Set.of("below","recovered").contains(stage)?s.critical-1:stage.equals("base_excess")?s.critical+1:s.critical;
        NativeSystemsFixture.need(mills<=8,"overload client native workload exceeds approved8mills");int cogs=stage.equals("base_excess")?s.critical+1:s.critical;
        var sourceBlock=mc.level.getBlockState(CRANK).getBlock();
        float expectedRpm=sourceBlock instanceof com.simibubi.create.content.kinetics.crank.HandCrankBlock crankBlock?Math.abs(crankBlock.getRotationSpeed()):Float.NaN;
        List<Map<String,Object>> observed=new ArrayList<>();boolean matches=Float.isFinite(expectedRpm)&&expectedRpm>0;
        List<BlockPos> positions=new ArrayList<>();positions.add(CRANK);positions.add(SHAFT);for(int i=0;i<cogs;i++)positions.add(COG.offset(i,0,0));for(int i=0;i<mills;i++)positions.add(COG.offset(i,-1,0));
        for(BlockPos q:positions){var be=mc.level.getBlockEntity(q);String actual=String.valueOf(ForgeRegistries.BLOCKS.getKey(mc.level.getBlockState(q).getBlock()));
            String expected=q.equals(CRANK)?"create:hand_crank":q.equals(SHAFT)?"create:shaft":q.getY()==COG.getY()?"create:cogwheel":"create:millstone";
            boolean nativeKinetic=be instanceof KineticBlockEntity;boolean over=nativeKinetic&&((KineticBlockEntity)be).isOverStressed();float speed=nativeKinetic?((KineticBlockEntity)be).getSpeed():Float.NaN,theory=nativeKinetic?((KineticBlockEntity)be).getTheoreticalSpeed():Float.NaN;
            // Native source-block RPM getter, not a fabricated speed or a server state read.
            // Server still independently qualifies source capacity/loss/event arithmetic.
            boolean match=actual.equals(expected)&&nativeKinetic&&over==expectedOver&&Float.isFinite(theory)&&Math.abs(Math.abs(theory)-expectedRpm)<.01
                    &&Float.isFinite(speed)&&(expectedOver?Math.abs(speed)<.001:Math.abs(Math.abs(speed)-expectedRpm)<.01);
            matches&=match;Map<String,Object> row=new LinkedHashMap<>();row.put("position",List.of(q.getX(),q.getY(),q.getZ()));row.put("native_block",actual);row.put("native_be",be==null?"MISSING":be.getClass().getName());row.put("overstressed",over);row.put("native_speed",Float.isFinite(speed)?speed:"MISSING");row.put("native_theoretical_rpm",Float.isFinite(theory)?theory:"MISSING");row.put("matches",match);observed.add(row);
        }
        if(stage.equals("recovered"))matches&=mc.level.getBlockState(COG.offset(s.critical-1,-1,0)).isAir()&&mc.level.getBlockState(COG.offset(s.critical,-1,0)).isAir()&&mc.level.getBlockState(COG.offset(s.critical,0,0)).isAir();
        if(!matches){s.firstMatch=-1;s.renderTick=-1;s.renders=0;s.frustum=false;}else if(s.firstMatch<0)s.firstMatch=mc.player.tickCount;
        s.lastNative=mc.player.tickCount;boolean ready=matches&&s.firstMatch>=0&&mc.player.tickCount>s.firstMatch;
        boolean framebuffer=ready&&s.renderTick>s.firstMatch&&s.renders>=2&&s.frustum&&mc.screen==null&&!mc.isPaused()&&mc.getMainRenderTarget().width>0&&mc.getMainRenderTarget().height>0;
        s.last=Map.of("members",observed,"actual_native_client_tick",mc.player.tickCount,"first_native_match_tick",s.firstMatch,"expected_overstressed",expectedOver,"actual_expected_millstones",mills);
        return report(mc,s,ready,framebuffer);
    }
    @SubscribeEvent public static void rendered(RenderLevelStageEvent event){Session s=session;if(s==null||s.closed||s.firstMatch<0||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES)return;
        Minecraft mc=Minecraft.getInstance();if(mc.level!=s.level||mc.player==null||!s.player.equals(mc.player.getUUID()))return;
        s.renderTick=mc.player.tickCount;s.renders=Math.min(512,s.renders+1);s.frustum=event.getFrustum().isVisible(new AABB(COG.offset(-1,-1,-1),COG.offset(Math.min(8,s.critical+1),3,1)));
    }
    private static void guard(Minecraft mc,JsonObject in){NativeSystemsFixture.need(mc.isSameThread()&&mc.player!=null&&mc.level!=null&&mc.getConnection()!=null&&mc.gameMode!=null,"native connected client thread required");
        NativeSystemsFixture.need(Boolean.getBoolean("bc.pack_test.gameplay_client")&&"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode")),"overload client disabled");
        NativeSystemsFixture.need(mc.player.isAlive()&&mc.player.getGameProfile().getName().matches("SmokeClient[12]")&&mc.gameMode.getPlayerMode()==GameType.SURVIVAL&&mc.level.dimension().equals(Level.OVERWORLD),"overload native Survival client required");
        NativeSystemsFixture.need(Math.abs(mc.player.getX()-1000000)<=128&&Math.abs(mc.player.getZ()-1000000)<=128&&Math.abs(mc.player.getY()-316)<=32,"client outside exact continuous ordinary core");
        NativeSystemsFixture.bounds(mc.player.blockPosition());NativeSystemsFixture.need(in.has("player")&&mc.player.getUUID().toString().equals(in.get("player").getAsString())&&in.has("sequence")&&in.get("sequence").getAsInt()>0,"overload client actor/sequence mismatch");}
    private static Map<String,Object> report(Minecraft mc,Session s,boolean ready,boolean framebuffer){Map<String,Object> out=new LinkedHashMap<>();out.put("ready",ready);out.put("passed",ready&&!s.closed);out.put("complete",false);out.put("closed",s.closed);out.put("stage",s.stage);out.put("candidate_binding",s.binding);out.put("critical_loads_from_bound_server_plan",s.critical);out.put("native_observation",s.last);out.put("client_tick",mc.player.tickCount);out.put("framebuffer_ready",framebuffer);out.put("render_events_after_match",s.renders);out.put("native_render_client_tick",s.renderTick);out.put("target_in_native_frustum",s.frustum);out.put("framebuffer_size",List.of(mc.getMainRenderTarget().width,mc.getMainRenderTarget().height));
        out.put("capture_target",List.of(COG.getX(),COG.getY(),COG.getZ()));out.put("limitation","Native BE overstress/speed/state arrival and real AFTER_PARTICLES/frustum readiness only. No frames captured, no pixel/render-semantic verdict, no server event/capacity/loss or protocol proof. Parent must bind and capture/review real framebuffer within corresponding live native source phase before next mutation/server finish; complete always false here.");return out;}
}
