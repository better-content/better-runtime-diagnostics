package com.bettercontent.betterruntimediagnostics.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Additional pure regression coverage; a sink receipt is not a native multiplayer observation. */
final class PlaytestLogQualificationTest {
    private static final class Sink implements PlaytestLog.Sink {
        final List<String> records = new ArrayList<>();
        boolean summary = true;
        @Override public boolean enabled(String category, String level) {
            return !category.equals("summary") || summary;
        }
        @Override public void write(String category, String level, String message) {
            records.add(level + " " + message);
        }
    }

    @Test void disabledSummaryStillExpiresAggregationWithoutPublishingAFalseReceipt() {
        Sink sink = new Sink(); sink.summary = false;
        AtomicLong clock = new AtomicLong();
        PlaytestLog log = new PlaytestLog("better_runtime_diagnostics", sink, clock::get, new Properties());
        log.debug("export", "dump.started", PlaytestLog.Context.server("minecraft:overworld", 1),
                "started", "operator_command", () -> "");
        assertEquals(1, log.aggregationKeys());
        clock.set(30_000_000_000L);
        log.summary("server");
        assertEquals(0, log.aggregationKeys());
        assertTrue(sink.records.stream().noneMatch(row -> row.contains("logging.summary")));
        sink.summary = true;
        log.summary("server");
        assertTrue(sink.records.stream().noneMatch(row -> row.contains("logging.summary")),
                "re-enabling summaries must not resurrect previously discarded observations");
    }

    @Test void statusIsSingleAnnouncementAndCarriesFailClosedSourceIdentity() {
        Sink sink = new Sink();
        PlaytestLog log = new PlaytestLog("better_runtime_diagnostics", sink, () -> 0L, new Properties());
        assertEquals("UNKNOWN", log.sourceIdentity((java.io.InputStream) null));
        log.status("common", "0.1.0", log.sourceIdentity((java.io.InputStream) null),
                () -> PlaytestLog.fields("enabled", true));
        log.status("server", "unexpected", "forged", () -> { throw new AssertionError("duplicate status evaluated"); });
        assertEquals(1, sink.records.size());
        String row = sink.records.get(0);
        assertTrue(row.startsWith("INFO BC_PLAYTEST schema=bc.playtest.v1 "));
        assertTrue(row.contains("event=logging.status"));
        assertTrue(row.contains("source=UNKNOWN"));
        assertTrue(row.contains("detail_per_minute=120"));
        assertTrue(row.contains("max_keys=256"));
        assertTrue(row.contains("trace_focus=false"));
    }

    @Test void unknownEventDataCannotInjectAnotherLogRecord() {
        Sink sink = new Sink();
        PlaytestLog log = new PlaytestLog("better_runtime_diagnostics", sink, () -> 0L, new Properties());
        log.debug("export\nBC_PLAYTEST", "dump\rstarted", PlaytestLog.Context.server("minecraft:overworld", 42),
                "observed", "operator_command", () -> PlaytestLog.fields("payload", "first\nBC_PLAYTEST schema=forged\rsecond"));
        assertEquals(1, sink.records.size());
        String row = sink.records.get(0);
        assertFalse(row.contains("\n"));
        assertFalse(row.contains("\r"));
        assertTrue(row.contains("tick=42"));
        assertTrue(row.contains("dimension=minecraft:overworld"));
        assertTrue(row.contains("\\nBC_PLAYTEST"));
        assertTrue(row.length() <= PlaytestLog.MAX_RECORD + "DEBUG ".length());
    }
}
