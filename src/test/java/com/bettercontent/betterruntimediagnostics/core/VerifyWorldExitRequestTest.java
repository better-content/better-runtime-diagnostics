package com.bettercontent.betterruntimediagnostics.core;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VerifyWorldExitRequestTest {
    private static final String RUN = "20261008T170000Z-123";
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("OfflinePlayer:SmokeWorld".getBytes(StandardCharsets.UTF_8));
    private static final String PREFIX = "bc.pack_test.verify_exit.";

    private static final class Fixture implements AutoCloseable {
        final Path owner = Path.of("build/verify-exit-test", UUID.randomUUID().toString()).toAbsolutePath();
        final Path root = owner.resolve(RUN + "/singleplayer/fixture");
        final Path world = root.resolve("client-6/saves/DebugWorld"), dir = root.resolve("journal-contract");
        final VerifyWorldExitRequest.Binding binding;
        Fixture() throws Exception {
            Files.createDirectories(world); Files.createDirectories(dir);
            binding = new VerifyWorldExitRequest.Binding(UUID.randomUUID(), RUN, "SmokeWorld", PLAYER, root, world, dir);
        }
        Map<String,String> properties() {
            var p = new HashMap<String,String>();
            p.put("bc.pack_test.world", "verify"); p.put("bc.journal.contract.singleplayer", "verify");
            p.put("bc.journal.contract.run_id", RUN);
            p.put("bc.journal.contract.player", "SmokeWorld"); p.put("bc.journal.contract.dir", dir.toString());
            p.put(PREFIX + "nonce", binding.nonce().toString()); p.put(PREFIX + "player_uuid", PLAYER.toString());
            p.put(PREFIX + "fixture_root", root.toString()); p.put(PREFIX + "world_root", world.toString());
            p.put(PREFIX + "request_dir", dir.toString()); return p;
        }
        byte[] payload() { return VerifyWorldExitRequestTest.payload(binding.fields()); }
        void request() throws Exception { Files.write(binding.requestFile(), payload()); }
        @Override public void close() throws Exception {
            try (var paths = Files.walk(owner)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static byte[] payload(Map<String,String> fields) {
        StringBuilder text = new StringBuilder(); fields.forEach((k,v) -> text.append(k).append('=').append(v).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static VerifyWorldExitRequest.LoadedIdentity identity(Fixture f, Object server, Object level, Object player) {
        return new VerifyWorldExitRequest.LoadedIdentity(server, level, player, f.world, PLAYER, "SmokeWorld");
    }

    @Test void absentPropertyAndOtherModesRemainInert() throws Exception {
        assertTrue(VerifyWorldExitRequest.configured(key -> null).isEmpty());
        try (var f = new Fixture()) {
            var p = f.properties(); assertEquals(f.binding, VerifyWorldExitRequest.configured(p::get).orElseThrow());
            for (String key : p.keySet()) {
                var missing = new HashMap<>(p); missing.remove(key);
                assertTrue(VerifyWorldExitRequest.configured(missing::get).isEmpty(), key);
            }
            for (String mode : new String[]{"save", "", "dedicated"}) {
                var wrong = new HashMap<>(p); wrong.put("bc.pack_test.world", mode);
                assertTrue(VerifyWorldExitRequest.configured(wrong::get).isEmpty());
            }
        }
    }

    @Test void onlyCanonicalOwnedDirectoriesAndExactSupportedSaveFolderAreAccepted() throws Exception {
        try (var f = new Fixture()) {
            for (String key : new String[]{PREFIX + "world_root", PREFIX + "request_dir", PREFIX + "fixture_root"}) {
                var p = f.properties(); p.put(key, p.get(key) + "/..");
                assertTrue(VerifyWorldExitRequest.configured(p::get).isEmpty());
            }
            var p = f.properties(); p.put("bc.journal.contract.dir", f.root.toString());
            assertTrue(VerifyWorldExitRequest.configured(p::get).isEmpty());
            Path otherWorld = f.root.resolve("client-6/saves/world"); Files.createDirectory(otherWorld);
            p = f.properties(); p.put(PREFIX + "world_root", otherWorld.toString());
            assertTrue(VerifyWorldExitRequest.configured(p::get).isEmpty());
            Path linked = f.root.resolve("linked-world"); Files.createSymbolicLink(linked, f.world);
            p = f.properties(); p.put(PREFIX + "world_root", linked.toString());
            assertTrue(VerifyWorldExitRequest.configured(p::get).isEmpty());
            p = f.properties(); p.put(PREFIX + "nonce", "1-1-1-1-1");
            assertTrue(VerifyWorldExitRequest.configured(p::get).isEmpty());
            // No LevelName criterion: a captured dedicated level may retain native LevelName=world.
            assertEquals(f.world, VerifyWorldExitRequest.configured(f.properties()::get).orElseThrow().worldRoot());
        }
    }

    @Test void requestSchemaNonceAndEveryMetadataFieldMustMatch() throws Exception {
        try (var f = new Fixture()) {
            assertFalse(VerifyWorldExitRequest.requested(f.binding));
            assertTrue(VerifyWorldExitRequest.validPayload(f.binding, f.payload()));
            for (String key : f.binding.fields().keySet()) {
                var wrong = new HashMap<>(f.binding.fields()); wrong.put(key, "mismatch");
                assertFalse(VerifyWorldExitRequest.validPayload(f.binding, payload(wrong)), key);
                wrong.remove(key); assertFalse(VerifyWorldExitRequest.validPayload(f.binding, payload(wrong)), key);
            }
            assertFalse(VerifyWorldExitRequest.validPayload(f.binding, new byte[2049]));
            assertFalse(VerifyWorldExitRequest.validPayload(f.binding, new byte[]{(byte)0xff}));
            assertFalse(VerifyWorldExitRequest.validPayload(f.binding, "not-a-request".getBytes(StandardCharsets.UTF_8)));
            assertFalse(VerifyWorldExitRequest.validPayload(f.binding,
                (new String(f.payload(), StandardCharsets.UTF_8) + "mode=verify\n").getBytes(StandardCharsets.UTF_8)));
            f.request(); assertTrue(VerifyWorldExitRequest.requested(f.binding));
            Files.write(f.binding.requestFile(), new byte[2049]); assertFalse(VerifyWorldExitRequest.requested(f.binding));
        }
    }

    @Test void requestSymlinkAndSymlinkAncestryNeverAuthorizeExit() throws Exception {
        try (var f = new Fixture()) {
            Path target = f.root.resolve("outside-request"); Files.write(target, f.payload());
            Files.createSymbolicLink(f.binding.requestFile(), target);
            assertFalse(VerifyWorldExitRequest.requested(f.binding));
            Files.delete(f.binding.requestFile()); Files.delete(f.dir);
            Path alternate = f.root.resolve("alternate"); Files.createDirectory(alternate);
            Files.createSymbolicLink(f.dir, alternate);
            assertFalse(VerifyWorldExitRequest.safeBinding(f.binding));
            assertFalse(VerifyWorldExitRequest.requested(f.binding));
        }
    }

    @Test void exactActualLoadedReferencesAndEmiReadinessAreRequiredBeforeOneShotClaim() throws Exception {
        try (var f = new Fixture()) {
            Object server = new Object(), level = new Object(), player = new Object();
            var loaded = identity(f, server, level, player);
            var gate = VerifyWorldExitRequest.afterLoaded(f.binding, loaded).orElseThrow();
            assertFalse(gate.claim(loaded, true)); // no request
            f.request(); assertFalse(gate.claim(loaded, false));
            assertFalse(gate.claim(identity(f, new Object(), level, player), true));
            assertFalse(gate.claim(identity(f, server, new Object(), player), true));
            assertFalse(gate.claim(identity(f, server, level, new Object()), true));
            assertFalse(gate.claim(null, true));
            assertFalse(VerifyWorldExitRequest.afterLoaded(f.binding, new VerifyWorldExitRequest.LoadedIdentity(
                server, level, player, f.root, PLAYER, "SmokeWorld")).isPresent());
            assertFalse(VerifyWorldExitRequest.afterLoaded(f.binding, new VerifyWorldExitRequest.LoadedIdentity(
                server, level, player, f.world, UUID.randomUUID(), "SmokeWorld")).isPresent());
            assertFalse(VerifyWorldExitRequest.afterLoaded(f.binding, new VerifyWorldExitRequest.LoadedIdentity(
                server, level, player, f.world, PLAYER, "OtherPlayer")).isPresent());
            assertTrue(gate.claim(loaded, true)); assertTrue(gate.started());
            assertFalse(gate.claim(loaded, true)); // simulated normal clearLevel ClientTick re-entry
        }
    }

    @Test void productionDefaultAndNativeSourceCallOrderRemainGuarded() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/bettercontent/betterruntimediagnostics/DebugWorldProbe.java"));
        int start = source.indexOf("private static void exitVerifiedFixtureIfRequested");
        String exit = source.substring(start, source.indexOf("@SubscribeEvent", start));
        assertTrue(exit.indexOf("gate.claim") < exit.indexOf("client.level.disconnect()"));
        assertTrue(exit.indexOf("client.level.disconnect()") < exit.indexOf("client.clearLevel()"));
        assertTrue(exit.indexOf("client.clearLevel()") < exit.indexOf("server.isShutdown()"));
        assertTrue(exit.indexOf("server.isShutdown()") < exit.indexOf("BC_DEBUG_WORLD_EXITED"));
        assertFalse(exit.contains("catch (")); assertFalse(exit.contains("saveEverything")); assertFalse(exit.contains("runTick"));
        assertTrue(source.contains("MODE.equals(\"verify\") && VERIFY_EXIT != null"));
        assertTrue(source.contains("event.phase != TickEvent.Phase.END"));
        assertTrue(source.contains("ReflectiveEmiReadinessAdapter.isLoaded()"));
        assertTrue(source.contains("server.getWorldPath(LevelResource.ROOT)"));
        assertFalse(source.contains("getLevelName()"));
        assertTrue(source.indexOf("RecipeGraphMod.LOGGER.info(\"BC_DEBUG_WORLD_LOADED") < source.indexOf("VerifyWorldExitRequest.afterLoaded"));
        assertTrue(source.contains("client.getSingleplayerServer() != server"));
        assertEquals(1, source.split("marker.setValue", -1).length - 1); // Existing SAVE only, no VERIFY marker mutation.
        assertTrue(source.contains("if (MODE.equals(\"save\")) marker.setValue(UUID.randomUUID().toString());"));
    }
}
