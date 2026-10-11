package com.bettercontent.betterruntimediagnostics.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Preconditions only, not evidence that native world creation or startup succeeded. */
public record FreshWorldInput(String runId, UUID nonce, String name, UUID playerId, Path root) {
    public FreshWorldInput {
        if (runId == null || runId.isBlank() || runId.length() > 128 || runId.chars().anyMatch(Character::isWhitespace)
                || nonce == null || playerId == null || !name.equals("Fresh-" + nonce)
                || !root.isAbsolute() || !root.normalize().equals(root))
            throw new IllegalArgumentException("invalid fresh-world launch identity");
    }
    public void requireEmptyNativeInput(Path gameDirectory, Path fixtureRoot, String tier, boolean serverAlreadyLoaded) {
        if (!tier.equals("debug") || !root.equals(gameDirectory.toAbsolutePath().normalize().resolve("saves").resolve(name))
                || !root.startsWith(fixtureRoot.toAbsolutePath().normalize()) || serverAlreadyLoaded)
            throw new IllegalStateException("fresh-world launch is not bound to an empty standalone Debug fixture");
        for (Path parent = root; parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IllegalStateException("fresh-world input has a symlink ancestor");
        }
        if (Files.exists(root)) throw new IllegalStateException("fresh native creation received a preexisting world");
    }
    public boolean matchesExit(String request) {
        return (runId + "\n" + nonce + "\n" + root + "\n").equals(request);
    }
}
