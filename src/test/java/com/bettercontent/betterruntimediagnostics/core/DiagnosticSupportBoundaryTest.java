package com.bettercontent.betterruntimediagnostics.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticSupportBoundaryTest {
    private static final Path ROOT = Path.of("src/testSupport/java/com/bettercontent/runtimetestsupport");
    private static final Path DRIVER = Path.of("mixin/diagnostics/GameplayCutDriverMixin.java");

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
        // Authored native queen setup/transition is not player/storage manipulation. Permit
        // only these exact public bee-slot actions, each at most once in this fixture. All
        // additional writes, changed slots/factories and copies into other observers fail.
        if (relative.equals(Path.of("gameplay/ColonyWorldProbe.java"))) {
            source = compact(source);
            for (String action : List.of(
                    "apiary.getBeeItems().setStackInSlot(0, species.toStack(ItemsRegistration.QUEEN.get()));",
                    "apiary.getBeeItems().setStackInSlot(0, species == Species.INVALID ? net.minecraft.world.item.ItemStack.EMPTY : species.toStack(ItemsRegistration.QUEEN.get()));")) {
                String exact = compact(action);
                int start = source.indexOf(exact);
                if (start < 0) continue;
                assertEquals(start, source.lastIndexOf(exact), "authored bee action must occur at most once");
                source = source.substring(0, start) + source.substring(start + exact.length());
            }
        }
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
                    return "com.bettercontent.runtimetestsupport.mixin.diagnostics.GameplayParticleMixin".equals(mixin);
                return "net.minecraft.client.multiplayer.MultiPlayerGameMode".equals(target)
                    && Set.of("com.bettercontent.runtimetestsupport.mixin.diagnostics.GameplayCutMixin",
                        "com.bettercontent.runtimetestsupport.mixin.diagnostics.GameplayCutDriverMixin").contains(mixin);
            }
            """)), "exact client target plus Debug opt-in");
    }

    @Test void reservedNamespaceContainsExactlyRegisteredMixinTypes() throws Exception {
        String reserved = "com.bettercontent.runtimetestsupport.mixin";
        String config = Files.readString(Path.of("src/testSupport/resources/better_runtime_test_support.mixins.json"));
        assertTrue(config.contains("\"package\": \"" + reserved + "\""));
        assertTrue(config.contains("\"plugin\": \"com.bettercontent.runtimetestsupport.diagnostics.NonfiniteRotationMixinPlugin\""));
        var registered = new java.util.HashSet<String>();
        for (String manifest : List.of("better_runtime_test_support.mixins.json", "better_runtime_offline_fixture.mixins.json")) {
            var document = com.google.gson.JsonParser.parseString(Files.readString(
                    Path.of("src/testSupport/resources").resolve(manifest))).getAsJsonObject();
            String namespace = document.get("package").getAsString();
            assertTrue(namespace.equals(reserved) || namespace.startsWith(reserved + "."));
            for (String side : List.of("mixins", "client", "server")) {
                if (!document.has(side)) continue;
                for (var name : document.getAsJsonArray(side)) {
                    assertTrue(registered.add(namespace + "." + name.getAsString()), "duplicate mixin");
                }
            }
        }
        assertFalse(registered.isEmpty());
        var actual = new java.util.HashSet<String>();
        for (Path sourceRoot : List.of(Path.of("src/testSupport/java"), Path.of("src/testSupportPure/java"))) {
            try (var paths = Files.walk(sourceRoot)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(path);
                    var namespace = java.util.regex.Pattern.compile("(?m)^package ([\\w.]+);").matcher(source);
                    assertTrue(namespace.find(), path.toString());
                    String name = path.getFileName().toString().replace(".java", "");
                    String qualified = namespace.group(1) + "." + name;
                    boolean inside = namespace.group(1).equals(reserved) || namespace.group(1).startsWith(reserved + ".");
                    boolean mixin = java.util.regex.Pattern.compile("@Mixin\\s*\\(").matcher(source).find();
                    assertEquals(inside, mixin, "normal helpers/entrypoints/plugins cannot occupy reserved namespace: " + path);
                    assertEquals(qualified.replace('.', '/') + ".java", sourceRoot.relativize(path).toString().replace('\\', '/'));
                    if (inside) {
                        assertFalse(source.contains("@Mod("));
                        assertFalse(source.contains("implements IMixinConfigPlugin"));
                        assertTrue(java.util.regex.Pattern.compile("(?m)^public (?:abstract )?(?:class|interface) " + name + "\\b").matcher(source).find());
                        actual.add(qualified);
                    }
                }
            }
        }
        assertEquals(registered, actual);
        String entrypoint = Files.readString(ROOT.resolve("RuntimeTestSupport.java"));
        assertTrue(entrypoint.contains("package com.bettercontent.runtimetestsupport;"));
        assertTrue(entrypoint.contains("@Mod("));
        assertFalse(Files.readString(ROOT.resolve("gameplay/WorldHooksInteractionWorldProbe.java")).contains("@Mixin("));
    }

    @Test void authoredHoldRemainsBoundAndOptIn() throws Exception {
        assertDriverGates(Files.readString(ROOT.resolve("gameplay/GameplayCutDriver.java")),
                Files.readString(ROOT.resolve("diagnostics/NonfiniteRotationMixinPlugin.java")));
    }

    @Test void actionExceptionCannotLeakIntoObserversOrOtherOperations() throws Exception {
        String driver = Files.readString(ROOT.resolve(DRIVER));
        assertBoundary(DRIVER, driver);
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("mixin/diagnostics/GameplayCutMixin.java"), driver));
        for (String mutated : List.of(
                driver.replace("if (GameplayCutDriver.suppressUnrelatedRelease()) ", ""),
                driver.replace("method = \"stopDestroyBlock\"", "method = \"continueDestroyBlock\""),
                driver.replace("method = \"startDestroyBlock\"", "method = \"startDestroyBlock\", cancellable = true"),
                driver.replace("ci.cancel();", "ci.cancel(); ci.cancel();"),
                driver.replace("ci.cancel();", "ci.setReturnValue(false);"),
                driver.replace("ci.cancel();", "inventory.setStackInSlot(0, stack); ci.cancel();")))
            assertThrows(AssertionError.class, () -> assertBoundary(DRIVER, mutated));
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("GameplaySpoilageProbe.java"), "inventory.setStackInSlot(0, stack);"));
        assertBoundary(Path.of("gameplay/ColonyWorldProbe.java"), "apiary.getBeeItems().setStackInSlot(0, species.toStack(ItemsRegistration.QUEEN.get()));");
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("gameplay/ColonyWorldProbe.java"), "apiary.getBeeItems().setStackInSlot(0, species.toStack(ItemsRegistration.QUEEN.get())); inventory.setStackInSlot(0, stack);"));
        String transition = "apiary.getBeeItems().setStackInSlot(0, species == Species.INVALID ? net.minecraft.world.item.ItemStack.EMPTY : species.toStack(ItemsRegistration.QUEEN.get()));";
        assertBoundary(Path.of("gameplay/ColonyWorldProbe.java"), transition);
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("gameplay/ColonyWorldProbe.java"), transition + transition));
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("gameplay/ColonyWorldProbe.java"), transition.replace("setStackInSlot(0", "setStackInSlot(1")));
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("gameplay/ColonyWorldProbe.java"), transition.replace("ItemsRegistration.QUEEN", "ItemsRegistration.DRONE")));
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("gameplay/OtherProbe.java"), transition));
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("gameplay/ColonyWorldProbe.java"), transition + "inventory.setStackInSlot(0, stack);"));
        assertThrows(AssertionError.class, () -> assertBoundary(Path.of("mixin/diagnostics/GameplayParticleMixin.java"), "@Inject(cancellable=true) void observe() { ci.cancel(); }"));
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
    private static void assertWhaleReadOnly(String source) {
        String code = compact(source);
        assertFalse(code.contains("@Mixin"));
        for (String operation : List.of(".set", ".add", ".remove", ".discard(", ".kill(", ".teleport",
                ".send", ".cancel(", ".write", ".delete", ".create", ".move(", ".copy(", ".put(",
                ".execute(", ".invoke(", ".getDeclared", "java.lang.reflect", "Unsafe"))
            assertFalse(code.contains(operation), "whale observer action: " + operation);
    }

    @Test void whaleObserverIsReadOnlyAndClientOnly() throws Exception {
        String observer = Files.readString(ROOT.resolve("whale/WhaleClientObserver.java"));
        String pure = Files.readString(Path.of("src/testSupportPure/java/com/bettercontent/runtimetestsupport/whale/WhaleObservation.java"));
        assertWhaleReadOnly(observer);
        assertWhaleReadOnly(pure);
        assertTrue(observer.contains("value = Dist.CLIENT"));
        assertTrue(observer.contains("event.phase != TickEvent.Phase.END"));
        assertTrue(observer.contains("event.getLevel() != mc.level"));
        assertTrue(observer.contains("mc.level.getEntity(entity.getId()) == entity"));
        assertTrue(observer.contains("ClientPlayerNetworkEvent.LoggingOut"));
        assertTrue(observer.contains("mc.getConnection() != null && mc.getConnection().getConnection().isConnected()"));
        assertTrue(observer.contains("boolean online = networkConnected(mc)"));
        assertTrue(observer.contains("networkConnected(mc), mc.level instanceof ClientLevel"));
        assertFalse(pure.contains("import net.minecraft"));
        for (String mutation : List.of("entity.setYRot(0);", "event.setCanceled(true);", "entity.discard();",
                "connection.send(packet);", "Files.write(path, bytes);", "@Mixin(Entity.class)"))
            assertThrows(AssertionError.class, () -> assertWhaleReadOnly(observer + mutation));
    }

    @Test void observersRemainNonCancellingAndStorageFree() throws Exception {
        Path root=Path.of("src/testSupport/java/com/bettercontent/runtimetestsupport");
        try(var paths=Files.walk(root)) {
            for(Path p:paths.filter(Files::isRegularFile).toList()) {
                assertBoundary(root.relativize(p), Files.readString(p));
            }
        }
        String rotation=Files.readString(root.resolve("mixin/diagnostics/NonfiniteRotationMixin.java"));
        String species=Files.readString(root.resolve("mixin/diagnostics/UntamedSpeciesObservationMixin.java"));
        assertTrue(rotation.contains("cancellable = false"));assertTrue(species.contains("cancellable = false"));
        assertTrue(species.contains("require = 1"));assertTrue(species.contains("getNumberOfSpecies"));
        String plugin=Files.readString(root.resolve("diagnostics/NonfiniteRotationMixinPlugin.java"));
        assertTrue(plugin.contains("bc.pack_test.diagnostics.run_id"));assertTrue(plugin.contains("4.0.4"));
        assertFalse(plugin.contains("ServerGamePacketListenerImpl"));
        String config=Files.readString(Path.of("src/testSupport/resources/better_runtime_test_support.mixins.json"));
        assertFalse(config.contains("Packet"));assertTrue(config.contains("NonfiniteRotationMixin"));assertTrue(config.contains("UntamedSpeciesObservationMixin"));
    }
}
