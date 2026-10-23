package com.bettercontent.betterruntimediagnostics.diagnostics;

/** Scalar projection of exporter results; command admission alone never means a complete dump. */
public final class ExportOutcome {
    private ExportOutcome() {}
    public static String dump(boolean success, boolean complete) {
        return !success ? "failed" : complete ? "complete" : "incomplete";
    }
    public static String combat(boolean success) { return success ? "completed" : "failed"; }
}
