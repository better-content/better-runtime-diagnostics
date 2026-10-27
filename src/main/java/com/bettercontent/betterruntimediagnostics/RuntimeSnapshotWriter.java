package com.bettercontent.betterruntimediagnostics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** File publication boundary shared by the live exporter and local output contracts. */
final class RuntimeSnapshotWriter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    static final List<String> FILES = List.of("recipes.json", "registries.json", "tags.json", "mods.json",
            "loot.json", "trades.json", "worldgen.json", "dimensions.json", "lighting.json");

    private RuntimeSnapshotWriter() {}

    static void write(Path output, Map<String, JsonObject> documents, JsonObject completion) throws IOException {
        Files.createDirectories(output);
        // A failed replacement must not leave the previous completion marker describing new files.
        Files.deleteIfExists(output.resolve("snapshot.json"));
        if (!documents.keySet().equals(new java.util.HashSet<>(FILES))) {
            throw new IOException("Snapshot document set does not match required files");
        }
        for (String name : FILES) {
            JsonObject document = documents.get(name);
            for (String key : List.of("snapshot_id", "generated_at", "world")) {
                if (!completion.has(key) || !completion.get(key).equals(document.get(key))) {
                    throw new IOException("Snapshot identity mismatch: " + name + "/" + key);
                }
            }
        }
        JsonArray manifest = new JsonArray();
        for (String name : FILES) {
            writeAtomic(output.resolve(name), documents.get(name));
            manifest.add(name);
        }
        completion.add("files", manifest);
        writeAtomic(output.resolve("snapshot.json"), completion);
    }

    private static void writeAtomic(Path target, JsonObject value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
