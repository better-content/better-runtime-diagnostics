package com.bettercontent.runtimetestsupport.gameplay;

import com.bettercontent.bettercreatekineticloss.api.TransmissionOverloadEvent;
import com.bettercontent.bettercreatekineticloss.config.TransmissionLossConfig;
import com.google.gson.JsonObject;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crank.HandCrankBlockEntity;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Independent nonshipping native overload family. No old Systems session, synthetic network,
 * direct turn/update/sync/calculateStress/cache methods, config/time/stress-field writes or repair.
 * Actual END ticks admit native gameMode.useItemOn crank pulses only. Six phase windows <=40 ticks
 * each, <=8 uses/window => <=48 uses/240 ticks; setup+mutation margin80 =>320 ticks total.
 * Parent must observe/capture live windows BEFORE transitioning; this source never sleeps or waits
 * by advancing time. Parent slow/absent means finite failure, not indefinite source maintenance.
 */
@Mod.EventBusSubscriber(modid="better_runtime_test_support")
public final class NativeKineticOverloadWorldProbe {
    public static final Set<String> ACTIONS=Set.of("overload-start","overload-observe","overload-boundary",
            "overload-repeat","overload-base-excess","overload-recover","overload-reentry","overload-view","overload-finish","overload-abort");
    public static final BlockPos COG=new BlockPos(999888,317,999936), SHAFT=COG.above(), CRANK=COG.above(2);
    public static final BlockPos VIEW=COG.offset(3,1,8);
    private static Session session;
    private NativeKineticOverloadWorldProbe(){}
    private static final class Session {
        final NativeSystemsFixture.Pose pose;final Map<String,String> binding;final int tick;final long wall=System.nanoTime();
        final Map<String,Boolean> checks=new LinkedHashMap<>();final List<Map<String,Object>> observations=new ArrayList<>(),events=new ArrayList<>();
        final Set<BlockPos> mills=new LinkedHashSet<>(),cogs=new LinkedHashSet<>();
        int sequence,requests=1,uses,phaseUses,phaseTick,lastPulse=-100,eventCount,eventBaseline,matchingTicks;
        int critical;double capacityRate,impactRate,crankImpact,shaftImpact,cogImpact,baseRpm,k,maxMult,shaftLoss,cogLoss,plannedRpm;
        String mode,phase="setup",failure="";boolean closed,aborted,phaseReady,timedOut;
        Map<String,Object> current=Map.of();
        Session(ServerPlayer p,JsonObject in){pose=new NativeSystemsFixture.Pose(p);pose.park=VIEW;binding=NativeSystemsFixture.binding(in,false);tick=p.server.getTickCount();sequence=in.get("sequence").getAsInt();
            for(String key:List.of("below_capacity","loss_caused_overload","stable_no_duplicate","base_excess_not_new_loss_event","native_recovery","native_reentry"))checks.put(key,null);}
        void phase(String next){phase=next;phaseTick=pose.p.server.getTickCount();phaseUses=0;lastPulse=phaseTick-5;eventBaseline=eventCount;matchingTicks=0;phaseReady=false;timedOut=false;current=Map.of();}
    }
    public static Map<String,Object> run(ServerPlayer p,JsonObject in){
        NativeSystemsFixture.guard(p,in);String action=in.get("action").getAsString();NativeSystemsFixture.need(ACTIONS.contains(action),"unknown overload action");
        if(action.equals("overload-start")){NativeSystemsFixture.need(session==null,"one overload episode; no reset/replay");
            NativeSystemsFixture.preflight(p.serverLevel(),COG.offset(-1,-1,-1),COG.offset(8,3,9));session=new Session(p,in);setup(session);return report(session,false);}
        Session s=session;NativeSystemsFixture.need(s!=null&&!s.closed&&s.pose.p==p&&s.pose.level==p.serverLevel()&&s.binding.equals(NativeSystemsFixture.binding(in,false)),"overload native session/candidate drift");
        int sequence=in.get("sequence").getAsInt();NativeSystemsFixture.need(sequence>s.sequence,"overload replay");s.sequence=sequence;
        boolean abort=action.equals("overload-abort");if(!abort)NativeSystemsFixture.need(++s.requests<=512&&budget(s),"overload finite320tick/60second/512request budget exceeded");
        switch(action){
            case "overload-observe"->{return report(s,s.phaseReady||s.timedOut||!s.failure.isEmpty());}
            case "overload-boundary"->{require(s,"below");s.phase("boundary");addMill(s,s.critical-1);view(s);}
            case "overload-repeat"->{require(s,"boundary");s.phase("repeat");view(s);}
            case "overload-base-excess"->{require(s,"repeat");NativeSystemsFixture.need(s.critical<8,"base-excess would exceed approved8millstone footprint; remain unqualified");s.phase("base_excess");addCog(s,s.critical);addMill(s,s.critical);view(s);}
            case "overload-recover"->{require(s,"base_excess");s.phase("recovered");removeMill(s,s.critical);removeMill(s,s.critical-1);
                NativeSystemsFixture.breakOwned(s.pose,COG.offset(s.critical,0,0),"create:cogwheel");s.cogs.remove(COG.offset(s.critical,0,0));view(s);}
            case "overload-reentry"->{require(s,"recovered");s.phase("reentry");addMill(s,s.critical-1);view(s);}
            case "overload-view"->view(s);
            case "overload-finish"->{require(s,"reentry");NativeSystemsFixture.need(in.has("capture_complete")&&in.get("capture_complete").getAsBoolean(),"parent capture acknowledgement required before restoring pose");s.pose.restore();s.closed=true;}
            case "overload-abort"->{s.aborted=true;s.pose.restore();s.closed=true;}
            default->throw new IllegalStateException("unexpected overload action");
        }
        return report(s,s.phaseReady||s.closed);
    }
    private static void setup(Session s){
        // Start bottom-up: actual millstone, native Y-axis cog, shaft, crank facing UP. Native
        // temporary stone under other cogs belongs to this fixture and is later natively broken.
        addMill(s,0);addCog(s,0);NativeSystemsFixture.place(s.pose,SHAFT,"create:shaft",true);NativeSystemsFixture.place(s.pose,CRANK,"create:hand_crank",true);
        NativeSystemsFixture.need(s.pose.level.getBlockState(SHAFT).getValue(BlockStateProperties.AXIS)==Direction.Axis.Y
                &&s.pose.level.getBlockState(CRANK).getValue(BlockStateProperties.FACING)==Direction.UP,"native vertical source/shaft orientation unsupported");
        HandCrankBlockEntity crank=crank(s);KineticBlockEntity mill=kinetic(s,COG.below());
        s.capacityRate=crank.calculateAddedStressCapacity();s.impactRate=mill.calculateStressApplied();
        s.crankImpact=crank.calculateStressApplied();s.shaftImpact=kinetic(s,SHAFT).calculateStressApplied();s.cogImpact=kinetic(s,COG).calculateStressApplied();
        // getGeneratedSpeed is the upstream native source getter; no direct turn or fake rotation.
        // Native crank configured RPM is observable after first actual use; plan uses its public
        // native rotation-speed getter to avoid inventing a source RPM, checked again while live.
        NativeSystemsFixture.need(crank.getBlockState().getBlock() instanceof com.simibubi.create.content.kinetics.crank.HandCrankBlock,"native crank block type missing");
        s.plannedRpm=Math.abs(((com.simibubi.create.content.kinetics.crank.HandCrankBlock)crank.getBlockState().getBlock()).getRotationSpeed());
        var c=TransmissionLossConfig.INSTANCE;s.baseRpm=c.getBaseRpm().get();s.k=c.getK().get();s.maxMult=c.getMaxMult().get();s.mode=c.getSpeedMode().get().name();s.shaftLoss=c.getShaft().get();s.cogLoss=c.getCogwheel().get();
        NativeSystemsFixture.need(c.getEnabled().get()&&finite(s.capacityRate)&&finite(s.impactRate)&&s.capacityRate>0&&s.impactRate>0&&s.plannedRpm>0,"native source/impact/loss configuration inapplicable");
        NativeSystemsFixture.need(finite(s.crankImpact)&&finite(s.shaftImpact)&&finite(s.cogImpact)&&s.crankImpact>=0&&s.shaftImpact>=0&&s.cogImpact>=0,"native transmission/source base rates invalid");
        double capacity=s.capacityRate*s.plannedRpm;
        // Enumerate at most six bounded native load counts. Reserve the eighth millstone for
        // actual base-excess negative; no config/capacity edits or unbounded load search.
        for(int n=2;n<=7;n++){double base=base(s,n,n),loss=loss(s,s.plannedRpm,n),below=base(s,n-1,n)+loss,excess=base(s,n+1,n+1);
            if(base<=capacity+1e-5&&base+loss>capacity+1e-5&&below<capacity&&excess>capacity&&loss>0){s.critical=n;break;}}
        NativeSystemsFixture.need(s.critical>=2,"raw native rates/config have no bounded two-sided loss boundary plus base-excess negative within8mills; no config/budget repair");
        double base=base(s,s.critical,s.critical),loss=loss(s,s.plannedRpm,s.critical),below=base(s,s.critical-1,s.critical)+loss;
        for(int i=1;i<s.critical;i++)addCog(s,i);for(int i=1;i<s.critical-1;i++)addMill(s,i);
        s.observations.add(Map.of("case","independent_plan","capacity_rate",s.capacityRate,"impact_rate",s.impactRate,"native_rpm",s.plannedRpm,"critical_millstones",s.critical,"capacity",capacity,"boundary_base",base,"raw_independent_loss",loss,"below_total",below));
        s.phase("below");view(s);
    }
    private static void addCog(Session s,int i){BlockPos q=COG.offset(i,0,0);NativeSystemsFixture.place(s.pose,q,"create:cogwheel",true);NativeSystemsFixture.need(s.pose.level.getBlockState(q).getValue(BlockStateProperties.AXIS)==Direction.Axis.Y,"native cog not Y-axis");s.cogs.add(q);}
    private static void addMill(Session s,int i){BlockPos q=COG.offset(i,-1,0);if(s.pose.stones.contains(q))NativeSystemsFixture.breakOwned(s.pose,q,"minecraft:stone");NativeSystemsFixture.place(s.pose,q,"create:millstone",false);s.mills.add(q);}
    private static void removeMill(Session s,int i){BlockPos q=COG.offset(i,-1,0);NativeSystemsFixture.breakOwned(s.pose,q,"create:millstone");s.mills.remove(q);}
    private static boolean finite(double n){return Double.isFinite(n);}
    private static double base(Session s,int mills,int cogs){return (mills*s.impactRate+cogs*s.cogImpact+s.shaftImpact+s.crankImpact)*s.plannedRpm;}
    private static double loss(Session s,double rpm,int cogs){double normalized=Math.abs(rpm)/Math.max(.001,s.baseRpm);double mult=switch(s.mode){case "NONE"->1;case "LINEAR"->1+s.k*normalized;case "QUADRATIC"->1+s.k*normalized*normalized;default->throw new IllegalStateException("unknown loss scaling");};return (s.shaftLoss+cogs*s.cogLoss)*Math.min(mult,s.maxMult);}
    private static boolean budget(Session s){return s.pose.p.server.getTickCount()-s.tick<=320&&System.nanoTime()-s.wall<=60_000_000_000L;}
    private static void require(Session s,String phase){NativeSystemsFixture.need(s.phase.equals(phase)&&s.phaseReady&&!s.timedOut&&s.failure.isEmpty(),"overload expected ready live "+phase+", actual "+s.phase+" failure="+s.failure);NativeSystemsFixture.need(s.pose.p.server.getTickCount()-s.phaseTick<40,"overload live capture window expired; no repair/retry");
        if(!Boolean.TRUE.equals(sample(s).get("matches"))){s.checks.put(key(phase),false);s.failure="native state/event divergence before phase transition";s.phaseReady=false;throw new IllegalStateException(s.failure);}}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){Session s=session;if(s==null||s.closed||event.phase!=TickEvent.Phase.END||s.phase.equals("setup"))return;
        try{
            NativeSystemsFixture.live(s.pose.p);NativeSystemsFixture.need(s.pose.level==s.pose.p.serverLevel()&&budget(s),"overload native thread/actor/budget drift");
            NativeSystemsFixture.need(s.pose.p.getHealth()>=10&&s.pose.p.getFoodData().getFoodLevel()>=8,"native Survival safety floor reached; stop inputs without health/food repair");
            if(!s.failure.isEmpty()||s.timedOut)return;int now=s.pose.p.server.getTickCount(),elapsed=now-s.phaseTick;
            if(elapsed>=40){s.timedOut=true;s.failure="phase40tick live source/capture window expired; no additional pulses or fabricated history";return;}
            if(now-s.lastPulse>=5){NativeSystemsFixture.need(s.phaseUses<8&&s.uses<48,"overload finite native input budget exhausted");
                s.pose.p.getInventory().selected=s.pose.work;NativeSystemsFixture.need(s.pose.p.getMainHandItem().isEmpty(),"foreign crank input work slot");
                // Normal native block-use entrypoint ONLY. This may actually deny overload use:
                // record failure if so; never invoke crank.turn or mutate inUse/network state.
                var result=s.pose.p.gameMode.useItemOn(s.pose.p,s.pose.level,s.pose.p.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(CRANK).add(0,.499,0),Direction.UP,CRANK,false));
                s.phaseUses++;s.uses++;s.lastPulse=now;NativeSystemsFixture.need(result.consumesAction(),"native crank use denied");
            }
            Map<String,Object> sample=sample(s);s.current=sample;boolean matches=Boolean.TRUE.equals(sample.get("matches"));
            s.matchingTicks=matches?s.matchingTicks+1:0;
            if(s.matchingTicks>=2&&!s.phaseReady){s.phaseReady=true;String key=key(s.phase);s.checks.put(key,true);Map<String,Object> row=new LinkedHashMap<>(sample);row.put("case",key);row.put("passed",true);s.observations.add(row);}
            // Never keep an earlier ready state after actual state/event divergence.
            if(s.phaseReady&&!matches){s.checks.put(key(s.phase),false);s.phaseReady=false;s.failure="native state diverged after readiness in "+s.phase;}
        }catch(RuntimeException failed){s.failure=failed.getClass().getName()+": "+failed.getMessage();s.phaseReady=false;if(s.checks.containsKey(key(s.phase)))s.checks.put(key(s.phase),false);}
    }
    private static Map<String,Object> sample(Session s){
        HandCrankBlockEntity crank=crank(s);double rpm=Math.abs(crank.getTheoreticalSpeed()),generated=Math.abs(crank.getGeneratedSpeed());
        boolean expectedOver=Set.of("boundary","repeat","base_excess","reentry").contains(s.phase);int expectedEvents=Set.of("boundary","reentry").contains(s.phase)?1:0;
        List<Map<String,Object>> members=new ArrayList<>();boolean nativeState=crank.hasNetwork()&&Math.abs(rpm-s.plannedRpm)<.01&&Math.abs(generated-s.plannedRpm)<.01;
        Set<BlockPos> positions=new LinkedHashSet<>();positions.add(CRANK);positions.add(SHAFT);positions.addAll(s.cogs);positions.addAll(s.mills);
        Set<KineticBlockEntity> observed=Collections.newSetFromMap(new IdentityHashMap<>());
        for(BlockPos q:positions){KineticBlockEntity be=kinetic(s,q);observed.add(be);boolean sameNetwork=be.hasNetwork()&&Objects.equals(crank.network,be.network);
            double speed=be.getSpeed(),theory=be.getTheoreticalSpeed();boolean actual=be.isOverStressed();
            boolean match=sameNetwork&&actual==expectedOver&&Math.abs(Math.abs(theory)-s.plannedRpm)<.01&&(expectedOver?Math.abs(speed)<.001:Math.abs(Math.abs(speed)-s.plannedRpm)<.01);
            nativeState&=match;members.add(Map.of("position",NativeSystemsFixture.xyz(q),"native_speed",speed,"native_theoretical_rpm",theory,"native_overstressed",actual,"same_native_network",sameNetwork,"matches",match));
        }
        // Public native membership read only; never calculateStress/sync or alter native fields.
        if(crank.hasNetwork()){var network=crank.getOrCreateNetwork();Set<KineticBlockEntity> nativeMembers=Collections.newSetFromMap(new IdentityHashMap<>());nativeMembers.addAll(network.members.keySet());nativeMembers.addAll(network.sources.keySet());nativeState&=nativeMembers.equals(observed);}
        double capacity=s.capacityRate*s.plannedRpm,base=base(s,s.mills.size(),s.cogs.size()),expectedLoss=loss(s,s.plannedRpm,s.cogs.size());
        boolean arithmetic=expectedOver?(s.phase.equals("base_excess")?base>capacity:base<=capacity+1e-5&&base+expectedLoss>capacity+1e-5):base+expectedLoss<capacity;
        int events=s.eventCount-s.eventBaseline;boolean eventMatch=events==expectedEvents;
        if(expectedEvents==1&&eventMatch){Map<String,Object> last=s.events.get(s.events.size()-1);eventMatch=Math.abs(((Number)last.get("base")).doubleValue()-base)<1e-4&&Math.abs(((Number)last.get("capacity")).doubleValue()-capacity)<1e-4&&Math.abs(((Number)last.get("loss")).doubleValue()-expectedLoss)<1e-4&&Boolean.TRUE.equals(last.get("same_native_network"));}
        Map<String,Object> out=new LinkedHashMap<>();out.put("phase",s.phase);out.put("tick",s.pose.p.server.getTickCount());out.put("members",members);out.put("native_source_generated_rpm",generated);out.put("native_source_theoretical_rpm",rpm);out.put("native_event_delta",events);out.put("expected_event_delta",expectedEvents);out.put("independent_base",base);out.put("independent_capacity",capacity);out.put("independent_raw_loss",expectedLoss);out.put("independent_inequality",arithmetic);out.put("matches",nativeState&&arithmetic&&eventMatch);return out;
    }
    @SubscribeEvent public static void overloaded(TransmissionOverloadEvent event){Session s=session;if(s==null||s.closed||s.pose.level.getBlockEntity(CRANK)==null)return;
        HandCrankBlockEntity crank=crank(s);if(!crank.hasNetwork()||event.getNetwork()!=crank.getOrCreateNetwork())return;
        s.eventCount++;if(s.events.size()<8)s.events.add(Map.of("tick",s.pose.p.server.getTickCount(),"phase",s.phase,"base",event.getBaseStress(),"loss",event.getLoss(),"capacity",event.getCapacity(),"same_native_network",true,"native_source_overstressed_at_event",crank.isOverStressed()));
    }
    private static KineticBlockEntity kinetic(Session s,BlockPos q){var be=s.pose.level.getBlockEntity(q);NativeSystemsFixture.need(be instanceof KineticBlockEntity,"native kinetic BE missing "+q);return (KineticBlockEntity)be;}
    private static HandCrankBlockEntity crank(Session s){var be=s.pose.level.getBlockEntity(CRANK);NativeSystemsFixture.need(be instanceof HandCrankBlockEntity,"native crank absent");return (HandCrankBlockEntity)be;}
    private static String key(String phase){return switch(phase){case "below"->"below_capacity";case "boundary"->"loss_caused_overload";case "repeat"->"stable_no_duplicate";case "base_excess"->"base_excess_not_new_loss_event";case "recovered"->"native_recovery";case "reentry"->"native_reentry";default->"unqualified_setup";};}
    private static void view(Session s){NativeSystemsFixture.pedestal(s.pose,VIEW);Vec3 target=Vec3.atCenterOf(COG.offset(Math.max(0,s.critical-1)/2,0,0));Vec3 eye=new Vec3(VIEW.getX()+.5,VIEW.getY()+s.pose.p.getEyeHeight(),VIEW.getZ()+.5),delta=target.subtract(eye);
        float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90),pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z)));s.pose.p.teleportTo(s.pose.level,VIEW.getX()+.5,VIEW.getY(),VIEW.getZ()+.5,yaw,pitch);}
    private static Map<String,Object> report(Session s,boolean ready){boolean all=s.checks.values().stream().allMatch(Boolean.TRUE::equals);Map<String,Object> out=new LinkedHashMap<>();
        out.put("ready",ready);out.put("passed",ready&&s.phaseReady&&s.failure.isEmpty());out.put("complete",s.closed&&!s.aborted&&all&&s.failure.isEmpty());out.put("closed",s.closed);out.put("phase",s.phase);out.put("checks",new LinkedHashMap<>(s.checks));out.put("observations",List.copyOf(s.observations));out.put("native_committed_events",List.copyOf(s.events));out.put("native_current",s.current);out.put("failure",s.failure);out.put("phase_ready",s.phaseReady);out.put("phase_window_expired",s.timedOut);out.put("candidate_binding",s.binding);
        out.put("native_total_committed_event_count",s.eventCount);
        out.put("server_tick",s.pose.p.server.getTickCount());out.put("phase_elapsed_ticks",s.pose.p.server.getTickCount()-s.phaseTick);out.put("critical_loads",s.critical);out.put("native_uses",s.uses);out.put("phase_native_uses",s.phaseUses);out.put("requests",s.requests);out.put("capture_target",NativeSystemsFixture.xyz(COG));out.put("camera_feet",NativeSystemsFixture.xyz(VIEW));
        out.put("budget",Map.of("actual_ticks",320,"wall_seconds",60,"requests",512,"native_crank_uses",48,"per_phase_ticks",40,"max_millstones",8,"calculation","6*40=240ticks;6*8=48nativeuses;80tick setup/mutation margin. Source windows are finite; capture while live, never extend/reset budgets."));
        out.put("independent_native_base_rates",Map.of("source_capacity_per_rpm",s.capacityRate,"source_impact_per_rpm",s.crankImpact,"shaft_impact_per_rpm",s.shaftImpact,"cog_impact_per_rpm",s.cogImpact,"millstone_impact_per_rpm",s.impactRate));
        out.put("raw_loss_config",Map.of("mode",String.valueOf(s.mode),"base_rpm",s.baseRpm,"k",s.k,"max_multiplier",s.maxMult,"shaft_su",s.shaftLoss,"cog_su",s.cogLoss));
        out.put("native_survival_state",Map.of("health",s.pose.p.getHealth(),"food",s.pose.p.getFoodData().getFoodLevel()));
        out.put("scope","Actual native Survival placement/break/crank game-mode API and actual native committed stress transitions, independent raw-config inequalities and observed source/member speeds. Authored resources; not earned progression, packet aim/reach/mining duration, arbitrary network/config coverage, full kinetic transport or framebuffer review. Any missing positive/recovery remains complete=false. No native force-sync/cache/clock/field repair.");return out;
    }
}
