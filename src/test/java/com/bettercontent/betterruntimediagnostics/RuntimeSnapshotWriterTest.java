package com.bettercontent.betterruntimediagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class RuntimeSnapshotWriterTest {
    @TempDir Path output;

    private JsonObject envelope(boolean complete) {
        JsonObject row = new JsonObject();
        row.addProperty("snapshot_id", "local-snapshot");
        row.addProperty("generated_at", "2026-01-01T00:00:00Z");
        row.addProperty("world", "local-world");
        row.addProperty("complete", complete);
        row.addProperty("evidence_state", complete ? "complete" : "incomplete");
        return row;
    }

    private Map<String, JsonObject> documents() {
        Map<String, JsonObject> rows = new LinkedHashMap<>();
        RuntimeSnapshotWriter.FILES.forEach(name -> rows.put(name, envelope(true)));
        return rows;
    }

    @Test void emittedManifestAndEveryDocumentShareSnapshotIdentity() throws Exception {
        RuntimeSnapshotWriter.write(output, documents(), envelope(true));
        JsonObject completion = read("snapshot.json");
        assertTrue(completion.get("complete").getAsBoolean());
        assertEquals("complete", completion.get("evidence_state").getAsString());
        assertEquals(java.util.List.of("recipes.json", "registries.json", "tags.json", "mods.json", "loot.json",
                        "trades.json", "worldgen.json", "dimensions.json", "lighting.json"),
                completion.getAsJsonArray("files").asList().stream().map(value -> value.getAsString()).toList());
        for (String name : RuntimeSnapshotWriter.FILES) {
            JsonObject document = read(name);
            for (String key : java.util.List.of("snapshot_id", "generated_at", "world")) {
                assertEquals(completion.get(key), document.get(key), name + "/" + key);
            }
        }
        try (var paths = Files.list(output)) { assertEquals(10, paths.count()); }
    }

    @Test void incompleteEvidenceRemainsExplicitlyIncompleteOnDisk() throws Exception {
        Map<String, JsonObject> rows = documents();
        rows.get("recipes.json").addProperty("complete", false);
        JsonObject completion = envelope(false);
        completion.addProperty("partial_count", 1);
        RuntimeSnapshotWriter.write(output, rows, completion);
        assertFalse(read("recipes.json").get("complete").getAsBoolean());
        assertFalse(read("snapshot.json").get("complete").getAsBoolean());
        assertEquals("incomplete", read("snapshot.json").get("evidence_state").getAsString());
        assertEquals(1, read("snapshot.json").get("partial_count").getAsInt());
    }

    @Test void missingOrMixedDocumentsCannotRetainAnOldCompletionMarker() throws Exception {
        RuntimeSnapshotWriter.write(output, documents(), envelope(true));
        Map<String, JsonObject> mixed = documents();
        mixed.get("tags.json").addProperty("snapshot_id", "other-snapshot");
        assertThrows(IOException.class, () -> RuntimeSnapshotWriter.write(output, mixed, envelope(true)));
        assertFalse(Files.exists(output.resolve("snapshot.json")));
        mixed.remove("tags.json");
        assertThrows(IOException.class, () -> RuntimeSnapshotWriter.write(output, mixed, envelope(true)));
    }

    @Test void writeFailureDoesNotPublishCompletion() throws Exception {
        RuntimeSnapshotWriter.write(output, documents(), envelope(true));
        Files.delete(output.resolve("tags.json"));
        Files.createDirectory(output.resolve("tags.json"));
        Files.writeString(output.resolve("tags.json/occupied"), "force replacement failure");
        assertThrows(IOException.class, () -> RuntimeSnapshotWriter.write(output, documents(), envelope(true)));
        assertFalse(Files.exists(output.resolve("snapshot.json")));
    }

    private JsonObject read(String name) throws IOException {
        return JsonParser.parseString(Files.readString(output.resolve(name))).getAsJsonObject();
    }
}
