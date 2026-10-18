package com.bettercontent.betterruntimediagnostics.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticSupportBoundaryTest {
    private static final Path ROOT = Path.of("src/testSupport/java/com/bettercontent/runtimetestsupport");
    private static final Path DRIVER = Path.of("diagnostics/GameplayCutDriverMixin.java");

    // A reviewed authored action, never a passive observer. Pin the entire hook body so that
    // its single cancellation cannot migrate to another target, become unconditional, or write storage.
    private static final String DRIVER_BODY = """
        {
            @Inject(method = "startDestroyBlock", at = @At("HEAD"), require = 1)
            private void bc$newTarget(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> ci) {
                GameplayCutDriver.stop(Minecraft.getInstance());
            }
            @Inject(method = "stopDestroyBlock", at = @At("HEAD"), cancellable = true, require = 1)
            private void bc$authoredHold(CallbackInfo ci) {
                if (GameplayCutDriver.suppressUnrelatedRelease()) ci.cancel();
            }
        }
        """;

    private static String compact(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\r\\n]*", "").replaceAll("\\s+", "");
    }

    private static void assertBoundary(Path relative, String source) {
        boolean driver = DRIVER.equals(relative);
        for (String token : List.of("bc.native.inventory", "bc.journal.contract", "NativeContract", "NativeCheckpoint",
                "NativeClientPacketMixin", "setStackInSlot", "setInvulnerable", "callback.cancel",
                "callback.setReturnValue", "entity.setYRot", "entity.setXRot"))
            assertFalse(source.contains(token), relative + ": " + token);
        String code = compact(source);
        assertFalse(code.contains(".setReturnValue("), relative + ": return override");
        if (!driver) {
            assertFalse(code.contains("cancellable=true"), relative + ": cancellable observer");
            assertFalse(code.contains(".cancel("), relative + ": cancelling observer");
            return;
        }
        assertTrue(code.contains("importnet.minecraft.client.multiplayer.MultiPlayerGameMode;"));
        String declaration = "@Mixin(value=MultiPlayerGameMode.class,priority=2000)publicabstractclassGameplayCutDriverMixin";
        int start = code.indexOf(declaration);
        assertTrue(start >= 0, "driver must target the exact native client class and priority");
        assertEquals(compact(DRIVER_BODY), code.substring(start + declaration.length()), "exact reviewed action only");
    }

    private static void assertDriverGates(String source, String plugin) {
        String code = compact(source);
        assertTrue(code.contains(compact("""
            private static boolean valid(Minecraft mc) {
                return target != null && GameplayClientProbe.observing() && System.nanoTime() < deadline
                    && "debug".equals(System.getProperty("bc.pack_test.diagnostics.mode"))
                    && run.equals(System.getProperty("bc.pack_test.diagnostics.run_id"))
                    && nonce.equals(System.getProperty("bc.pack_test.gameplay_client.nonce"))
                    && candidate.equals(System.getProperty("bc.pack_test.diagnostics.client_sha256"))
                    && mc.level != null && mc.level.dimension().equals(Level.OVERWORLD) && mc.getConnection() != null
                    && mc.player != null && mc.player.isAlive() && player.equals(mc.player.getUUID())
                    && mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SURVIVAL && mc.screen == null
                    && !mc.options.keyAttack.isDown() && !mc.options.keyUse.isDown()
                    && mc.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target)) <= 36
                    && mc.level.hasChunkAt(target);
            }
            """)), "all active/identity/reach/native-input/deadline gates remain conjunctive");
        assertTrue(code.contains(compact("""
            public static boolean suppressUnrelatedRelease() {
                if (target == null) return false;
                if (!valid(Minecraft.getInstance())) { clear(); return false; }
                return true;
            }
            """)));
        assertTrue(code.contains(compact("""
            public static void stop(Minecraft mc) {
                boolean wasActive = target != null;
                clear();
                if (wasActive && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            }
            """)), "explicit stop clears before native release");
        for (String guard : List.of("deadline = System.nanoTime() + 90_000_000_000L;",
                "if (!Boolean.getBoolean(\"bc.pack_test.gameplay_client\") || mc.player == null",
                "if (!pos.equals(new BlockPos(1_000_000, 316, 1_000_020)))",
                "if (tick <= lastTick) return;",
                "if (!client.equals(request.get(\"client_sha256\").getAsString()) || !server.equals(request.get(\"server_sha256\").getAsString()))"))
            assertTrue(code.contains(compact(guard)), guard);
        String gate = compact(plugin);
        assertTrue(gate.contains("enabled=explicitFixture(run,directory);"));
        assertTrue(gate.contains("if(!enabled)returnfalse;if(gameplayMixin(target,mixin,System.getProperty(\"bc.pack_test.diagnostics.mode\",\"\"),Boolean.getBoolean(\"bc.pack_test.gameplay_client\")))returntrue;"));
        assertTrue(gate.contains(compact("""
            public static boolean gameplayMixin(String target, String mixin, String mode, boolean requested) {
                if (!requested || !"debug".equals(mode)) return false;
                if ("net.minecraft.client.particle.ParticleEngine".equals(target))
                    return "com.bettercontent.runtimetestsupport.diagnostics.GameplayParticleMixin".equals(mixin);
                return "net.minecraft.client.multiplayer.MultiPlayerGameMode".equals(target)
                    && Set.of("com.bettercontent.runtimetestsupport.diagnostics.GameplayCutMixin",
                        "com.bettercontent.runtimetestsupport.diagnostics.GameplayCutDriverMixin").contains(mixin);
            }
            """)), "exact client target plus Debug opt-in");
    }

    @Test void authoredHoldRemainsBoundAndOptIn() throws Exception {
        assertDriverGates(Files.readString(ROOT.resolve("gameplay/GameplayCutDriver.java")),
                Files.readString(ROOT.resolve("diagnostics/NonfiniteRotationMixinPlugin.java")));
    }

    @Test void actionExceptionCannotLeakIntoObserversOrOtherOperations() throws Exception {
        String driver = Files.readString(ROOT.resolve(DRIVER));
        assertBoundary(DRIVER, driver);
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("diagnostics/GameplayCutMixin.java"), driver));
        for (String mutated : List.of(
                driver.replace("if (GameplayCutDriver.suppressUnrelatedRelease()) ", ""),
                driver.replace("method = \"stopDestroyBlock\"", "method = \"continueDestroyBlock\""),
                driver.replace("method = \"startDestroyBlock\"", "method = \"startDestroyBlock\", cancellable = true"),
                driver.replace("ci.cancel();", "ci.cancel(); ci.cancel();"),
                driver.replace("ci.cancel();", "ci.setReturnValue(false);"),
                driver.replace("ci.cancel();", "inventory.setStackInSlot(0, stack); ci.cancel();")))
            assertThrows(AssertionError.class, () -> assertBoundary(DRIVER, mutated));
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("GameplaySpoilageProbe.java"), "inventory.setStackInSlot(0, stack);"));
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("diagnostics/GameplayParticleMixin.java"), "@Inject(cancellable=true) void observe() { ci.cancel(); }"));
    }

    @Test void weakenedDriverGatesAreRejected() throws Exception {
        String driver = Files.readString(ROOT.resolve("gameplay/GameplayCutDriver.java"));
        String plugin = Files.readString(ROOT.resolve("diagnostics/NonfiniteRotationMixinPlugin.java"));
        for (String guard : List.of("target != null", "GameplayClientProbe.observing()", "System.nanoTime() < deadline",
                "mc.gameMode.getPlayerMode() == GameType.SURVIVAL", "<= 36", "90_000_000_000L",
                "if (target == null) return false;", "clear();"))
            assertThrows(AssertionError.class, () -> assertDriverGates(driver.replace(guard, "true"), plugin), guard);
        assertThrows(AssertionError.class, () -> assertDriverGates(driver, plugin.replace("if (!enabled) return false;", "")));
        assertThrows(AssertionError.class, () -> assertDriverGates(driver, plugin.replace("!requested ||", "")));
    }
    @Test void observersRemainNonCancellingAndStorageFree() throws Exception {
        Path root=Path.of("src/testSupport/java/com/bettercontent/runtimetestsupport");
        try(var paths=Files.walk(root)) {
            for(Path p:paths.filter(Files::isRegularFile).toList()) {
                assertBoundary(root.relativize(p), Files.readString(p));
            }
        }
        String rotation=Files.readString(root.resolve("diagnostics/NonfiniteRotationMixin.java"));
        String species=Files.readString(root.resolve("diagnostics/UntamedSpeciesObservationMixin.java"));
        assertTrue(rotation.contains("cancellable = false"));assertTrue(species.contains("cancellable = false"));
        assertTrue(species.contains("require = 1"));assertTrue(species.contains("getNumberOfSpecies"));
        String plugin=Files.readString(root.resolve("diagnostics/NonfiniteRotationMixinPlugin.java"));
        assertTrue(plugin.contains("bc.pack_test.diagnostics.run_id"));assertTrue(plugin.contains("4.0.4"));
        assertFalse(plugin.contains("ServerGamePacketListenerImpl"));
        String config=Files.readString(Path.of("src/testSupport/resources/better_runtime_test_support.mixins.json"));
        assertFalse(config.contains("Packet"));assertTrue(config.contains("NonfiniteRotationMixin"));assertTrue(config.contains("UntamedSpeciesObservationMixin"));
    }
}
