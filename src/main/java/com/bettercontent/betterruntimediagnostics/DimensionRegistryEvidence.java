package com.bettercontent.betterruntimediagnostics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.TreeSet;

/** Pure comparison of observed IDs; never supplies or guesses dimension membership. */
final class DimensionRegistryEvidence {
    private DimensionRegistryEvidence() {}

    static void add(JsonObject out, Collection<String> registeredIds, int registrySize,
                    boolean registryAvailable, JsonArray loaded, JsonArray issues) {
        TreeSet<String> registered = new TreeSet<>(registeredIds);
        TreeSet<String> loadedIds = new TreeSet<>();
        loaded.forEach(id -> loadedIds.add(id.getAsString()));
        boolean countMatches = registryAvailable && registrySize == registeredIds.size()
                && registrySize == registered.size();
        boolean setsMatch = registryAvailable && registered.equals(loadedIds)
                && loaded.size() == loadedIds.size();
        TreeSet<String> notLoaded = new TreeSet<>(registered);
        notLoaded.removeAll(loadedIds);
        TreeSet<String> notRegistered = new TreeSet<>(loadedIds);
        notRegistered.removeAll(registered);

        if (!registryAvailable) {
            issues.add("live LevelStem registry unavailable; registered dimensions and equality are unproven");
        } else {
            if (!countMatches) {
                issues.add("LevelStem registry count mismatch: registry=" + registrySize
                        + " observed_keys=" + registeredIds.size() + " distinct_ids=" + registered.size());
            }
            if (!setsMatch) {
                issues.add("LevelStem registered/loaded dimension IDs differ: registered_not_loaded="
                        + notLoaded + " loaded_not_registered=" + notRegistered
                        + " loaded_count=" + loaded.size() + " distinct_loaded_ids=" + loadedIds.size());
            }
        }
        out.add("registered_dimensions", array(registered));
        out.addProperty("registered_dimension_count", registrySize);
        out.addProperty("registered_dimension_registry_available", registryAvailable);
        out.addProperty("registered_dimension_count_matches_ids", countMatches);
        out.addProperty("registered_loaded_dimensions_match", setsMatch);
        out.add("registered_not_loaded_dimensions", array(notLoaded));
        out.add("loaded_not_registered_dimensions", array(notRegistered));
    }

    private static JsonArray array(Collection<String> ids) {
        JsonArray out = new JsonArray();
        ids.forEach(out::add);
        return out;
    }
}
