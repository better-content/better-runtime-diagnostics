package com.bettercontent.betterruntimediagnostics.core;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FreshWorldInputTest {
    @Test void refusesPreseededEscapedSymlinkAndNonDebugInputsBeforeNativeCreation() throws Exception {
        Path parent = Path.of("build/test-fixtures").toAbsolutePath();
        Files.createDirectories(parent);
        Path fixture = Files.createTempDirectory(parent, "fresh-contract-");
        try {
            Path game = fixture.resolve("client");
            Files.createDirectories(game.resolve("saves"));
            UUID nonce = UUID.randomUUID();
            Path world = game.resolve("saves/Fresh-" + nonce);
            var input = new FreshWorldInput("run-1", nonce, "Fresh-" + nonce, UUID.randomUUID(), world);
            assertDoesNotThrow(() -> input.requireEmptyNativeInput(game, fixture, "debug", false));
            assertThrows(IllegalStateException.class, () -> input.requireEmptyNativeInput(game, fixture, "dist", false));
            assertThrows(IllegalStateException.class, () -> input.requireEmptyNativeInput(game, fixture, "debug", true));
            assertThrows(IllegalStateException.class, () -> input.requireEmptyNativeInput(fixture.resolve("different-client"), fixture, "debug", false));
            Files.createDirectory(world);
            Files.writeString(world.resolve("level.dat"), "authored negative-control bytes, not a valid native save");
            assertThrows(IllegalStateException.class, () -> input.requireEmptyNativeInput(game, fixture, "debug", false));
            Files.delete(world.resolve("level.dat")); Files.delete(world);
            Path real = fixture.resolve("real-saves"); Files.createDirectory(real);
            Files.delete(game.resolve("saves")); Files.createSymbolicLink(game.resolve("saves"), real);
            assertThrows(IllegalStateException.class, () -> input.requireEmptyNativeInput(game, fixture, "debug", false));
            Files.delete(game.resolve("saves"));
        } finally {
            try (var paths = Files.walk(fixture)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
    @Test void exitRequestMustMatchTheExactRunNonceAndWorld() {
        UUID nonce = UUID.randomUUID();
        Path root = Path.of("build/test-fixtures/client/saves/Fresh-" + nonce).toAbsolutePath().normalize();
        var input = new FreshWorldInput("run-1", nonce, "Fresh-" + nonce, UUID.randomUUID(), root);
        assertTrue(input.matchesExit("run-1\n" + nonce + "\n" + root + "\n"));
        assertFalse(input.matchesExit("run-2\n" + nonce + "\n" + root + "\n"));
        assertFalse(input.matchesExit("run-1\n" + UUID.randomUUID() + "\n" + root + "\n"));
        assertFalse(input.matchesExit("run-1\n" + nonce + "\n" + root.resolve("different") + "\n"));
        assertThrows(IllegalArgumentException.class, () -> new FreshWorldInput("run-1", nonce, "other", UUID.randomUUID(), root));
    }
}
