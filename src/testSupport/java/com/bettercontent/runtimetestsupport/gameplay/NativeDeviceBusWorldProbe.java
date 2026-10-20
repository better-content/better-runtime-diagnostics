package com.bettercontent.runtimetestsupport.gameplay;

import com.bettercontent.betteroc2rcreatecontrols.api.events.MachineOperationEvent;
import com.bettercontent.betteroc2rwirelessmessaging.api.WirelessMessageReceivedEvent;
import com.bettercontent.betteroc2rwirelessmessaging.api.WirelessOperators;
import com.bettercontent.betteroc2rwirelessmessaging.blockentity.WirelessRelayBlockEntity;
import com.google.gson.*;
import li.cil.oc2.api.bus.device.Device;
import li.cil.oc2.api.bus.device.DeviceTypes;
import li.cil.oc2.api.bus.device.rpc.*;
import li.cil.oc2.common.block.BusCableBlock;
import li.cil.oc2.common.blockentity.ComputerBlockEntity;
import li.cil.oc2.common.bus.CommonDeviceBusController;
import li.cil.oc2.common.vm.AbstractVirtualMachine;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import java.util.*;
import java.util.function.Consumer;

/** Nonshipping real installed-card/controller fixture. No manual provider query, forced scan,
 * VM start, guest CPU, UART, adapter invocation, fabricated ID or private access. Current-bound
 * RPCMethod invocation below is explicitly a CALLBACK fixture, NOT a wire/program proof.
 * Start/place-a use SmokeClient1, place-b/insert-b use actual SmokeClient2. All other actions A.
 * Global monotonic sequence across actors. Poll nativebus-observe no faster than once/second.
 * Each wait <=420 actual ticks; episode <=3000 ticks/300 wall seconds/512 requests.
 */
@Mod.EventBusSubscriber(modid="better_runtime_test_support")
public final class NativeDeviceBusWorldProbe {
    public static final Set<String> ACTIONS=Set.of("nativebus-start","nativebus-place-a","nativebus-place-b",
            "nativebus-observe","nativebus-insert-a","nativebus-insert-b","nativebus-add-interface",
            "nativebus-callbacks","nativebus-remove-sticker","nativebus-remove-card-a","nativebus-finish","nativebus-abort");
    public static final BlockPos HOST_A=new BlockPos(999904,316,999888), HOST_B=HOST_A.offset(0,0,12), RELAY=HOST_A.offset(0,0,6);
    private static final String CARD="better_oc2r_wireless_messaging:wireless_card";
    private static Session session;
    private NativeDeviceBusWorldProbe() {}
    private static final class Host {
        final NativeSystemsFixture.Pose pose; final BlockPos pos;
        ComputerBlockEntity computer; AbstractVirtualMachine vm; IItemHandler cards;
        int slot, scans, removals; Direction out; BlockPos endpoint,sticker,motor;
        RPCDevice card, stickerDevice, motorDevice; Set<UUID> cardIds=Set.of(), stickerIds=Set.of(), motorIds=Set.of();
        List<String> installedSlots=List.of();
        final Consumer<CommonDeviceBusController.AfterDeviceScanEvent> after=e->scans++;
        final Consumer<CommonDeviceBusController.DevicesChangedEvent> removed=e->removals+=e.devices().size();
        Host(ServerPlayer p,BlockPos q){pose=new NativeSystemsFixture.Pose(p);pose.park=q.offset(-5,0,-5);pos=q;}
        CommonDeviceBusController bus(){return vm.busController;}
        void close(){if(vm!=null){bus().onAfterDeviceScan.remove(after);bus().onDevicesRemoved.remove(removed);}}
    }
    private static final class Session {
        final Host a,b; final Map<String,String> binding; final long wall=System.nanoTime(); final int tick;
        final Map<String,Boolean> checks=new LinkedHashMap<>(); final List<Map<String,Object>> evidence=new ArrayList<>();
        String phase="place_a"; int phaseTick,sequence,requests=1,matchTick=-1,scanBaseline,removedBaseline;
        long lastPoll; boolean closed,aborted,receiptValid=true; int machineEvents,receipts;
        BlockEntity stickerTile; Device oldSticker; Set<UUID> oldStickerIds=Set.of();
        Session(ServerPlayer p,ServerPlayer second,JsonObject in){a=new Host(p,HOST_A);b=new Host(second,HOST_B);binding=NativeSystemsFixture.binding(in,false);
            tick=p.server.getTickCount();phaseTick=tick;sequence=in.get("sequence").getAsInt();
            for(String k:List.of("native_distinct_owners","empty_card_and_cable_denial","native_card_transfer","installed_card_discovery",
                    "native_interface_discovery","current_bound_callbacks","native_host_wireless_conservation","external_automatic_invalidation","card_automatic_invalidation_b_unchanged"))checks.put(k,null);}
        void phase(String next){phase=next;phaseTick=a.pose.p.server.getTickCount();matchTick=-1;lastPoll=0;}
    }
    public static Map<String,Object> run(ServerPlayer p,JsonObject in){
        NativeSystemsFixture.guard(p,in);String action=in.get("action").getAsString();NativeSystemsFixture.need(ACTIONS.contains(action),"unknown nativebus action");
        if(action.equals("nativebus-start")){
            NativeSystemsFixture.need(session==null&&p.getGameProfile().getName().equals("SmokeClient1"),"one nativebus episode, started by actual A only");
            ServerPlayer b=p.server.getPlayerList().getPlayerByName("SmokeClient2");NativeSystemsFixture.need(b!=null&&b!=p&&!b.getUUID().equals(p.getUUID()),"actual distinct connected SmokeClient2 required");
            NativeSystemsFixture.live(b);NativeSystemsFixture.preflight(p.serverLevel(),HOST_A.offset(-6,0,-6),HOST_B.offset(6,4,6));
            session=new Session(p,b,in);return report(session,true);
        }
        Session s=session;NativeSystemsFixture.need(s!=null&&!s.closed,"no active nativebus session");
        NativeSystemsFixture.need(s.binding.equals(NativeSystemsFixture.binding(in,false)),"nativebus candidate/run/nonce drift");
        NativeSystemsFixture.live(s.a.pose.p);NativeSystemsFixture.live(s.b.pose.p);
        NativeSystemsFixture.need(p==s.a.pose.p||p==s.b.pose.p,"nativebus wrong native actor");
        int sequence=in.get("sequence").getAsInt();NativeSystemsFixture.need(sequence>s.sequence,"nativebus replay");s.sequence=sequence;
        boolean abort=action.equals("nativebus-abort");
        if(!abort)NativeSystemsFixture.need(++s.requests<=512&&p.server.getTickCount()-s.tick<=3000&&System.nanoTime()-s.wall<=300_000_000_000L,"nativebus finite workload budget exceeded");
        if(action.equals("nativebus-observe")){
            actor(p,s.a);NativeSystemsFixture.need(s.phase.startsWith("wait_"),"not in nativebus observation phase");
            long now=System.nanoTime();NativeSystemsFixture.need(s.lastPoll==0||now-s.lastPoll>=1_000_000_000L,"nativebus observe profile requires <=1Hz parent polling");s.lastPoll=now;
            boolean matched=observe(s);int tick=p.server.getTickCount();
            if(!matched)s.matchTick=-1;else if(s.matchTick<0)s.matchTick=tick;
            if(matched&&tick>s.matchTick){qualify(s);return report(s,true);}
            if(tick-s.phaseTick>=420){record(s,key(s.phase),false,Map.of("timeout_actual_ticks",tick-s.phaseTick,"a",snapshot(s.a),"b",snapshot(s.b)));s.phase("failed");return report(s,true);}
            return report(s,false);
        }
        switch(action){
            case "nativebus-place-a"->{actor(p,s.a);phase(s,"place_a");placeHost(s.a,true);s.stickerTile=p.serverLevel().getBlockEntity(s.a.sticker);s.phase("place_b");}
            case "nativebus-place-b"->{actor(p,s.b);phase(s,"place_b");placeHost(s.b,false);NativeSystemsFixture.place(s.b.pose,RELAY,"better_oc2r_wireless_messaging:wireless_relay",false);
                record(s,"native_distinct_owners",owners(s),Map.of("a",owner(s.a),"b",owner(s.b)));s.phase("wait_empty");}
            case "nativebus-insert-a"->{actor(p,s.a);phase(s,"empty");insert(s.a);s.phase("insert_b");}
            case "nativebus-insert-b"->{actor(p,s.b);phase(s,"insert_b");insert(s.b);record(s,"native_card_transfer",true,Map.of("a_slots",slots(s.a.cards),"b_slots",slots(s.b.cards)));s.phase("wait_cards");}
            case "nativebus-add-interface"->{actor(p,s.a);phase(s,"cards");s.scanBaseline=s.a.scans;useItem(s.a,s.a.endpoint,s.a.out,"oc2r:bus_interface");
                NativeSystemsFixture.need(BusCableBlock.getConnectionType(p.serverLevel().getBlockState(s.a.endpoint),s.a.out)==BusCableBlock.ConnectionType.INTERFACE,"native interface not admitted");s.phase("wait_bound");}
            case "nativebus-callbacks"->{actor(p,s.a);phase(s,"bound");callbacks(s);s.phase("callbacks");}
            case "nativebus-remove-sticker"->{actor(p,s.a);phase(s,"callbacks");s.oldSticker=current(s.a,"create_sticker");s.oldStickerIds=ids(s.a,s.oldSticker);s.scanBaseline=s.a.scans;s.removedBaseline=s.a.removals;
                NativeSystemsFixture.breakOwned(s.a.pose,s.a.sticker,"create:sticker");s.phase("wait_removed");}
            case "nativebus-remove-card-a"->{actor(p,s.a);phase(s,"removed");s.scanBaseline=s.a.scans;s.removedBaseline=s.a.removals;
                List<String> before=slots(s.a.cards);ItemStack old=s.a.cards.getStackInSlot(s.a.slot).copy();ItemStack sim=s.a.cards.extractItem(s.a.slot,1,true);
                NativeSystemsFixture.need(ItemStack.matches(old,sim)&&before.equals(slots(s.a.cards)),"native card extraction simulation mutated/rejected");
                ItemStack extracted=s.a.cards.extractItem(s.a.slot,1,false);NativeSystemsFixture.need(ItemStack.matches(old,extracted)&&s.a.cards.getStackInSlot(s.a.slot).isEmpty(),"native card extraction conservation failed");
                NativeSystemsFixture.need(s.a.pose.p.getInventory().getItem(s.a.pose.work).isEmpty(),"foreign extraction destination");s.a.pose.p.getInventory().setItem(s.a.pose.work,extracted);s.phase("wait_card_removed");}
            case "nativebus-finish"->{actor(p,s.a);phase(s,"done");NativeSystemsFixture.need(in.has("capture_complete")&&in.get("capture_complete").getAsBoolean(),"parent capture acknowledgement required before restoring poses");
                NativeSystemsFixture.need(ready(s.a)&&ready(s.b)&&owners(s)&&bStable(s)&&motorStable(s.a)&&count(s.a,"wireless_card")==0&&count(s.a,"create_sticker")==0&&Collections.disjoint(allIds(s.a),s.a.cardIds)&&Collections.disjoint(allIds(s.a),s.oldStickerIds),"nativebus final composition drift; no pass or repair");close(s,false);}
            case "nativebus-abort"->close(s,true);
            default->throw new IllegalStateException("unexpected nativebus action");
        }
        return report(s,true);
    }
    private static void placeHost(Host h,boolean external){
        NativeSystemsFixture.place(h.pose,h.pos,"oc2r:computer",false);BlockEntity be=h.pose.p.serverLevel().getBlockEntity(h.pos);
        NativeSystemsFixture.need(be instanceof ComputerBlockEntity,"native computer absent");h.computer=(ComputerBlockEntity)be;
        NativeSystemsFixture.need(h.computer.getVirtualMachine() instanceof AbstractVirtualMachine,"typed native VM unavailable");h.vm=(AbstractVirtualMachine)h.computer.getVirtualMachine();
        h.cards=h.computer.getItemStackHandlers().getItemHandler(DeviceTypes.CARD).orElseThrow(()->new IllegalStateException("native CARD handler absent"));
        NativeSystemsFixture.need(h.cards.getSlots()>0&&h.cards.getSlots()<=16&&slotsEmpty(h.cards),"new native card slots foreign/unbounded");h.slot=0;
        h.bus().onAfterDeviceScan.add(h.after);h.bus().onDevicesRemoved.add(h.removed);
        h.out=h.pose.p.serverLevel().getBlockState(h.pos).getValue(BlockStateProperties.FACING).getOpposite();NativeSystemsFixture.need(h.out.getAxis().isHorizontal(),"native host front not horizontal");
        // Actual bus-interface placement on computer face, then add actual cable in that same cell.
        useItem(h,h.pos,h.out,"oc2r:bus_interface");BlockPos port=h.pos.relative(h.out);h.pose.owned.add(port);useItem(h,port,Direction.UP,"oc2r:bus_cable");
        if(!external)return;
        BlockPos middle=h.pos.relative(h.out,2);h.endpoint=h.pos.relative(h.out,3);h.sticker=h.pos.relative(h.out,4);
        NativeSystemsFixture.place(h.pose,middle,"oc2r:bus_cable",false);NativeSystemsFixture.place(h.pose,h.endpoint,"oc2r:bus_cable",false);
        NativeSystemsFixture.place(h.pose,h.sticker,"create:sticker",false);
        Direction branch=h.out.getClockWise();BlockPos c1=middle.relative(branch),c2=middle.relative(branch,2);h.motor=middle.relative(branch,3);
        NativeSystemsFixture.place(h.pose,h.motor,"create:creative_motor",false);useItem(h,h.motor,branch.getOpposite(),"oc2r:bus_interface");h.pose.owned.add(c2);
        useItem(h,c2,Direction.UP,"oc2r:bus_cable");NativeSystemsFixture.place(h.pose,c1,"oc2r:bus_cable",false);
        NativeSystemsFixture.need(BusCableBlock.getConnectionType(h.pose.p.serverLevel().getBlockState(port),h.out.getOpposite())==BusCableBlock.ConnectionType.INTERFACE
                &&BusCableBlock.getConnectionType(h.pose.p.serverLevel().getBlockState(c2),branch)==BusCableBlock.ConnectionType.INTERFACE,"native endpoint interface topology wrong");
    }
    private static void useItem(Host h,BlockPos clicked,Direction face,String item){NativeSystemsFixture.useAuthored(h.pose,clicked,face,item,false);}
    private static void insert(Host h){
        NativeSystemsFixture.need(slotsEmpty(h.cards),"foreign native slots before transfer");List<String> before=slots(h.cards);ItemStack bad=new ItemStack(Items.STONE);
        ItemStack denied=h.cards.insertItem(h.slot,bad.copy(),true);NativeSystemsFixture.need(ItemStack.matches(denied,bad)&&before.equals(slots(h.cards)),"native CARD stone denial/simulation failed");
        ItemStack source=NativeSystemsFixture.item(CARD);NativeSystemsFixture.give(h.pose,source);ItemStack actual=h.pose.p.getMainHandItem();
        NativeSystemsFixture.need(h.cards.insertItem(h.slot,actual.copy(),true).isEmpty()&&before.equals(slots(h.cards))&&ItemStack.matches(actual,source),"native card insertion simulation rejected/mutated");
        ItemStack remainder=h.cards.insertItem(h.slot,actual.copy(),false);NativeSystemsFixture.need(remainder.isEmpty()&&h.cards.getStackInSlot(h.slot).is(source.getItem())&&h.cards.getStackInSlot(h.slot).getCount()==1,"native card insertion failed");
        h.pose.p.setItemInHand(InteractionHand.MAIN_HAND,remainder);
    }
    private static boolean observe(Session s){
        if(!ready(s.a)||!ready(s.b)||!owners(s))return false;
        if(s.a.bus().getDevices().size()>64||s.b.bus().getDevices().size()>64)throw new IllegalStateException("native device bound exceeded");
        return switch(s.phase){
            case "wait_empty"->slotsEmpty(s.a.cards)&&slotsEmpty(s.b.cards)&&count(s.a,"wireless_card")==0&&count(s.b,"wireless_card")==0
                    &&count(s.a,"create_sticker")==0&&count(s.a,"create_creative_motor")==1&&count(s.b,"create_creative_motor")==0;
            case "wait_cards"->count(s.a,"wireless_card")==1&&count(s.b,"wireless_card")==1&&count(s.a,"create_sticker")==0&&count(s.a,"create_creative_motor")==1;
            case "wait_bound"->s.a.scans>s.scanBaseline&&count(s.a,"create_sticker")==1&&count(s.a,"create_creative_motor")==1&&count(s.a,"wireless_card")==1&&count(s.b,"wireless_card")==1;
            case "wait_removed"->s.a.scans>s.scanBaseline&&s.a.removals>s.removedBaseline&&s.a.pose.p.serverLevel().getBlockState(s.a.sticker).isAir()
                    &&s.a.bus().getDevices().stream().noneMatch(d->d==s.oldSticker)&&count(s.a,"create_sticker")==0&&Collections.disjoint(allIds(s.a),s.oldStickerIds)
                    &&motorStable(s.a)&&current(s.a,"wireless_card")==s.a.card&&ids(s.a,s.a.card).equals(s.a.cardIds)&&bStable(s);
            case "wait_card_removed"->s.a.scans>s.scanBaseline&&s.a.removals>s.removedBaseline&&s.a.cards.getStackInSlot(s.a.slot).isEmpty()&&count(s.a,"wireless_card")==0
                    &&Collections.disjoint(allIds(s.a),s.a.cardIds)&&motorStable(s.a)&&bStable(s);
            default->false;
        };
    }
    private static void qualify(Session s){String phase=s.phase;record(s,key(phase),true,Map.of("a",snapshot(s.a),"b",snapshot(s.b),"actual_owner_composition",owners(s)));
        if(phase.equals("wait_cards")){for(Host h:List.of(s.a,s.b)){h.card=current(h,"wireless_card");h.cardIds=ids(h,h.card);h.installedSlots=slots(h.cards);}}
        if(phase.equals("wait_bound")){s.a.stickerDevice=current(s.a,"create_sticker");s.a.stickerIds=ids(s.a,s.a.stickerDevice);s.a.motorDevice=current(s.a,"create_creative_motor");s.a.motorIds=ids(s.a,s.a.motorDevice);}
        s.phase(switch(phase){case "wait_empty"->"empty";case "wait_cards"->"cards";case "wait_bound"->"bound";case "wait_removed"->"removed";case "wait_card_removed"->"done";default->"failed";});
    }
    private static String key(String phase){return switch(phase){case "wait_empty"->"empty_card_and_cable_denial";case "wait_cards"->"installed_card_discovery";case "wait_bound"->"native_interface_discovery";case "wait_removed"->"external_automatic_invalidation";case "wait_card_removed"->"card_automatic_invalidation_b_unchanged";default->throw new IllegalStateException("no observation case");};}
    private static void callbacks(Session s){
        RPCDevice sticker=current(s.a,"create_sticker"),sender=current(s.a,"wireless_card"),receiver=current(s.b,"wireless_card");
        int before=s.machineEvents;boolean read=Boolean.TRUE.equals(invoke(sticker,"isExtended"));int afterRead=s.machineEvents;
        boolean extended=Boolean.TRUE.equals(invoke(sticker,"extend"));boolean nativeExtended=s.a.pose.p.serverLevel().getBlockState(s.a.sticker).getValue(BlockStateProperties.EXTENDED);
        int changed=s.machineEvents;boolean noop=Boolean.TRUE.equals(invoke(sticker,"extend"));int afterNoop=s.machineEvents;boolean retracted=Boolean.TRUE.equals(invoke(sticker,"retract"));
        record(s,"current_bound_callbacks",!read&&afterRead==before&&extended&&nativeExtended&&!noop&&changed==before+1&&afterNoop==changed&&retracted&&s.machineEvents==before+2,
                Map.of("events_before",before,"after_read",afterRead,"after_change",changed,"after_noop",afterNoop,"after_retract",s.machineEvents,"scope","current native discovered RPCMethod fixture; NOT wire"));
        BlockEntity be=s.a.pose.p.serverLevel().getBlockEntity(RELAY);NativeSystemsFixture.need(be instanceof WirelessRelayBlockEntity,"native relay absent");
        var energy=be.getCapability(ForgeCapabilities.ENERGY).orElseThrow(()->new IllegalStateException("native relay FE absent"));
        NativeSystemsFixture.need(energy.getEnergyStored()==0,"foreign relay FE");String topic="nativebus-"+s.binding.get("nonce"),payload="α".repeat(17);
        NativeSystemsFixture.need(((Number)invoke(sender,"getTopicDepth",topic)).intValue()==0,"foreign native topic");
        boolean simulation=energy.receiveEnergy(64,true)==64&&energy.getEnergyStored()==0;
        boolean denied=denied(sender,"send","insufficient energy",topic,payload);boolean deniedPreserves=((Number)invoke(sender,"getTopicDepth",topic)).intValue()==0&&energy.getEnergyStored()==0;
        int accepted=energy.receiveEnergy(64,false);Object sent=invoke(sender,"send",topic,payload);int stored=energy.getEnergyStored();
        boolean oversize=denied(sender,"send","storage limit rejected publication",topic,"x".repeat(4097));boolean empty=denied(sender,"send","topic cannot be empty","",payload);
        int receipts=s.receipts;Object own=invoke(sender,"pop",topic,1,"own-"+s.binding.get("nonce"));boolean noSelf=s.receipts==receipts;
        Object delivery=invoke(receiver,"pop",topic,1,null);int after=s.receipts;Object twice=invoke(receiver,"pop",topic,1,null);
        record(s,"native_host_wireless_conservation",simulation&&denied&&deniedPreserves&&accepted==64&&stored==58&&List.of(6,34).equals(sent)&&oversize&&empty&&energy.getEnergyStored()==stored
                        &&List.of(payload).equals(own)&&noSelf&&List.of(payload).equals(delivery)&&List.of().equals(twice)&&after==receipts+1&&s.receipts==after&&s.receiptValid&&owners(s),
                Map.of("simulated_no_mutation",simulation,"denial_no_mutation",deniedPreserves,"funded_fe",accepted,"after_send_fe",stored,"receipts",s.receipts,"actual_source_owner",s.a.pose.p.getUUID().toString(),"actual_receiver_owner",s.b.pose.p.getUUID().toString(),"scope","actual native host/card binding callback+product receipt; NOT RPC transport"));
    }
    @SubscribeEvent public static void machine(MachineOperationEvent e){Session s=session;if(s!=null&&!s.closed&&e.getMachine()==s.stickerTile)s.machineEvents++;}
    @SubscribeEvent public static void receipt(WirelessMessageReceivedEvent e){Session s=session;if(s==null||s.closed||e.server!=s.a.pose.p.server||!e.topic.equals("nativebus-"+s.binding.get("nonce")))return;
        s.receipts++;boolean valid=s.a.pose.p.getUUID().equals(e.owner)&&e.bytes==34&&e.energy==6&&e.operation!=null;
        if(e.operation!=null)try{UUID.fromString(e.operation);}catch(IllegalArgumentException bad){valid=false;}s.receiptValid&=valid;
        if(s.receipts<=4)s.evidence.add(Map.of("case","actual_receipt_witness","source_owner",String.valueOf(e.owner),"operation",String.valueOf(e.operation),"valid",valid,"tick",s.a.pose.p.server.getTickCount()));}
    private static boolean ready(Host h){return h.vm!=null&&h.vm.getRunState().name().equals("STOPPED")&&h.bus().getState().name().equals("READY")&&h.scans>0&&h.pose.p.serverLevel().getBlockEntity(h.pos)==h.computer;}
    private static boolean owners(Session s){return ownerMatches(s.a)&&ownerMatches(s.b)&&s.a.computer!=s.b.computer&&!s.a.pose.p.getUUID().equals(s.b.pose.p.getUUID());}
    private static boolean ownerMatches(Host h){return h.computer!=null&&h.computer.getPersistentData().hasUUID(WirelessOperators.KEY)&&h.computer.getPersistentData().getUUID(WirelessOperators.KEY).equals(h.pose.p.getUUID());}
    private static String owner(Host h){return h.computer.getPersistentData().hasUUID(WirelessOperators.KEY)?h.computer.getPersistentData().getUUID(WirelessOperators.KEY).toString():"MISSING";}
    private static boolean motorStable(Host h){return count(h,"create_creative_motor")==1&&current(h,"create_creative_motor")==h.motorDevice&&ids(h,h.motorDevice).equals(h.motorIds);}
    private static boolean bStable(Session s){return current(s.b,"wireless_card")==s.b.card&&ids(s.b,s.b.card).equals(s.b.cardIds)
            &&s.b.cards.getStackInSlot(s.b.slot).is(NativeSystemsFixture.item(CARD).getItem())&&s.b.cards.getStackInSlot(s.b.slot).getCount()==1
            &&ownerMatches(s.b)&&count(s.b,"create_sticker")==0;}
    private static int count(Host h,String type){int n=0;for(Device d:h.bus().getDevices())if(d instanceof RPCDevice rpc&&rpc.getTypeNames().contains(type))n++;return n;}
    private static RPCDevice current(Host h,String type){List<RPCDevice> found=new ArrayList<>();for(Device d:h.bus().getDevices())if(d instanceof RPCDevice rpc&&rpc.getTypeNames().contains(type))found.add(rpc);
        NativeSystemsFixture.need(found.size()==1,"native binding missing/ambiguous "+type);RPCDevice d=found.get(0);NativeSystemsFixture.need(!ids(h,d).isEmpty(),"native bound device has no actual identifiers");return d;}
    private static Set<UUID> ids(Host h,Device d){return Set.copyOf(h.bus().getDeviceIdentifiers(d));}
    private static Set<UUID> allIds(Host h){Set<UUID> out=new HashSet<>();for(Device d:h.bus().getDevices())out.addAll(ids(h,d));return out;}
    private static List<String> slots(IItemHandler h){List<String> out=new ArrayList<>();for(int i=0;i<h.getSlots();i++)out.add(h.getStackInSlot(i).save(new net.minecraft.nbt.CompoundTag()).toString());return List.copyOf(out);}
    private static boolean slotsEmpty(IItemHandler h){for(int i=0;i<h.getSlots();i++)if(!h.getStackInSlot(i).isEmpty())return false;return true;}
    private static Map<String,Object> snapshot(Host h){List<Map<String,Object>> rows=new ArrayList<>();if(h.vm!=null)for(Device d:h.bus().getDevices()){
        NativeSystemsFixture.need(rows.size()<64,"native membership bound exceeded");Map<String,Object> row=new LinkedHashMap<>();row.put("class",d.getClass().getName());row.put("identity_hash_diagnostic_only",System.identityHashCode(d));row.put("native_ids",ids(h,d).stream().map(UUID::toString).sorted().toList());
        if(d instanceof RPCDevice rpc){row.put("types",List.copyOf(rpc.getTypeNames()));row.put("methods",rpc.getMethodGroups().stream().map(RPCMethodGroup::getName).sorted().toList());}rows.add(row);}
        return Map.of("position",NativeSystemsFixture.xyz(h.pos),"native_owner",h.computer==null?"MISSING":owner(h),"native_run_state",h.vm==null?"MISSING":h.vm.getRunState().name(),"native_bus_state",h.vm==null?"MISSING":h.bus().getState().name(),"after_scan_events",h.scans,"removed_devices_events",h.removals,"devices",rows);}
    private static void actor(ServerPlayer p,Host h){NativeSystemsFixture.need(p==h.pose.p,"wrong native actor for nativebus phase");}
    private static void phase(Session s,String phase){NativeSystemsFixture.need(s.phase.equals(phase),"expected nativebus "+phase+", actual "+s.phase);}
    private static void record(Session s,String key,boolean passed,Map<String,Object> detail){Boolean previous=s.checks.get(key);s.checks.put(key,previous==null?passed:previous&&passed);Map<String,Object> row=new LinkedHashMap<>(detail);row.put("case",key);row.put("passed",passed);row.put("tick",s.a.pose.p.server.getTickCount());s.evidence.add(row);}
    private static void close(Session s,boolean aborted){s.aborted=aborted;s.a.close();s.b.close();s.a.pose.restore();s.b.pose.restore();s.closed=true;}
    private static Map<String,Object> report(Session s,boolean ready){Map<String,Object> out=new LinkedHashMap<>();boolean all=s.checks.values().stream().allMatch(Boolean.TRUE::equals);
        out.put("ready",ready);out.put("passed",ready&&all&&!s.aborted);out.put("complete",s.closed&&!s.aborted&&all);out.put("closed",s.closed);out.put("phase",s.phase);out.put("checks",new LinkedHashMap<>(s.checks));out.put("observations",List.copyOf(s.evidence));out.put("candidate_binding",s.binding);
        out.put("actors",Map.of("a",s.a.pose.p.getUUID().toString(),"b",s.b.pose.p.getUUID().toString()));out.put("server_tick",s.a.pose.p.server.getTickCount());out.put("phase_elapsed_ticks",s.a.pose.p.server.getTickCount()-s.phaseTick);out.put("requests",s.requests);
        out.put("a",snapshot(s.a));out.put("b",snapshot(s.b));out.put("poll_profile",Map.of("minimum_observe_interval_ms",1000,"phase_tick_limit",420,"episode_tick_limit",3000,"wall_seconds",300,"requests",512));
        out.put("phase_passed",ready&&s.checks.values().stream().noneMatch(Boolean.FALSE::equals)&&!s.aborted);
        out.put("b_card_slots_before",s.b.installedSlots);out.put("b_card_slots_current",s.b.cards==null?List.of():slots(s.b.cards));
        out.put("b_full_card_payload_unchanged_observation_only",s.b.cards!=null&&s.b.installedSlots.equals(slots(s.b.cards)));
        out.put("scope","Installed native cards and actual physical bus discovery/ownership/invalidation plus explicit current-bound RPCMethod callbacks. Native game-mode fixture bypasses packet aim/reach and mining-duration validation. NOT guest CPU/UART/RPC wire/installed program/render proof; retained cached-device behavior is out of contract, not a demonstrated product bug. No world cleanup or inventory/rootNBT restoration.");return out;}
    private static Object invoke(RPCDevice device,String method,Object... values){
        Session s=session;NativeSystemsFixture.need(s!=null&&!s.closed&&owners(s),"native callback host ownership/session invalid");
        boolean reachable=false;for(Host h:List.of(s.a,s.b))if(ready(h)&&h.bus().getDevices().stream().anyMatch(d->d==device)&&!ids(h,device).isEmpty())reachable=true;
        NativeSystemsFixture.need(reachable,"callback no longer in actual current controller membership; never invoke retained stale pointer");
        JsonArray parameters=new JsonArray();Gson gson=new Gson();for(Object value:values)parameters.add(gson.toJsonTree(value));
        RPCInvocation invocation=new RPCInvocation(){public JsonArray getParameters(){return parameters;}public Gson getGson(){return gson;}public Optional<Object[]> tryDeserializeParameters(RPCParameter... signature){if(signature.length!=parameters.size())return Optional.empty();Object[] args=new Object[signature.length];try{for(int i=0;i<args.length;i++)args[i]=gson.fromJson(parameters.get(i),signature[i].getType());}catch(JsonParseException|IllegalArgumentException bad){return Optional.empty();}return Optional.of(args);}};
        var group=device.getMethodGroups().stream().filter(g->g.getName().equals(method)).findFirst().orElseThrow(()->new IllegalStateException("native method missing "+method));var overload=group.findOverload(invocation).orElseThrow(()->new IllegalStateException("native RPC signature rejected "+method));
        try{return overload.invoke(invocation);}catch(Throwable error){if(error instanceof Error fatal)throw fatal;throw new CallbackFailure(error);}}
    private static final class CallbackFailure extends RuntimeException{CallbackFailure(Throwable error){super(error);}}
    private static boolean denied(RPCDevice d,String method,String reason,Object... values){try{invoke(d,method,values);return false;}catch(CallbackFailure e){Throwable cause=e;for(int i=0;i<8&&cause.getCause()!=null;i++)cause=cause.getCause();NativeSystemsFixture.need((cause instanceof IllegalStateException||cause instanceof IllegalArgumentException)&&String.valueOf(cause.getMessage()).contains(reason),"unexpected native callback failure: "+cause);return true;}}
}

/** Stateless fixture mechanics shared ONLY by the three newly approved nonshipping files.
 * Each family owns its own session and budgets; these helpers do not touch old Systems phases.
 */
final class NativeSystemsFixture {
    private NativeSystemsFixture(){}
    static final class Pose {
        final ServerPlayer p; final ServerLevel level; final Vec3 origin; final float yaw,pitch; final int selected,work;
        final Set<BlockPos> owned=new HashSet<>(),stones=new HashSet<>();
        BlockPos park;
        Pose(ServerPlayer p){this.p=p;level=p.serverLevel();origin=p.position();yaw=p.getYRot();pitch=p.getXRot();selected=p.getInventory().selected;int slot=-1;for(int i=0;i<9;i++)if(p.getInventory().getItem(i).isEmpty()){slot=i;break;}need(slot>=0,"empty authored-fixture hotbar slot required; no foreign inventory moves");work=slot;}
        void restore(){need(p.serverLevel()==level,"restore dimension drift");p.getInventory().selected=selected;p.teleportTo(level,origin.x,origin.y,origin.z,yaw,pitch);}
    }
    static void need(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    static void live(ServerPlayer p){need(p.server.isSameThread()&&p.connection!=null&&p.server.getPlayerList().getPlayer(p.getUUID())==p&&!(p instanceof net.minecraftforge.common.util.FakePlayer),"actual connected server-thread player required");
        need(p.isAlive()&&p.getGameProfile().getName().matches("SmokeClient[12]")&&p.gameMode.getGameModeForPlayer()==net.minecraft.world.level.GameType.SURVIVAL&&p.serverLevel().dimension().equals(net.minecraft.world.level.Level.OVERWORLD),"native Survival actor required");need(Math.abs(p.getX()-1000000)<=128&&Math.abs(p.getZ()-1000000)<=128&&Math.abs(p.getY()-316)<=32,"native actor outside exact continuous ordinary core");bounds(p.blockPosition());}
    static void guard(ServerPlayer p,JsonObject in){live(p);need(Boolean.getBoolean("bc.pack_test.gameplay_world")&&"debug".equals(System.getProperty("bc.pack_test.diagnostics.mode")),"native authored fixture disabled");need(in.has("player")&&p.getUUID().toString().equals(in.get("player").getAsString()),"native actor UUID mismatch");need(in.has("sequence")&&in.get("sequence").getAsInt()>0,"positive monotonic sequence required");}
    static Map<String,String> binding(JsonObject in,boolean client){Map<String,String> b=new LinkedHashMap<>();for(String key:List.of("run","nonce")){need(in.has(key)&&in.get(key).isJsonPrimitive(),"missing native binding "+key);b.put(key,in.get(key).getAsString());}
        need(b.get("run").equals(System.getProperty("bc.pack_test.diagnostics.run_id"))&&b.get("nonce").equals(System.getProperty("bc.pack_test.gameplay_"+(client?"client":"world")+".nonce")),"native run/nonce mismatch");
        for(String kind:List.of("client","server","runtime","support")){String key=kind+"_sha256",value=System.getProperty("bc.pack_test.diagnostics."+key,"");need(value.matches("[a-f0-9]{64}")&&(!in.has(key)||value.equals(in.get(key).getAsString())),"native candidate/support mismatch "+key);b.put(key,value);}return Map.copyOf(b);}
    static void bounds(BlockPos p){need(Math.abs((long)p.getX()-1000000)<=128&&Math.abs((long)p.getZ()-1000000)<=128&&Math.abs(p.getY()-316)<=32,"native fixture outside ordinary core");}
    static void preflight(ServerLevel level,BlockPos min,BlockPos max){for(BlockPos q:BlockPos.betweenClosed(min,max)){bounds(q);need(level.hasChunkAt(q)&&level.getBlockState(q).isAir(),"unloaded/foreign fixture footprint "+q);} }
    static ItemStack item(String name){var i=net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(name));need(i!=null&&i!=Items.AIR,"native item absent "+name);return new ItemStack(i);}
    static String block(ServerLevel l,BlockPos q){return String.valueOf(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(l.getBlockState(q).getBlock()));}
    static List<Integer> xyz(BlockPos q){return List.of(q.getX(),q.getY(),q.getZ());}
    static void give(Pose pose,ItemStack stack){pose.p.getInventory().selected=pose.work;need(pose.p.getMainHandItem().isEmpty(),"foreign authored work slot");pose.p.setItemInHand(InteractionHand.MAIN_HAND,stack);}
    static void near(Pose p,BlockPos q,boolean vertical){bounds(q);BlockPos feet=p.park==null?q.offset(0,0,3):p.park;pedestal(p,feet);p.p.teleportTo(p.level,feet.getX()+.5,feet.getY(),feet.getZ()+.5,-90,vertical?90:0);}
    static void pedestal(Pose p,BlockPos feet){bounds(feet);for(BlockPos q:List.of(feet,feet.above()))need(p.level.hasChunkAt(q)&&p.level.getBlockState(q).isAir(),"foreign camera headroom "+q);stone(p,feet.below());}
    static void stone(Pose p,BlockPos q){bounds(q);need(p.level.hasChunkAt(q),"unloaded authored support");if(p.level.getBlockState(q).isAir()){need(p.level.setBlock(q,Blocks.STONE.defaultBlockState(),3),"authored support failed");p.owned.add(q.immutable());p.stones.add(q.immutable());}else need(p.level.getBlockState(q).is(Blocks.STONE)||p.owned.contains(q),"foreign authored support "+q);}
    static void place(Pose p,BlockPos q,String item,boolean vertical){bounds(q);need(p.level.hasChunkAt(q)&&p.level.getBlockState(q).isAir(),"foreign native placement "+q);stone(p,q.below());useAuthored(p,q.below(),Direction.UP,item,vertical);need(block(p.level,q).equals(item),"native Survival placement rejected "+item);p.owned.add(q.immutable());}
    static void useAuthored(Pose p,BlockPos clicked,Direction face,String name,boolean vertical){near(p,clicked,vertical);give(p,item(name));boolean sneak=p.p.isShiftKeyDown();p.p.setShiftKeyDown(true);try{
        var result=p.p.gameMode.useItemOn(p.p,p.level,p.p.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(clicked).add(face.getStepX()*.499,face.getStepY()*.499,face.getStepZ()*.499),face,clicked,false));need(result.consumesAction()&&p.p.getMainHandItem().isEmpty(),"native authored item use/payment rejected "+name);
        }finally{p.p.setShiftKeyDown(sneak);}}
    static void breakOwned(Pose p,BlockPos q,String expected){need(p.owned.contains(q)&&block(p.level,q).equals(expected),"foreign native break target "+q);near(p,q,false);need(p.p.gameMode.destroyBlock(q)&&p.level.getBlockState(q).isAir(),"native owned break rejected");p.owned.remove(q);p.stones.remove(q);}
}
