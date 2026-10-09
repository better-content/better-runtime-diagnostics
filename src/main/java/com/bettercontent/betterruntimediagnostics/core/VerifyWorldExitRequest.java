package com.bettercontent.betterruntimediagnostics.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Pure fixture lifecycle signal validation. Never accesses native saves, registries or game state. */
public final class VerifyWorldExitRequest {
    public static final String SCHEMA = "bc.pack_test.verify_exit.v1";
    public static final int MAX_BYTES = 2048;
    private static final String PREFIX = "bc.pack_test.verify_exit.";
    private VerifyWorldExitRequest() {}

    public record Binding(UUID nonce, String runId, String playerName, UUID playerId,
                          Path fixtureRoot, Path worldRoot, Path requestDir) {
        public Path requestFile() { return requestDir.resolve("verify-exit-" + nonce + ".request"); }
        public Map<String, String> fields() {
            var fields = new LinkedHashMap<String, String>();
            fields.put("schema", SCHEMA); fields.put("mode", "verify"); fields.put("nonce", nonce.toString());
            fields.put("run_id", runId); fields.put("player_name", playerName); fields.put("player_uuid", playerId.toString());
            fields.put("fixture_root", fixtureRoot.toString()); fields.put("world_root", worldRoot.toString());
            fields.put("request_dir", requestDir.toString());
            return Map.copyOf(fields);
        }
    }

    /** Properties absent or not the exact owned lifecycle fixture leave normal VERIFY inert. */
    public static Optional<Binding> configured(Function<String, String> property) {
        try {
            if (!"verify".equals(property.apply("bc.pack_test.world"))) return Optional.empty();
            String nonce = property.apply(PREFIX + "nonce");
            if (nonce == null || nonce.isBlank()) return Optional.empty();
            String run = property.apply(PREFIX + "run_id"), player = property.apply(PREFIX + "player");
            if (run == null || !run.matches("[0-9]{8}T[0-9]{6}Z-[0-9]+")
                    || player == null || !player.matches("[A-Za-z0-9_]{1,16}")) return Optional.empty();
            var binding = new Binding(canonicalUuid(nonce), run, player, canonicalUuid(property.apply(PREFIX + "player_uuid")),
                Path.of(property.apply(PREFIX + "fixture_root")), Path.of(property.apply(PREFIX + "world_root")),
                Path.of(property.apply(PREFIX + "request_dir")));
            if (!safeBinding(binding)) return Optional.empty();
            return Optional.of(binding);
        } catch (IllegalArgumentException | NullPointerException | SecurityException failure) { return Optional.empty(); }
    }

    private static UUID canonicalUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) throw new IllegalArgumentException("Noncanonical fixture UUID");
        return uuid;
    }

    private static boolean canonicalDirectory(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().equals(path)) return false;
        for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent())
            if (Files.isSymbolicLink(ancestor)) return false;
        return true;
    }

    public static boolean safeBinding(Binding binding) {
        try {
            Path fixture = binding.fixtureRoot(), world = binding.worldRoot(), dir = binding.requestDir();
            return canonicalDirectory(fixture) && canonicalDirectory(world) && canonicalDirectory(dir)
                && "fixture".equals(fixture.getFileName().toString())
                && fixture.getParent() != null && fixture.getParent().getParent() != null
                && binding.runId().equals(fixture.getParent().getParent().getFileName().toString())
                && dir.equals(fixture.resolve("world-lifecycle")) && world.startsWith(fixture)
                && "DebugWorld".equals(world.getFileName().toString())
                && world.getParent() != null && "saves".equals(world.getParent().getFileName().toString());
        } catch (IOException | IllegalArgumentException | NullPointerException | SecurityException failure) { return false; }
    }

    public static boolean validPayload(Binding binding, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) return false;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var actual = new LinkedHashMap<String, String>();
            for (String line : text.split("\n", -1)) {
                if (line.isEmpty()) continue;
                int separator = line.indexOf('=');
                if (separator <= 0 || actual.putIfAbsent(line.substring(0, separator), line.substring(separator + 1)) != null) return false;
            }
            return actual.equals(binding.fields());
        } catch (CharacterCodingException | IllegalArgumentException failure) { return false; }
    }

    public static boolean requested(Binding binding) {
        try {
            if (!safeBinding(binding)) return false;
            Path file = binding.requestFile();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                    || Files.size(file) > MAX_BYTES) return false;
            try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                return validPayload(binding, input.readNBytes(MAX_BYTES + 1));
            }
        } catch (IOException | IllegalArgumentException | SecurityException failure) { return false; }
    }

    /** Opaque native identities compared by REFERENCE, not replacement UUID equivalence. */
    public record LoadedIdentity(Object server, Object level, Object player, Path worldRoot, UUID playerId, String playerName) {
        public LoadedIdentity {
            Objects.requireNonNull(server); Objects.requireNonNull(level); Objects.requireNonNull(player);
            Objects.requireNonNull(worldRoot); Objects.requireNonNull(playerId); Objects.requireNonNull(playerName);
        }
        public boolean same(LoadedIdentity current) {
            return current != null && server == current.server && level == current.level && player == current.player
                && worldRoot.equals(current.worldRoot) && playerId.equals(current.playerId) && playerName.equals(current.playerName);
        }
    }

    public static final class Gate {
        private final Binding binding;
        private final LoadedIdentity loaded;
        private boolean started;
        private Gate(Binding binding, LoadedIdentity loaded) { this.binding = binding; this.loaded = loaded; }
        public Binding binding() { return binding; }
        public synchronized boolean started() { return started; }
        public synchronized boolean claim(LoadedIdentity current, boolean emiReady) {
            if (started || !emiReady || !loaded.same(current) || !requested(binding)) return false;
            started = true; // BEFORE native clearLevel, whose progress screen may re-enter ClientTick.
            return true;
        }
    }

    /** Called only after the genuine native server-thread VERIFY loaded marker has been logged. */
    public static Optional<Gate> afterLoaded(Binding binding, LoadedIdentity loaded) {
        if (!safeBinding(binding) || !binding.worldRoot().equals(loaded.worldRoot())
                || !binding.playerId().equals(loaded.playerId()) || !binding.playerName().equals(loaded.playerName())) return Optional.empty();
        return Optional.of(new Gate(binding, loaded));
    }
}
