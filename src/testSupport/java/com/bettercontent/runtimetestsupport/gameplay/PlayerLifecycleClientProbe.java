package com.bettercontent.runtimetestsupport.gameplay;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Nonshipping native network client half. No direct server access, synthetic callback or simulated pointer.
 * LocalPlayer.respawn() sends vanilla ServerboundClientCommandPacket(PERFORM_RESPAWN).
 * Server Clone/Respawn witnesses, not this request acknowledgment, are the authority for replacement. */
public final class PlayerLifecycleClientProbe {
    private static LocalPlayer original;
    private static Map<String, String> episode;
    private static int lastSequence;
    private static long startedNanos;
    private static boolean requested;
    private PlayerLifecycleClientProbe() {}

    public static Map<String, Object> run(Minecraft mc, JsonObject input) {
        check(mc.isSameThread(), "native lifecycle action must use client thread");
        check(Boolean.getBoolean("bc.pack_test.gameplay_client"), "client lifecycle driver disabled");
        check("debug".equals(System.getProperty("bc.pack_test.diagnostics.mode")), "client lifecycle requires Debug");
        check(mc.player != null && mc.level != null && mc.gameMode != null && mc.getConnection() != null, "native connected client required");
        check(mc.level.dimension().equals(Level.OVERWORLD) && mc.gameMode.getPlayerMode() == GameType.SURVIVAL, "native lifecycle Survival overworld required");
        LocalPlayer player = mc.player;
        check(input.get("player").getAsString().equals(player.getUUID().toString()), "native client UUID mismatch");
        Map<String, String> binding = binding(input);
        int sequence = input.get("sequence").getAsInt();
        if (episode == null) {
            check(input.get("action").getAsString().equals("lifecycle-respawn"), "must arm from actual dead native player");
            check(player.isDeadOrDying() && mc.screen instanceof DeathScreen, "native DeathScreen/dead player not observed");
            requireCore(player);
            episode = binding; original = player; startedNanos = System.nanoTime(); lastSequence = sequence;
        } else {
            check(episode.equals(binding) && original.getUUID().equals(player.getUUID()), "native client episode/candidate drift");
            check(sequence > lastSequence, "replayed native client lifecycle action"); lastSequence = sequence;
        }
        check(System.nanoTime() - startedNanos <= 600_000_000_000L, "client lifecycle deadline expired");
        String action = input.get("action").getAsString();
        if (action.equals("lifecycle-respawn")) {
            check(!requested && player == original && player.isDeadOrDying() && mc.screen instanceof DeathScreen, "respawn requires original dead network player, once only");
            // Vanilla DeathScreen enables its action after twenty native death ticks. Do not bypass that readiness.
            if (player.deathTime >= 20) {
                requireCore(player);
                player.respawn();
                requested = true;
            }
        } else check(action.equals("lifecycle-observe"), "unknown client lifecycle action");
        boolean replacement = requested && player != original && player.getUUID().equals(original.getUUID());
        if (replacement) requireCore(player);
        boolean ready = replacement && player.isAlive() && !(mc.screen instanceof DeathScreen);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidate_binding", episode); result.put("player", player.getUUID().toString());
        result.put("respawn_requested", requested); result.put("ready", ready); result.put("passed", ready);
        result.put("death_screen", mc.screen instanceof DeathScreen); result.put("native_death_time", player.deathTime);
        result.put("original_local_player_identity", System.identityHashCode(original));
        result.put("current_local_player_identity", System.identityHashCode(player));
        result.put("replacement_local_player", replacement); result.put("alive", player.isAlive());
        result.put("position", List.of(player.getX(), player.getY(), player.getZ()));
        result.put("native_action", "LocalPlayer.respawn -> ServerboundClientCommandPacket.Action.PERFORM_RESPAWN");
        result.put("origin", "native network respawn action; no direct server callbacks and no pointer/keys synthesized");
        return result;
    }

    private static Map<String, String> binding(JsonObject input) {
        Map<String, String> result = new LinkedHashMap<>();
        String run = System.getProperty("bc.pack_test.diagnostics.run_id", "");
        String nonce = System.getProperty("bc.pack_test.gameplay_client.nonce", "");
        check(run.matches("[A-Za-z0-9_.-]{1,128}") && UUID.fromString(nonce).toString().equals(nonce), "invalid native client binding");
        check(run.equals(input.get("run").getAsString()) && nonce.equals(input.get("nonce").getAsString()), "native client run/nonce mismatch");
        result.put("run", run); result.put("nonce", nonce);
        for (String kind : List.of("client", "server", "runtime", "support")) {
            String hash = System.getProperty("bc.pack_test.diagnostics." + kind + "_sha256", "");
            check(hash.matches("[a-f0-9]{64}"), "native client missing artifact digest");
            if (input.has(kind + "_sha256")) check(hash.equals(input.get(kind + "_sha256").getAsString()), "native client artifact mismatch");
            result.put(kind + "_sha256", hash);
        }
        return Map.copyOf(result);
    }
    private static void requireCore(LocalPlayer player) {
        check(Math.abs(player.getX() - 1_000_000) <= 128 && Math.abs(player.getZ() - 1_000_000) <= 128
                && Math.abs(player.getY() - 316) <= 32, "native client lifecycle outside fixture core");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
