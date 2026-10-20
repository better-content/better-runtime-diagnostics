package com.bettercontent.runtimetestsupport.gameplay;

import dev.ghen.thirst.foundation.common.capability.ModCapabilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Native timed manual use, not a direct finishUsingItem invocation. Known entitlement fixtures
 * are restored after the observation. No production serving/capability state is bypassed. */
@Mod.EventBusSubscriber(modid = "better_runtime_test_support")
public final class PlantDrinkProbe {
    private static Session active;
    private PlantDrinkProbe() {}
    private static final class Session {
        final UUID player;
        final String kind;
        final int started, slot, thirst, quenched;
        final ItemStack previous;
        final MobEffectInstance poison, regeneration;
        int finishes;
        boolean interrupted;
        Session(ServerPlayer p, String k) {
            player = p.getUUID(); kind = k; started = p.tickCount; slot = p.getInventory().selected;
            previous = p.getMainHandItem().copy();
            var cap = p.getCapability(ModCapabilities.PLAYER_THIRST).orElseThrow(() -> new IllegalStateException("native Thirst capability absent"));
            thirst = cap.getThirst(); quenched = cap.getQuenched();
            poison = p.getEffect(MobEffects.POISON) == null ? null : new MobEffectInstance(p.getEffect(MobEffects.POISON));
            regeneration = p.getEffect(MobEffects.REGENERATION) == null ? null : new MobEffectInstance(p.getEffect(MobEffects.REGENERATION));
        }
    }
    public static Map<String, Object> start(ServerPlayer p, String kind) {
        if (active != null || !List.of("plain", "full", "partial", "bogclear", "suncrest").contains(kind))
            throw new IllegalStateException("invalid or overlapping native plant drink fixture");
        active = new Session(p, kind);
        var cap = p.getCapability(ModCapabilities.PLAYER_THIRST).resolve().orElseThrow();
        p.removeEffect(MobEffects.POISON); p.removeEffect(MobEffects.REGENERATION);
        cap.setThirst(kind.equals("full") ? 20 : 10); cap.setQuenched(0); cap.updateThirstData(p);
        if (kind.equals("bogclear") || kind.equals("partial")) p.addEffect(new MobEffectInstance(MobEffects.POISON, 1200));
        String id = switch (kind) { case "bogclear", "partial" -> "bogclear_tonic"; case "suncrest" -> "suncrest_tonic"; default -> "orange_juice"; };
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("better_drinking_water", id));
        if (item == null || item == Items.AIR) throw new IllegalStateException("selected candidate lacks native plant drink " + id);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        var outcome = p.gameMode.useItem(p, p.serverLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (kind.equals("full")) {
            if (outcome.consumesAction() || p.isUsingItem()) throw new IllegalStateException("full Thirst admitted native plant drink");
        } else if (!outcome.consumesAction() || !p.isUsingItem()) throw new IllegalStateException("native manual drink did not begin");
        return Map.of("case", kind, "item", "better_drinking_water:" + id, "started_tick", p.tickCount,
            "native_use_result", outcome.toString(), "ready", false, "fixture_thirst", cap.getThirst());
    }
    /** Called each actual END player tick, never advances native clocks. */
    public static void tick(ServerPlayer p) {
        if (active == null || !active.player.equals(p.getUUID())) return;
        if (active.kind.equals("partial") && !active.interrupted && p.tickCount - active.started >= 16) {
            if (!p.isUsingItem() || p.getUseItemRemainingTicks() <= 0) throw new IllegalStateException("half use lacked active native use");
            p.stopUsingItem(); active.interrupted = true;
        }
    }
    @SubscribeEvent public static void finished(LivingEntityUseItemEvent.Finish event) {
        if (active != null && event.getEntity().getUUID().equals(active.player)
                && ForgeRegistries.ITEMS.getKey(event.getItem().getItem()).getNamespace().equals("better_drinking_water")) active.finishes++;
    }
    public static Map<String, Object> observe(ServerPlayer p) {
        if (active == null || !active.player.equals(p.getUUID())) throw new IllegalStateException("no native plant drink fixture");
        Session s = active;
        if (p.tickCount - s.started < 40) return Map.of("ready", false, "elapsed_native_ticks", p.tickCount - s.started);
        var cap = p.getCapability(ModCapabilities.PLAYER_THIRST).resolve().orElseThrow();
        Map<String, Object> row = new LinkedHashMap<>();
        try {
            boolean denied = s.kind.equals("full") || s.kind.equals("partial");
            if (p.isUsingItem() || s.finishes != (denied ? 0 : 1)) throw new IllegalStateException("native use completion/event count mismatch");
            if (denied) {
                if (!p.getMainHandItem().is(s.kind.equals("full") ? ForgeRegistries.ITEMS.getValue(new ResourceLocation("better_drinking_water:orange_juice"))
                        : ForgeRegistries.ITEMS.getValue(new ResourceLocation("better_drinking_water:bogclear_tonic"))) || p.getMainHandItem().getCount() != 1)
                    throw new IllegalStateException("denied/partial use consumed plant vessel");
                if (cap.getThirst() != (s.kind.equals("full") ? 20 : 10) || cap.getQuenched() != 0)
                    throw new IllegalStateException("denied/partial plant use awarded hydration");
                if (s.kind.equals("partial") && (!s.interrupted || !p.hasEffect(MobEffects.POISON)))
                    throw new IllegalStateException("half-serving released completion-only poison action");
            } else {
                if (!p.getMainHandItem().is(Items.GLASS_BOTTLE) || p.getMainHandItem().getCount() != 1)
                    throw new IllegalStateException("native plant drink did not conserve one glass vessel");
                if (cap.getThirst() <= 10 || cap.getQuenched() <= 0) throw new IllegalStateException("completed plant drink awarded no native hydration");
                if (s.kind.equals("plain") && (p.hasEffect(MobEffects.REGENERATION) || cap.getThirst() != 16 || cap.getQuenched() != 8))
                    throw new IllegalStateException("plain orange juice leaked specialist effect or wrong hydration");
                if (s.kind.equals("bogclear") && p.hasEffect(MobEffects.POISON)) throw new IllegalStateException("completed bogclear failed native poison removal");
                if (s.kind.equals("suncrest") && !p.hasEffect(MobEffects.REGENERATION)) throw new IllegalStateException("suncrest lacks native regeneration");
            }
            row.put("ready", true); row.put("case", s.kind); row.put("elapsed_native_ticks", p.tickCount - s.started);
            row.put("finish_events", s.finishes); row.put("thirst", cap.getThirst()); row.put("quenched", cap.getQuenched());
            row.put("held_item", ForgeRegistries.ITEMS.getKey(p.getMainHandItem().getItem()).toString()); row.put("held_count", p.getMainHandItem().getCount());
            row.put("poison", p.hasEffect(MobEffects.POISON)); row.put("regeneration", p.hasEffect(MobEffects.REGENERATION));
            row.put("limitation", "Five manual native cases only; automatic fractional reserve, blackout, every catalogue entitlement and mana/blink require separate cases.");
            return row;
        } finally {
            p.stopUsingItem(); p.getInventory().selected = s.slot; p.setItemInHand(InteractionHand.MAIN_HAND, s.previous);
            cap.setThirst(s.thirst); cap.setQuenched(s.quenched); cap.updateThirstData(p);
            p.removeEffect(MobEffects.POISON); p.removeEffect(MobEffects.REGENERATION);
            if (s.poison != null) p.addEffect(s.poison); if (s.regeneration != null) p.addEffect(s.regeneration);
            active = null;
        }
    }
}
