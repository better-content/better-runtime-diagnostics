package com.bettercontent.betterruntimediagnostics.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class ExportOutcomeTest {
    @Test void dumpCompletenessCannotHideExporterFailure() {
        assertEquals("failed", ExportOutcome.dump(false, true));
        assertEquals("failed", ExportOutcome.dump(false, false));
        assertEquals("incomplete", ExportOutcome.dump(true, false));
        assertEquals("complete", ExportOutcome.dump(true, true));
    }
    @Test void commandReturnDoesNotPretendFailedCombatSamplingSucceeded() {
        assertEquals("failed", ExportOutcome.combat(false));
        assertEquals("completed", ExportOutcome.combat(true));
    }
}
