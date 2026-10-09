package com.bettercontent.betterruntimediagnostics.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticSupportBoundaryTest {
    @Test void observersRemainNonCancellingAndStorageFree() throws Exception {
        Path root=Path.of("src/testSupport/java/com/bettercontent/runtimetestsupport");
        try(var paths=Files.walk(root)) {
            for(Path p:paths.filter(Files::isRegularFile).toList()) {
                String s=Files.readString(p);
                for(String token:List.of("bc.native.inventory", "bc.journal.contract", "NativeContract", "NativeCheckpoint", "NativeClientPacketMixin", "setStackInSlot", "setInvulnerable", "callback.cancel", "callback.setReturnValue", "cancellable = true", "entity.setYRot", "entity.setXRot"))assertFalse(s.contains(token),p+": "+token);
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
