package com.bettercontent.betterruntimediagnostics;

import com.bettercontent.runtimetestsupport.fixture.FixtureClientInput;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixtureClientInputTest {
    private static final UUID NONCE = UUID.fromString("2a1c3406-1041-4f5c-aa00-222222222222");
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("OfflinePlayer:SmokeClient1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final String RUN = "20261025T234000Z-123";
    private static Properties properties() {
        Properties p = new Properties();
        String prefix = "bc.pack_test.offline_client.";
        p.setProperty(prefix + "run", RUN);
        p.setProperty(prefix + "nonce", NONCE.toString());
        p.setProperty(prefix + "uuid", PLAYER.toString());
        p.setProperty(prefix + "player", "SmokeClient1");
        p.setProperty(prefix + "root", "/home/dev/probe/fixture/client-1");
        p.setProperty(prefix + "bridge", "/home/dev/probe/fixture/runtime-diagnostics");
        p.setProperty("bc.pack_test.diagnostics.run_id", RUN);
        p.setProperty("bc.pack_test.diagnostics.dir", "/home/dev/probe/fixture/runtime-diagnostics");
        p.setProperty("bc.pack_test.diagnostics.mode", "dist");
        for (String kind : java.util.List.of("client", "server", "runtime", "support"))
            p.setProperty("bc.pack_test.diagnostics." + kind + "_sha256", "a".repeat(64));
        return p;
    }
    @Test void requiresExplicitEmptyTokenAndExactOfflineIdentity() {
        var input = FixtureClientInput.fromProperties(properties());
        input.requireActual(input.client(), PLAYER, "SmokeClient1", "");
        assertThrows(IllegalArgumentException.class, () -> input.requireActual(input.client(), PLAYER, "SmokeClient1", "real-token"));
        assertThrows(IllegalArgumentException.class, () -> input.requireActual(input.client(), PLAYER, "SmokeClient1", "0"));
        assertThrows(IllegalArgumentException.class, () -> input.requireActual(input.client(), PLAYER, "SmokeClient1", null));
        assertThrows(IllegalArgumentException.class, () -> input.requireActual(input.client(), UUID.randomUUID(), "SmokeClient1", ""));
        assertThrows(IllegalArgumentException.class, () -> input.requireActual(input.client(), PLAYER, "DifferentUser", ""));
        assertThrows(IllegalArgumentException.class, () -> input.requireActual(input.client().resolveSibling("client-2"), PLAYER, "SmokeClient1", ""));
    }
    @Test void missingOrCrossRunCandidateBindingsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> FixtureClientInput.fromProperties(new Properties()));
        for (String field : properties().stringPropertyNames()) {
            Properties p = properties(); p.remove(field);
            assertThrows(IllegalArgumentException.class, () -> FixtureClientInput.fromProperties(p), field);
        }
        for (String mode : java.util.List.of("dev", "candidate", "production", "", "debug-with-waivers")) {
            Properties p = properties(); p.setProperty("bc.pack_test.diagnostics.mode", mode);
            assertThrows(IllegalArgumentException.class, () -> FixtureClientInput.fromProperties(p), mode);
        }
        Properties debug = properties(); debug.setProperty("bc.pack_test.diagnostics.mode", "debug");
        assertDoesNotThrow(() -> FixtureClientInput.fromProperties(debug));
        Properties stale = properties(); stale.setProperty("bc.pack_test.diagnostics.run_id", "20261024T234000Z-123");
        assertThrows(IllegalArgumentException.class, () -> FixtureClientInput.fromProperties(stale));
    }
    @Test void nonfixtureRootsAndMalformedHashBindingsFailClosed() {
        for (String root : java.util.List.of("/", "relative/fixture/client-1", "/home/dev/probe/fixture/client-1/..", "/home/dev/probe/fixture/client-other", "/home/dev/probe/normal/client-1", "/home/dev/probe/fixture/server")) {
            Properties p = properties(); p.setProperty("bc.pack_test.offline_client.root", root);
            assertThrows(IllegalArgumentException.class, () -> FixtureClientInput.fromProperties(p), root);
        }
        for (String kind : java.util.List.of("client", "server", "runtime", "support")) {
            Properties p = properties(); p.setProperty("bc.pack_test.diagnostics." + kind + "_sha256", "not-a-candidate-hash");
            assertThrows(IllegalArgumentException.class, () -> FixtureClientInput.fromProperties(p), kind);
        }
        Properties p = properties(); p.setProperty("bc.pack_test.offline_client.bridge", "/home/dev/other/fixture/runtime-diagnostics");
        assertThrows(IllegalArgumentException.class, () -> FixtureClientInput.fromProperties(p));
    }
    @Test void onboardingIsExactRunNonceAndPlayerBound() {
        var input = FixtureClientInput.fromProperties(properties());
        String request = RUN + "\n" + NONCE + "\n" + PLAYER + "\n";
        input.requireOnboardingRequest(request);
        for (String invalid : java.util.List.of(request.replace(RUN, "stale"), request.replace(NONCE.toString(), UUID.randomUUID().toString()),
                request.replace(PLAYER.toString(), UUID.randomUUID().toString()), request + "extra\n", request.strip()))
            assertThrows(IllegalArgumentException.class, () -> input.requireOnboardingRequest(invalid));
    }
}
