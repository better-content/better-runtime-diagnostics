package com.bettercontent.runtimetestsupport.gameplay;

import com.bettercontent.betterairtightmachines.chemistry.AirtightUpgradeHolder;
import com.bettercontent.betterchemlibhazards.api.event.ChemicalOutcomeEvent;
import com.bettercontent.bettercreatekineticloss.config.TransmissionLossConfig;
import com.bettercontent.betteroc2rcreatecontrols.api.events.MachineOperationEvent;
import com.bettercontent.betteroc2rwirelessmessaging.api.WirelessMessageReceivedEvent;
import com.bettercontent.betteroc2rwirelessmessaging.blockentity.WirelessRelayBlockEntity;
import com.google.gson.*;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import li.cil.oc2.api.bus.device.Device;
import li.cil.oc2.api.bus.device.provider.BlockDeviceQuery;
import li.cil.oc2.api.bus.device.provider.ItemDeviceQuery;
import li.cil.oc2.api.bus.device.rpc.*;
import li.cil.oc2.common.bus.device.provider.Providers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Nonshipping authored fixture. No product handler/broker calls, private access, clock edits or restart.
 * RPC scope: real registered provider discovery, ObjectDevice method selection/deserialization/invoke
 * on real native tiles. ItemDeviceQuery is an explicit fixture query, NOT an installed/booted guest,
 * UART transport or automatic OC2 bus discovery. Creative motor and supplied resources are authored.
 * All state-changing actions are one-shot. Poll ONLY the corresponding *-observe action.
 *
 * Sequence: start -> sealed-observe (>=45 ticks) -> unseal-blocked -> blocked-observe (>=45)
 * -> release -> released-observe (>=1 real tick + actual release event) -> rpc-wireless
 * -> kinetic32-observe -> speed64 -> kinetic64-observe -> topology-remove -> topology-observe
 * -> stale-negative -> finish. Inspect each passed=false result, do not retry an action as a repair.
 * Client may observe sealed/released/kinetic32/kinetic64/topology/sticker before later mutations.
 */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support")
public final class SystemsInteractionWorldProbe {
    public static final Set<String> ACTIONS = Set.of("systems-start", "systems-sealed-observe",
            "systems-unseal-blocked", "systems-blocked-observe", "systems-release", "systems-released-observe",
            "systems-rpc-wireless", "systems-kinetic32-observe", "systems-speed64", "systems-kinetic64-observe",
            "systems-topology-remove", "systems-topology-observe", "systems-stale-negative", "systems-view", "systems-finish");
    public static final BlockPos BASIN = new BlockPos(999_936, 316, 999_920);
    public static final BlockPos MOTOR = new BlockPos(999_952, 316, 999_920);
    public static final BlockPos STICKER = new BlockPos(999_952, 316, 999_932);
    public static final BlockPos HOST_A = new BlockPos(999_972, 316, 999_920);
    public static final BlockPos HOST_B = new BlockPos(999_972, 316, 999_926);
    public static final BlockPos RELAY = new BlockPos(999_976, 316, 999_920);
    private static final String UPGRADE = "better_airtight_machines:airtight_upgrade";
    private static final String GAS = "chemlib:carbon_dioxide";
    private static final Gson GSON = new Gson();
    private static Session session;
    private SystemsInteractionWorldProbe() {}

    private static final class Session {
        final ServerPlayer player;
        final ServerLevel level;
        final Map<String,String> binding;
        final String family;
        final Set<String> required;
        final long startedWall = System.nanoTime();
        final int startedTick, oldSelected, workSlot, refundSlot;
        final Vec3 origin;
        final float yaw, pitch;
        final Map<String,Boolean> cases = new LinkedHashMap<>();
        final List<Map<String,Object>> observations = new ArrayList<>();
        final Set<BlockPos> blockers = new LinkedHashSet<>();
        final List<Map<String,Object>> receiptWitnesses = new ArrayList<>();
        boolean receiptFieldsValid = true;
        String phase = "starting";
        int phaseTick, lastSequence, requests, gasEvents, machineEvents, wirelessEvents;
        Map<String,Object> releaseWitness;
        Direction shaftDirection;
        RPCDevice motorDevice, stickerDevice, sender, receiver;
        BlockEntity originalSticker;
        boolean finished;
        Session(ServerPlayer p, JsonObject in) {
            player=p; level=p.serverLevel(); binding=binding(in); startedTick=p.server.getTickCount();
            family=in.has("family")?in.get("family").getAsString():"all";
            required=switch(family) {
                case "all" -> Set.of("seal_payment_noop","sealed_gas","blocked_gas","released_gas","rpc_committed_noop","wireless_fe_delivery_denials","kinetic32","kinetic64","topology_invalidation","stale_device_denial");
                case "seals" -> Set.of("seal_payment_noop","sealed_gas","blocked_gas","released_gas");
                case "kinetics" -> Set.of("kinetic32","kinetic64","topology_invalidation");
                case "provider-api" -> Set.of("rpc_committed_noop");
                case "wireless" -> Set.of("wireless_fe_delivery_denials");
                default -> throw new IllegalStateException("unknown closed systems family");
            };
            phaseTick=startedTick; lastSequence=in.get("sequence").getAsInt(); oldSelected=p.getInventory().selected;
            origin=p.position(); yaw=p.getYRot(); pitch=p.getXRot();
            List<Integer> empty=new ArrayList<>();
            for(int i=0;i<9;i++) if(p.getInventory().getItem(i).isEmpty()) empty.add(i);
            need(empty.size()>=2,"two empty native hotbar slots required; no foreign item is moved");
            workSlot=empty.get(0); refundSlot=empty.get(1);
            for(String key:List.of("seal_payment_noop", "sealed_gas", "blocked_gas", "released_gas",
                    "rpc_committed_noop", "wireless_fe_delivery_denials", "kinetic32", "kinetic64",
                    "topology_invalidation", "stale_device_denial")) cases.put(key,null);
        }
        boolean family(String name) {return family.equals("all")||family.equals(name);}
        void phase(String next) {phase=next;phaseTick=player.server.getTickCount();}
        int elapsed(){return player.server.getTickCount()-phaseTick;}
    }

    public static Map<String,Object> run(ServerPlayer p, JsonObject in) {
        guard(p,in);
        String action=in.get("action").getAsString(); need(ACTIONS.contains(action),"unknown systems action");
        if(action.equals("systems-start")) {
            need(session==null,"systems episode already exists; no silent replay/reset");
            session=new Session(p,in);
            start(session);
            return report(session,false);
        }
        Session s=session; need(s!=null && !s.finished,"no active systems episode");
        need(s.player==p && s.level==p.serverLevel() && s.binding.equals(binding(in)),"systems identity/candidate drift");
        int seq=in.get("sequence").getAsInt();need(seq>s.lastSequence,"systems action replay");s.lastSequence=seq;
        need(++s.requests<=256 && p.server.getTickCount()-s.startedTick<=12_000
                && System.nanoTime()-s.startedWall<=600_000_000_000L,"systems finite request/tick/wall budget exceeded");
        switch(action) {
            case "systems-sealed-observe" -> {
                phase(s,"sealed"); if(s.elapsed()<45)return report(s,false);
                record(s,"sealed_gas",sealed(s) && gasCount(s)==1 && s.gasEvents==0,
                        Map.of("airtight",sealed(s),"gas_count",gasCount(s),"release_events",s.gasEvents));
                s.phase("sealed_observed");
            }
            case "systems-unseal-blocked" -> {
                phase(s,"sealed_observed");
                // Closed radius-three native release neighborhood, entirely checked before any fill.
                for(BlockPos q:BlockPos.betweenClosed(BASIN.offset(-3,-3,-3),BASIN.offset(3,3,3))) {
                    if(q.equals(BASIN))continue;
                    need(s.level.hasChunkAt(q),"gas denial neighbor not loaded");
                    need(s.level.getBlockState(q).isAir() || s.level.getBlockState(q).is(Blocks.STONE),"foreign gas-denial neighbor "+q);
                }
                for(BlockPos q:BlockPos.betweenClosed(BASIN.offset(-3,-3,-3),BASIN.offset(3,3,3))) {
                    if(q.equals(BASIN)||!s.level.getBlockState(q).isAir())continue;
                    need(s.level.setBlock(q,Blocks.STONE.defaultBlockState(),3),"denial blocker placement failed");
                    s.blockers.add(q.immutable());
                }
                need(p.getInventory().getItem(s.workSlot).isEmpty(),"fixture hand not empty");
                p.getInventory().selected=s.workSlot; p.setShiftKeyDown(true);
                try { use(p,BASIN,InteractionHand.MAIN_HAND); } finally {p.setShiftKeyDown(false);}
                retreat(s);
                boolean refund=p.getInventory().getItem(s.refundSlot).is(item(UPGRADE).getItem())
                        &&p.getInventory().getItem(s.refundSlot).getCount()==2;
                // Inventory.add may choose an earlier EMPTY slot. Count exact native vector instead of guessing it.
                int total=countPlayer(p,UPGRADE);
                record(s,"seal_payment_noop",Boolean.TRUE.equals(s.cases.get("seal_payment_noop")) && !sealed(s) && total==2,
                        Map.of("airtight",sealed(s),"upgrade_total",total,"preferred_refund_slot",refund));
                s.phase("blocked");
            }
            case "systems-blocked-observe" -> {
                phase(s,"blocked");if(s.elapsed()<45)return report(s,false);
                record(s,"blocked_gas",!sealed(s)&&gasCount(s)==1&&s.gasEvents==0,
                        Map.of("gas_count",gasCount(s),"release_events",s.gasEvents));s.phase("blocked_observed");
            }
            case "systems-release" -> {
                phase(s,"blocked_observed");BlockPos q=BASIN.above();
                need(s.blockers.contains(q)&&s.level.getBlockState(q).is(Blocks.STONE),"foreign release target");
                need(s.level.setBlock(q,Blocks.AIR.defaultBlockState(),3),"release opening failed");
                s.blockers.remove(q);s.phase("releasing");
            }
            case "systems-released-observe" -> {
                phase(s,"releasing");if(s.gasEvents==0&&s.elapsed()<100)return report(s,false);
                record(s,"released_gas",gasCount(s)==0&&s.gasEvents==1&&s.releaseWitness!=null
                                &&Boolean.TRUE.equals(s.releaseWitness.get("native_pollutant"))
                                &&Boolean.TRUE.equals(s.releaseWitness.get("same_native_holder"))
                                &&Integer.valueOf(0).equals(s.releaseWitness.get("remaining_gas")),
                        Map.of("gas_count",gasCount(s),"release_events",s.gasEvents,
                                "same_callback_native_witness",s.releaseWitness==null?Map.of():s.releaseWitness));
                s.phase("released");
            }
            case "systems-rpc-wireless" -> {
                need(s.family("provider-api")||s.family("wireless"),"RPC/wireless action outside selected family");
                phase(s,s.family.equals("all")?"released":"independent_ready");
                if(s.family("provider-api")) rpc(s);
                if(s.family("wireless")) wireless(s);
                s.phase("rpc_complete");
            }
            case "systems-kinetic32-observe" -> {
                need(s.family("kinetics"),"kinetic action outside selected family");
                phase(s,s.family.equals("all")?"rpc_complete":"kinetics_ready");if(!kineticReady(s,32,2)&&s.elapsed()<100)return report(s,false);
                kinetic(s,"kinetic32",32,2);s.phase("kinetic32_observed");
            }
            case "systems-speed64" -> {
                phase(s,"kinetic32_observed");
                if(s.family.equals("all")) invoke(s.motorDevice,"setGeneratedSpeed",64); else nativeMotorSpeed(s,64);
                s.phase("speed64");
            }
            case "systems-kinetic64-observe" -> {
                phase(s,"speed64");if(!kineticReady(s,64,2)&&s.elapsed()<100)return report(s,false);
                kinetic(s,"kinetic64",64,2);s.phase("kinetic64_observed");
            }
            case "systems-topology-remove" -> {
                phase(s,"kinetic64_observed");BlockPos end=MOTOR.relative(s.shaftDirection,2);
                need(blockId(s.level,end).equals("create:shaft"),"foreign end shaft");
                near(p,end);need(p.gameMode.destroyBlock(end),"native Survival shaft break denied");retreat(s);s.phase("topology");
            }
            case "systems-topology-observe" -> {
                phase(s,"topology");if(!kineticReady(s,64,1)&&s.elapsed()<100)return report(s,false);
                kinetic(s,"topology_invalidation",64,1);s.phase("topology_observed");
            }
            case "systems-stale-negative" -> {
                need(s.family.equals("all"),"cached adapter lifetime diagnostic is not a registered-bus contract");
                phase(s,"topology_observed");
                near(p,STICKER);need(p.gameMode.destroyBlock(STICKER),"native sticker break denied");
                var provider=Providers.blockDeviceProviderRegistry().getValue(id("better_oc2r_create_controls:sticker"));
                need(provider!=null,"registered sticker provider missing");
                boolean absent=!provider.getDevice(blockQuery(s.level,STICKER)).isPresent();
                boolean returnedTrue=false;String denial="";
                try{returnedTrue=Boolean.TRUE.equals(invoke(s.stickerDevice,"extend"));}
                catch(RpcDenied expected){denial=expected.getMessage();}
                boolean stillAir=s.level.getBlockState(STICKER).isAir();
                record(s,"stale_device_denial",absent&&!returnedTrue&&stillAir,
                        Map.of("fresh_query_absent",absent,"stale_rpc_returned_true",returnedTrue,"native_position_air",stillAir,
                                "old_tile_removed",s.originalSticker.isRemoved(),"denial",denial));
                retreat(s);s.phase("stale_observed");
            }
            case "systems-view" -> {
                String target=in.get("target").getAsString();
                BlockPos q=switch(target){case "basin" -> BASIN;case "motor" -> MOTOR;case "sticker" -> STICKER;
                    default -> throw new IllegalStateException("unknown native systems view target");};
                BlockPos feet=q.offset(0,0,8);pedestal(s,feet);
                p.teleportTo(s.level,feet.getX()+.5,feet.getY(),feet.getZ()+.5,180,12);
                // Only authored server teleport/camera pose; does not advance or reset a transaction phase.
            }
            case "systems-finish" -> {
                phase(s,switch(s.family){case "all" -> "stale_observed";case "seals" -> "released";
                    case "kinetics" -> "topology_observed";default -> "rpc_complete";});
                // Retain fixture blocks/resources/evidence. Never rewrite original player inventory/NBT.
                need(p.getInventory().getItem(s.workSlot).isEmpty(),"foreign hand at finish");
                p.getInventory().selected=s.oldSelected;
                p.teleportTo(s.level,s.origin.x,s.origin.y,s.origin.z,s.yaw,s.pitch);
                s.phase("complete");s.finished=true;
            }
            default -> throw new IllegalStateException("unsupported systems action");
        }
        return report(s,true);
    }

    private static void start(Session s) {
        ServerPlayer p=s.player;
        if(s.family("seals")) need(countPlayer(p,UPGRADE)==0,"fixture requires no preexisting upgrade stacks for an independent refund ledger");
        // Preflight only this independent family's footprint; default all retains the original sequence.
        List<BlockPos> centers=new ArrayList<>();
        if(s.family("seals")) centers.add(BASIN);
        if(s.family("kinetics")) centers.add(MOTOR);
        if(s.family("provider-api")) centers.add(STICKER);
        if(s.family("wireless")) centers.addAll(List.of(HOST_A,HOST_B,RELAY));
        for(BlockPos center:centers) {
            for(BlockPos q:BlockPos.betweenClosed(center.offset(-3,-3,-3),center.offset(3,3,3)))
                need(s.level.hasChunkAt(q),"systems footprint requires already loaded chunks "+q);
            need(s.level.getBlockState(center).isAir(),"foreign fixture block "+center);
        }
        pedestal(s,new BlockPos(999_944,316,999_940));
        // Fresh-query relay search must not select an unrelated powered apparatus.
        if(s.family("wireless")) for(BlockPos q:BlockPos.betweenClosed(HOST_A.offset(-16,-16,-16),HOST_A.offset(16,16,16)))
            if(s.level.hasChunkAt(q))need(!(s.level.getBlockEntity(q) instanceof WirelessRelayBlockEntity),"foreign nearby relay");
        if(s.family("seals")) {
        place(s,BASIN,"create:basin");
        need(s.level.getBlockEntity(BASIN) instanceof BasinBlockEntity,"native basin absent");
        p.getInventory().selected=s.workSlot;giveHand(s,UPGRADE,2);use(p,BASIN,InteractionHand.MAIN_HAND);
        boolean paid=sealed(s)&&p.getMainHandItem().getCount()==1;
        IItemHandler h=items(s.level.getBlockEntity(BASIN));
        use(p,BASIN,InteractionHand.MAIN_HAND);
        // A sealed basin may perform its ordinary native item insertion. That is NOT a second seal payment.
        boolean noop=sealed(s)&&countPlayer(p,UPGRADE)+countHandler(h,UPGRADE)==1;
        if(p.getMainHandItem().isEmpty()){
            for(int i=0;i<h.getSlots();i++)if(h.getStackInSlot(i).is(item(UPGRADE).getItem())&&h.getStackInSlot(i).getCount()==1){
                ItemStack recovered=h.extractItem(i,1,false);
                need(recovered.is(item(UPGRADE).getItem())&&recovered.getCount()==1,"authored unused upgrade extraction refused");
                p.setItemInHand(InteractionHand.MAIN_HAND,recovered);break;
            }
        }
        need(p.getMainHandItem().is(item(UPGRADE).getItem())&&p.getMainHandItem().getCount()==1
                &&countHandler(h,UPGRADE)==0,"native repeated seal use did not conserve one unused upgrade");
        need(p.getInventory().getItem(s.refundSlot).isEmpty(),"foreign refund slot");
        p.getInventory().setItem(s.refundSlot,p.getMainHandItem());p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        record(s,"seal_payment_noop",paid&&noop,Map.of("airtight",sealed(s),"upgrade_total",countPlayer(p,UPGRADE)));
        need(countHandler(h,GAS)==0,"foreign gas in new basin");
        ItemStack authored=item(GAS); ItemStack simulated=insert(h,authored.copy(),true);
        need(simulated.isEmpty()&&countHandler(h,GAS)==0,"basin simulation mutated/rejected authored gas");
        need(insert(h,authored,false).isEmpty()&&gasCount(s)==1,"native basin gas capability refused fixture");
        }
        if(s.family("kinetics")) {
        place(s,MOTOR,"create:creative_motor");
        var state=s.level.getBlockState(MOTOR);
        need(state.hasProperty(BlockStateProperties.FACING),"native motor facing missing");
        s.shaftDirection=state.getValue(BlockStateProperties.FACING);
        need(s.shaftDirection.getAxis().isHorizontal(),"fixture needs horizontal native motor");
        place(s,MOTOR.relative(s.shaftDirection),"create:shaft");
        place(s,MOTOR.relative(s.shaftDirection,2),"create:shaft");
        if(s.family.equals("all")) {
            s.motorDevice=blockDevice(s,MOTOR,"creative_motor");invoke(s.motorDevice,"setGeneratedSpeed",32);
        } else nativeMotorSpeed(s,32);
        }
        if(s.family("provider-api")) {
            place(s,STICKER,"create:sticker");s.originalSticker=s.level.getBlockEntity(STICKER);
            s.stickerDevice=blockDevice(s,STICKER,"sticker");
        }
        if(s.family("wireless")) {
            place(s,HOST_A,"oc2r:computer");place(s,HOST_B,"oc2r:computer");
            place(s,RELAY,"better_oc2r_wireless_messaging:wireless_relay");
            s.sender=card(s,HOST_A);s.receiver=card(s,HOST_B);
        }
        retreat(s);s.phase(s.family("seals")?"sealed":s.family.equals("kinetics")?"kinetics_ready":"independent_ready");
    }

    private static void nativeMotorSpeed(Session s,int speed) {
        need(s.level.getBlockEntity(MOTOR) instanceof CreativeMotorBlockEntity,"native motor missing");
        var motor=(CreativeMotorBlockEntity)s.level.getBlockEntity(MOTOR);
        need(motor.generatedSpeed!=null,"native motor speed behavior not ready");
        motor.generatedSpeed.setValue(speed); // Public native configuration behavior; no forced network/cache refresh.
    }

    private static void rpc(Session s) {
        int before=s.machineEvents;
        boolean initially=Boolean.TRUE.equals(invoke(s.stickerDevice,"isExtended"));
        need(!initially,"authored sticker already extended");
        int afterRead=s.machineEvents;
        boolean first=Boolean.TRUE.equals(invoke(s.stickerDevice,"extend"));
        int afterFirst=s.machineEvents;
        boolean nativeExtended=s.level.getBlockState(STICKER).getValue(BlockStateProperties.EXTENDED);
        boolean noop=Boolean.TRUE.equals(invoke(s.stickerDevice,"extend"));
        int afterNoop=s.machineEvents;
        boolean retract=Boolean.TRUE.equals(invoke(s.stickerDevice,"retract"));
        record(s,"rpc_committed_noop",afterRead==before&&first&&nativeExtended&&!noop&&retract&&afterFirst==before+1
                        &&afterNoop==afterFirst&&s.machineEvents==before+2
                        &&!s.level.getBlockState(STICKER).getValue(BlockStateProperties.EXTENDED),
                Map.of("events_before",before,"after_read",afterRead,"after_change",afterFirst,
                        "after_noop",afterNoop,"after_retract",s.machineEvents));
    }

    private static void wireless(Session s) {
        var relay=(WirelessRelayBlockEntity)s.level.getBlockEntity(RELAY);
        need(relay!=null,"native relay missing");
        var energy=relay.getCapability(ForgeCapabilities.ENERGY).orElseThrow(()->new IllegalStateException("relay FE absent"));
        String topic="systems-"+s.binding.get("nonce");String payload="α".repeat(17);
        need(((Number)invoke(s.sender,"getTopicDepth",topic)).intValue()==0,"foreign existing topic");
        int original=energy.getEnergyStored();need(original==0,"fresh relay has foreign FE");
        int simulation=energy.receiveEnergy(64,true);
        boolean simulate=simulation==64&&energy.getEnergyStored()==0;
        boolean deniedEmpty=denied(s.sender,"send","insufficient energy",topic,payload);
        boolean noPublish=((Number)invoke(s.sender,"getTopicDepth",topic)).intValue()==0;
        int accepted=energy.receiveEnergy(64,false);int funded=energy.getEnergyStored();
        Object sent=invoke(s.sender,"send",topic,payload);
        int bytes=payload.getBytes(StandardCharsets.UTF_8).length;int cost=4+(bytes+31)/32;
        boolean charged=funded-energy.getEnergyStored()==cost;
        int afterSend=energy.getEnergyStored();
        boolean deniedOversize=denied(s.sender,"send","storage limit rejected publication",topic,"x".repeat(4097));
        boolean deniedEmptyTopic=denied(s.sender,"send","topic cannot be empty","",payload);
        boolean noDeniedDebit=energy.getEnergyStored()==afterSend;
        int receipts=s.wirelessEvents;
        Object self=invoke(s.sender,"pop",topic,1,"self-"+s.binding.get("nonce"));
        boolean noSelfCredit=s.wirelessEvents==receipts;
        Object delivered=invoke(s.receiver,"pop",topic,1,null);
        int afterReceipt=s.wirelessEvents;
        Object twice=invoke(s.receiver,"pop",topic,1,null);
        boolean delivery=List.of(payload).equals(self)&&List.of(payload).equals(delivered)&&List.of().equals(twice)
                &&noSelfCredit&&afterReceipt==receipts+1&&s.wirelessEvents==afterReceipt&&s.receiptFieldsValid;
        near(s.player,RELAY);need(s.player.gameMode.destroyBlock(RELAY),"native relay removal denied");
        boolean staleDenied=denied(s.sender,"send","No wireless relay in range",topic,payload);
        boolean noStalePublish=((Number)invoke(s.sender,"getTopicDepth",topic)).intValue()==1;
        record(s,"wireless_fe_delivery_denials",simulate&&deniedEmpty&&noPublish&&accepted==64&&funded==64
                        &&charged&&List.of(cost,bytes).equals(sent)&&deniedOversize&&deniedEmptyTopic&&noDeniedDebit
                        &&delivery&&staleDenied&&noStalePublish&&!relay.getCapability(ForgeCapabilities.ENERGY).isPresent(),
                Map.of("utf8_bytes",bytes,"expected_cost",cost,"rpc_result",sent,"funded_fe",funded,
                        "after_send_fe",afterSend,"simulation_no_mutation",simulate,"delivery_once",delivery,
                        "no_relay_denied",staleDenied,"removed_capability_invalid",!relay.getCapability(ForgeCapabilities.ENERGY).isPresent(),
                        "receipt_witnesses",List.copyOf(s.receiptWitnesses)));
        retreat(s);
    }

    private static boolean kineticReady(Session s,int rpm,int shafts) {
        BlockEntity tile=s.level.getBlockEntity(MOTOR);
        if(!(tile instanceof CreativeMotorBlockEntity motor)||!motor.hasNetwork())return false;
        Set<KineticBlockEntity> members=new HashSet<>(motor.getOrCreateNetwork().members.keySet());
        members.addAll(motor.getOrCreateNetwork().sources.keySet());
        if(members.size()!=shafts+1)return false;
        for(int i=1;i<=shafts;i++) {
            var be=s.level.getBlockEntity(MOTOR.relative(s.shaftDirection,i));
            if(!(be instanceof KineticBlockEntity k)||!k.hasNetwork()||!Objects.equals(k.network,motor.network)
                    ||Math.abs(Math.abs(k.getTheoreticalSpeed())-rpm)>.01)return false;
        }
        return true;
    }
    private static void kinetic(Session s,String key,int rpm,int shafts) {
        boolean ready=kineticReady(s,rpm,shafts);
        if(!ready){record(s,key,false,Map.of("native_network_ready",false,"expected_rpm",rpm,"expected_shafts",shafts));return;}
        var motor=(CreativeMotorBlockEntity)s.level.getBlockEntity(MOTOR);var n=motor.getOrCreateNetwork();
        var c=TransmissionLossConfig.INSTANCE;
        // Independent oracle from raw declared config and actual shaft speed, NOT NetworkScanner/LossCache.
        double normalized=Math.abs(rpm)/Math.max(.001,c.getBaseRpm().get());
        double mult=switch(c.getSpeedMode().get().name()) {
            case "NONE" -> 1;case "LINEAR" -> 1+c.getK().get()*normalized;
            case "QUADRATIC" -> 1+c.getK().get()*normalized*normalized;
            default -> throw new IllegalStateException("unknown configured loss mode");
        };
        mult=Math.min(mult,c.getMaxMult().get());double expected=shafts*c.getShaft().get()*mult;
        boolean zeroBase=n.members.values().stream().allMatch(f->f==0f);
        float actual=n.calculateStress(); // Actual transformed upstream network, no forced loss refresh/invalidation.
        record(s,key,ready&&zeroBase&&c.getEnabled().get()&&expected>0&&Math.abs(actual-expected)<1e-5,
                Map.of("network_id",String.valueOf(motor.network),"native_members",n.getSize(),"native_rpm",rpm,
                        "expected_shafts",shafts,"zero_native_base_stress",zeroBase,"independent_expected_loss",expected,
                        "native_calculate_stress",actual,"loss_enabled",c.getEnabled().get()));
    }

    @SubscribeEvent public static void chemical(ChemicalOutcomeEvent e) {
        Session s=session;if(s==null||s.finished||e.level()!=s.level||!e.pos().equals(BASIN)
                ||e.kind()!=ChemicalOutcomeEvent.Kind.GAS_RELEASED)return;
        s.gasEvents++;
        String block=blockId(s.level,BASIN.above());
        s.releaseWitness=Map.of("server_tick",s.player.server.getTickCount(),"native_block",block,
                "native_state",s.level.getBlockState(BASIN.above()).toString(),"native_pollutant",block.startsWith("adpother:"),
                "remaining_gas",gasCount(s),"same_native_holder",e.device()==s.level.getBlockEntity(BASIN));
    }
    @SubscribeEvent public static void machine(MachineOperationEvent e) {
        Session s=session;if(s!=null&&!s.finished&&e.getMachine()==s.originalSticker)s.machineEvents++;
    }
    @SubscribeEvent public static void received(WirelessMessageReceivedEvent e) {
        Session s=session;if(s!=null&&!s.finished&&e.server==s.player.server&&e.topic.equals("systems-"+s.binding.get("nonce"))) {
            s.wirelessEvents++;
            boolean valid=s.player.getUUID().equals(e.owner)&&e.bytes==34&&e.energy==6&&e.operation!=null;
            if(e.operation!=null)try{UUID.fromString(e.operation);}catch(IllegalArgumentException bad){valid=false;}
            s.receiptFieldsValid&=valid;
            if(s.receiptWitnesses.size()<4)s.receiptWitnesses.add(Map.of("owner",String.valueOf(e.owner),
                    "operation",String.valueOf(e.operation),"utf8_bytes",e.bytes,"energy",e.energy,"fields_valid",valid));
        }
    }

    private static RPCDevice blockDevice(Session s,BlockPos pos,String name) {
        var p=Providers.blockDeviceProviderRegistry().getValue(id("better_oc2r_create_controls:"+name));
        need(p!=null,"registered Create OC2 provider absent "+name);
        var result=p.getDevice(blockQuery(s.level,pos));need(result.isPresent(),"native provider rejected "+pos);
        Device device=result.get();need(device instanceof RPCDevice,"provider not RPC");return (RPCDevice)device;
    }
    private static BlockDeviceQuery blockQuery(ServerLevel level,BlockPos pos) {
        return new BlockDeviceQuery(){public net.minecraft.world.level.LevelAccessor getLevel(){return level;}
            public BlockPos getQueryPosition(){return pos;}public Direction getQuerySide(){return Direction.UP;}};
    }
    private static RPCDevice card(Session s,BlockPos host) {
        BlockEntity be=s.level.getBlockEntity(host);need(be!=null,"real OC2 host absent");
        ItemStack card=item("better_oc2r_wireless_messaging:wireless_card");
        ItemDeviceQuery q=new ItemDeviceQuery(){public Optional<BlockEntity> getContainerBlockEntity(){return Optional.of(be);}
            public Optional<Entity> getContainerEntity(){return Optional.empty();}public ItemStack getItemStack(){return card;}};
        var provider=Providers.itemDeviceProviderRegistry().getValue(id("better_oc2r_wireless_messaging:wireless_card"));
        need(provider!=null,"registered wireless item provider absent");
        var d=provider.getDevice(q);need(d.isPresent()&&d.get() instanceof RPCDevice,"card provider rejected native query");
        return (RPCDevice)d.get();
    }
    private static Object invoke(RPCDevice d,String name,Object... values) {
        JsonArray args=new JsonArray();for(Object v:values)args.add(GSON.toJsonTree(v));
        RPCInvocation invocation=new RPCInvocation(){public JsonArray getParameters(){return args;}
            public Gson getGson(){return GSON;}
            public Optional<Object[]> tryDeserializeParameters(RPCParameter... signature){
                if(signature.length!=args.size())return Optional.empty();
                Object[] out=new Object[signature.length];
                try{for(int i=0;i<out.length;i++)out[i]=GSON.fromJson(args.get(i),signature[i].getType());}
                catch(JsonParseException|IllegalArgumentException invalid){return Optional.empty();}
                return Optional.of(out);
            }};
        var group=d.getMethodGroups().stream().filter(g->g.getName().equals(name)).findFirst()
                .orElseThrow(()->new IllegalStateException("missing actual OC2 RPC method "+name));
        var method=group.findOverload(invocation).orElseThrow(()->new IllegalStateException("OC2 signature rejected "+name));
        try{return method.invoke(invocation);}catch(Throwable error){
            if(error instanceof Error fatal)throw fatal;
            Throwable cause=error;
            for(int depth=0;depth<8&&cause.getCause()!=null&&cause.getCause()!=cause;depth++)cause=cause.getCause();
            if(cause instanceof Error fatal)throw fatal;
            if(!(cause instanceof IllegalStateException)&&!(cause instanceof IllegalArgumentException))
                throw new IllegalStateException("unexpected native RPC callback failure "+name,error);
            throw new RpcDenied(name+": "+cause.getClass().getName()+": "+String.valueOf(cause.getMessage()));
        }
    }
    private static final class RpcDenied extends RuntimeException {RpcDenied(String message){super(message);}}
    private static boolean denied(RPCDevice d,String method,String expectedReason,Object... args){
        try{invoke(d,method,args);return false;}catch(RpcDenied rejected){
            need(rejected.getMessage().contains(expectedReason),"unexpected RPC failure is not an expected denial: "+rejected.getMessage());return true;
        }
    }
    private static void place(Session s,BlockPos pos,String id) {
        need(s.level.hasChunkAt(pos)&&s.level.getBlockState(pos).isAir(),"foreign/unloaded placement "+pos);
        BlockPos support=pos.below();
        if(s.level.getBlockState(support).isAir())need(s.level.setBlock(support,Blocks.STONE.defaultBlockState(),3),"fixture support failed");
        need(s.level.getBlockState(support).is(Blocks.STONE),"foreign placement support "+support);
        s.player.getInventory().selected=s.workSlot;giveHand(s,id,1);near(s.player,pos);
        var result=s.player.gameMode.useItemOn(s.player,s.level,s.player.getMainHandItem(),InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(support).add(0,.5,0),Direction.UP,support,false));
        need(result.consumesAction()&&blockId(s.level,pos).equals(id)&&s.player.getMainHandItem().isEmpty(),"native Survival placement failed "+id);
    }
    private static void giveHand(Session s,String name,int n){need(s.player.getMainHandItem().isEmpty(),"foreign hand refused");ItemStack v=item(name);v.setCount(n);s.player.setItemInHand(InteractionHand.MAIN_HAND,v);}
    private static void use(ServerPlayer p,BlockPos pos,InteractionHand hand){near(p,pos);p.gameMode.useItemOn(p,p.serverLevel(),p.getItemInHand(hand),hand,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));}
    private static void near(ServerPlayer p,BlockPos pos){p.teleportTo(p.serverLevel(),pos.getX()+.5,pos.getY(),pos.getZ()+4.5,-90,0);}
    private static void pedestal(Session s,BlockPos feet){
        for(BlockPos q:BlockPos.betweenClosed(feet.offset(-1,-1,-1),feet.offset(1,-1,1))) {
            need(s.level.hasChunkAt(q)&&s.level.getBlockState(q.above()).isAir()
                    &&s.level.getBlockState(q.above(2)).isAir(),"foreign/unloaded camera pedestal headroom");
            need(s.level.getBlockState(q).isAir()||s.level.getBlockState(q).is(Blocks.STONE),"foreign pedestal support");
        }
        for(BlockPos q:BlockPos.betweenClosed(feet.offset(-1,-1,-1),feet.offset(1,-1,1)))
            if(s.level.getBlockState(q).isAir())need(s.level.setBlock(q,Blocks.STONE.defaultBlockState(),3),"pedestal placement failed");
    }
    private static void retreat(Session s){s.player.teleportTo(s.level,999_944.5,316,999_940.5,-90,0);}
    private static boolean sealed(Session s){return s.level.getBlockEntity(BASIN) instanceof AirtightUpgradeHolder a&&a.isAirtight();}
    private static IItemHandler items(BlockEntity be){need(be!=null,"tile missing");return be.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(()->new IllegalStateException("native item capability missing"));}
    private static int gasCount(Session s){return countHandler(items(s.level.getBlockEntity(BASIN)),GAS);}
    private static int countHandler(IItemHandler h,String id){int n=0;for(int i=0;i<h.getSlots();i++)if(h.getStackInSlot(i).is(item(id).getItem()))n+=h.getStackInSlot(i).getCount();return n;}
    private static ItemStack insert(IItemHandler h,ItemStack stack,boolean simulate){for(int i=0;i<h.getSlots()&&!stack.isEmpty();i++)stack=h.insertItem(i,stack,simulate);return stack;}
    private static int countPlayer(ServerPlayer p,String id){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item(id).getItem()))n+=p.getInventory().getItem(i).getCount();return n;}
    private static ResourceLocation id(String s){return new ResourceLocation(s);}
    private static ItemStack item(String name){var i=ForgeRegistries.ITEMS.getValue(id(name));need(i!=null&&i!=Items.AIR,"missing native item "+name);return new ItemStack(i);}
    private static String blockId(ServerLevel level,BlockPos pos){return String.valueOf(ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock()));}
    private static void phase(Session s,String expected){need(s.phase.equals(expected),"expected phase "+expected+", actual "+s.phase);}
    private static void record(Session s,String key,boolean passed,Map<String,Object> detail){
        Boolean previous=s.cases.get(key);s.cases.put(key,previous==null?passed:previous&&passed);
        Map<String,Object> row=new LinkedHashMap<>(detail);row.put("case",key);row.put("passed",passed);
        row.put("server_tick",s.player.server.getTickCount());s.observations.add(row);
    }
    private static Map<String,Object> report(Session s,boolean ready){
        Map<String,Object> out=new LinkedHashMap<>();out.put("phase",s.phase);out.put("ready",ready);
        boolean all=s.required.stream().allMatch(key->Boolean.TRUE.equals(s.cases.get(key)));
        out.put("selected_family",s.family);out.put("required_checks",s.required.stream().sorted().toList());
        out.put("acceptance_scope","selected family only; unselected null checks are NOT_PERFORMED, not passes; provider API is not native bus/UART/guest CPU; 32/64 loss is not overload");
        out.put("cached_adapter_scope","out-of-contract lifetime diagnostic without proven registered bus reachability; never product-bug or bus-invalidation proof");
        out.put("passed",ready&&all);out.put("complete",s.finished&&all);out.put("checks",new LinkedHashMap<>(s.cases));
        out.put("observations",List.copyOf(s.observations));out.put("candidate_binding",s.binding);
        out.put("server_tick",s.player.server.getTickCount());out.put("phase_elapsed_ticks",s.elapsed());
        out.put("positions",Map.of("basin",List.of(BASIN.getX(),BASIN.getY(),BASIN.getZ()),
                "motor",List.of(MOTOR.getX(),MOTOR.getY(),MOTOR.getZ()),"sticker",List.of(STICKER.getX(),STICKER.getY(),STICKER.getZ()),
                "host_a",List.of(HOST_A.getX(),HOST_A.getY(),HOST_A.getZ()),"host_b",List.of(HOST_B.getX(),HOST_B.getY(),HOST_B.getZ()),
                "relay",List.of(RELAY.getX(),RELAY.getY(),RELAY.getZ())));
        out.put("scope","Authored native Survival placement/use and native loaded tick/capability composition. OC2 registry+ObjectDevice RPC dispatch using explicit fixture queries; NOT guest CPU, UART, installed-card bus scan, earned resources or gameplay reachability. Native game-mode API bypasses packet aim/reach validation and elapsed mining duration. No restart/worldtime mutation.");
        out.put("uncovered",List.of("Create overload transition/cogs/belts/config-reload", "OC2 guest/serial bus/event subscription transport", "wireless live cross-dimension/restart/range boundary", "PNC chemistry recipe caches", "train cargo/fuel", "magic anchor tickets", "offline weather unload/restart", "client framebuffer semantic review"));
        return out;
    }
    private static Map<String,String> binding(JsonObject in){
        Map<String,String> b=new LinkedHashMap<>();
        for(String k:List.of("run","nonce","player")){need(in.has(k)&&in.get(k).isJsonPrimitive(),"missing systems binding "+k);b.put(k,in.get(k).getAsString());}
        need(b.get("run").equals(System.getProperty("bc.pack_test.diagnostics.run_id"))
                &&b.get("nonce").equals(System.getProperty("bc.pack_test.gameplay_world.nonce")),"systems run/nonce mismatch");
        for(String kind:List.of("client","server","runtime","support")){
            String key=kind+"_sha256",hash=System.getProperty("bc.pack_test.diagnostics."+key,"");
            need(hash.matches("[a-f0-9]{64}")&&(!in.has(key)||hash.equals(in.get(key).getAsString())),"systems candidate mismatch "+key);b.put(key,hash);
        }
        return Map.copyOf(b);
    }
    private static void guard(ServerPlayer p,JsonObject in){
        need(p.server.isSameThread()&&p.connection!=null&&!(p instanceof net.minecraftforge.common.util.FakePlayer),"real server-thread network player required");
        need(Boolean.getBoolean("bc.pack_test.gameplay_world")&&"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode")),"systems fixture disabled");
        need(p.isAlive()&&p.getGameProfile().getName().matches("SmokeClient[12]")&&p.gameMode.getGameModeForPlayer()==GameType.SURVIVAL
                &&p.serverLevel().dimension().equals(Level.OVERWORLD)&&Math.abs(p.getX()-1_000_000)<=128
                &&Math.abs(p.getZ()-1_000_000)<=128&&Math.abs(p.getY()-316)<=32,"systems outside bound Survival core");
        need(in.has("player")&&p.getUUID().toString().equals(in.get("player").getAsString()),"systems player mismatch");
    }
    private static void need(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
}
