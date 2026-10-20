package com.bettercontent.runtimetestsupport.whale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/** Pure Java, immutable receipt contract. No Minecraft classes or gameplay operations. */
public final class WhaleObservation {
    public static final String TYPE = "untamedwilds:baleen_whale";
    public static final String DIMENSION = "minecraft:overworld";
    public static final long WINDOW_NANOS = 30_000_000_000L;
    private WhaleObservation() {}

    public record Binding(String run, String nonce, String playerUuid, String entityUuid,
                          String clientHash, String serverHash, String runtimeHash, String supportHash,
                          Path fixture) {
        public Binding {
            if (run == null || !run.matches("[0-9]{8}T[0-9]{6}Z-[0-9]+")
                    || !uuid(nonce) || !uuid(playerUuid) || !uuid(entityUuid)
                    || !hash(clientHash) || !hash(serverHash) || !hash(runtimeHash) || !hash(supportHash)
                    || fixture == null || !fixture.isAbsolute() || !fixture.equals(fixture.normalize()))
                throw new IllegalArgumentException("Invalid whale binding");
        }
        public static Binding read(Properties properties) throws IOException {
            if (!"true".equals(properties.getProperty("bc.pack_test.whale.enabled"))
                    || !"debug".equals(properties.getProperty("bc.pack_test.diagnostics.mode")))
                throw new IllegalArgumentException("Whale observer not explicitly enabled for Debug");
            String prefix = "bc.pack_test.diagnostics.";
            Binding result = new Binding(properties.getProperty(prefix + "run_id"),
                properties.getProperty("bc.pack_test.whale.nonce"),
                properties.getProperty("bc.pack_test.whale.player_uuid"),
                properties.getProperty("bc.pack_test.whale.entity_uuid"),
                properties.getProperty(prefix + "client_sha256"), properties.getProperty(prefix + "server_sha256"),
                properties.getProperty(prefix + "runtime_sha256"), properties.getProperty(prefix + "support_sha256"),
                Path.of(properties.getProperty(prefix + "dir", "")));
            result.validateFixture();
            return result;
        }
        public void validateFixture() throws IOException {
            for (Path current = fixture; current != null; current = current.getParent())
                if (Files.isSymbolicLink(current)) throw new IOException("Symlink fixture ancestry");
            if (!Files.isDirectory(fixture, LinkOption.NOFOLLOW_LINKS) || !fixture.toRealPath().equals(fixture)
                    || !"runtime-diagnostics".equals(fixture.getFileName().toString())
                    || fixture.getParent() == null || !"fixture".equals(fixture.getParent().getFileName().toString())
                    || fixture.getParent().getParent() == null || fixture.getParent().getParent().getParent() == null
                    || !run.equals(fixture.getParent().getParent().getParent().getFileName().toString()))
                throw new IOException("Missing or substituted fixture identity");
        }
        public void validateClientDirectory(Path client) throws IOException {
            validateFixture();
            if (client == null || !client.isAbsolute() || !client.equals(client.normalize())
                    || !fixture.getParent().equals(client.getParent())
                    || !client.getFileName().toString().matches("client-[0-9]+"))
                throw new IOException("Wrong native client directory");
            for (Path current = client; current != null; current = current.getParent())
                if (Files.isSymbolicLink(current)) throw new IOException("Symlink client ancestry");
            if (!Files.isDirectory(client, LinkOption.NOFOLLOW_LINKS) || !client.toRealPath().equals(client))
                throw new IOException("Missing native client directory");
        }
        public String receipt(long join, long first, long last) {
            return "{\"schema\":\"bc.whale_client_observation.v1\",\"run\":\"" + run
                + "\",\"nonce\":\"" + nonce + "\",\"player_uuid\":\"" + playerUuid
                + "\",\"entity_uuid\":\"" + entityUuid + "\",\"entity_type\":\"" + TYPE
                + "\",\"dimension\":\"" + DIMENSION + "\",\"join_tick\":" + join
                + ",\"first_tick\":" + first + ",\"last_tick\":" + last
                + ",\"client_sha256\":\"" + clientHash + "\",\"server_sha256\":\"" + serverHash
                + "\",\"runtime_sha256\":\"" + runtimeHash + "\",\"support_sha256\":\"" + supportHash + "\"}";
        }
    }
    public static boolean uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return false;
        return UUID.fromString(value).toString().equals(value);
    }
    public static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    /** Tokens are opaque native object identities, compared by reference, never by equals. */
    public record Observation(Object level, Object entity, String playerUuid, String entityUuid,
                              String type, String dimension, boolean connected, boolean nativeClientLevel) {
        boolean matches(Binding binding) {
            return level != null && entity != null && connected && nativeClientLevel
                && binding.playerUuid.equals(playerUuid) && binding.entityUuid.equals(entityUuid)
                && TYPE.equals(type) && DIMENSION.equals(dimension);
        }
    }
    public record State(Binding binding, Observation joined, long joinTick, long firstTick,
                        long startedNanos, boolean closed, String receipt) {
        public static State idle(Binding binding) { return new State(binding, null, -1, -1, 0, false, null); }
        public State disconnect() { return new State(binding, null, -1, -1, 0, true, null); }
        public State join(Observation observation, long tick, long now) {
            if (closed || joined != null || tick < 0 || !observation.matches(binding)) return this;
            return new State(binding, observation, tick, -1, now, false, null);
        }
        public State tick(Observation observation, long tick, long now) {
            if (closed || joined == null) return this;
            long elapsed = now - startedNanos;
            if (elapsed < 0 || elapsed >= WINDOW_NANOS) return disconnect();
            if (!observation.connected || !binding.playerUuid.equals(observation.playerUuid)
                    || observation.level != joined.level) return disconnect();
            if (!observation.matches(binding) || observation.entity != joined.entity) return disconnect();
            if (tick <= joinTick || tick <= firstTick) return this;
            if (firstTick < 0) return new State(binding, joined, joinTick, tick, startedNanos, false, null);
            return new State(binding, null, joinTick, firstTick, startedNanos, true,
                binding.receipt(joinTick, firstTick, tick));
        }
    }
}
