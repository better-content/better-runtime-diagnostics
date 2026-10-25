package com.bettercontent.runtimetestsupport.fixture;

import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/** Explicit disposable offline-launch binding, not an authentication fallback for a real user. */
public record FixtureClientInput(String run, UUID nonce, String player, UUID playerId, Path client, Path bridge) {
    public FixtureClientInput {
        if (run == null || !run.matches("[0-9]{8}T[0-9]{6}Z-[0-9]+") || nonce == null || playerId == null
                || player == null || !player.matches("[A-Za-z0-9_]{1,16}") || client == null || bridge == null
                || !playerId.equals(UUID.nameUUIDFromBytes(("OfflinePlayer:" + player).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                || client.getFileName() == null || bridge.getFileName() == null || client.getParent() == null || bridge.getParent() == null
                || !client.isAbsolute() || !bridge.isAbsolute() || !client.equals(client.normalize())
                || !bridge.equals(bridge.normalize()) || !client.getFileName().toString().matches("client(?:-[0-9]+)?")
                || !"runtime-diagnostics".equals(bridge.getFileName().toString())
                || !client.getParent().equals(bridge.getParent())
                || !"fixture".equals(client.getParent().getFileName().toString()))
            throw new IllegalArgumentException("invalid disposable offline client binding");
    }

    public static FixtureClientInput fromProperties(Properties properties) {
        String prefix = "bc.pack_test.offline_client.";
        String run = properties.getProperty(prefix + "run", "");
        if (!run.equals(properties.getProperty("bc.pack_test.diagnostics.run_id"))
                || !properties.getProperty(prefix + "bridge", "").equals(properties.getProperty("bc.pack_test.diagnostics.dir"))
                || !java.util.Set.of("dist", "debug").contains(properties.getProperty("bc.pack_test.diagnostics.mode", "")))
            throw new IllegalArgumentException("offline client does not match its diagnostic fixture");
        for (String kind : java.util.List.of("client", "server", "runtime", "support"))
            if (!properties.getProperty("bc.pack_test.diagnostics." + kind + "_sha256", "").matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("offline client has no candidate/support hash binding");
        return new FixtureClientInput(run, UUID.fromString(properties.getProperty(prefix + "nonce", "")),
            properties.getProperty(prefix + "player", ""), UUID.fromString(properties.getProperty(prefix + "uuid", "")),
            Path.of(properties.getProperty(prefix + "root", "")), Path.of(properties.getProperty(prefix + "bridge", "")));
    }

    public void requireActual(Path directory, UUID actualId, String actualName, String accessToken) {
        // portablemc 4.4.1 OfflineAuthSession supplies an empty token. Never accept a real credential.
        if (!client.equals(directory.toAbsolutePath().normalize()) || !playerId.equals(actualId)
                || !player.equals(actualName) || !"".equals(accessToken))
            throw new IllegalArgumentException("offline launch differs from its bound empty-token client identity");
    }

    public void requireOnboardingRequest(String text) {
        if (!(run + "\n" + nonce + "\n" + playerId + "\n").equals(text))
            throw new IllegalArgumentException("onboarding request differs from its bound client/run/nonce");
    }
}
