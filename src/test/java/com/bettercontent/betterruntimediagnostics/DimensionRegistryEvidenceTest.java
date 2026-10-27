package com.bettercontent.betterruntimediagnostics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DimensionRegistryEvidenceTest {
    private JsonObject result;
    private JsonArray issues;

    private void compare(List<String> registered, int count, boolean available, String loadedJson) {
        result = new JsonObject();
        issues = new JsonArray();
        DimensionRegistryEvidence.add(result, registered, count, available,
                JsonParser.parseString(loadedJson).getAsJsonArray(), issues);
    }

    @Test void exactArbitraryIdsAreSortedWithoutAWhitelist() {
        compare(List.of("unknown:moon", "example:custom", "other:deep"), 3, true,
                "[\"other:deep\",\"unknown:moon\",\"example:custom\"]");
        assertEquals("[\"example:custom\",\"other:deep\",\"unknown:moon\"]",
                result.getAsJsonArray("registered_dimensions").toString());
        assertEquals(3, result.get("registered_dimension_count").getAsInt());
        assertTrue(flag("registered_dimension_registry_available"));
        assertTrue(flag("registered_dimension_count_matches_ids"));
        assertTrue(flag("registered_loaded_dimensions_match"));
        assertTrue(issues.isEmpty());
    }

    @Test void registeredButUnloadedDimensionIsAnExplicitError() {
        compare(List.of("example:home", "other:unloaded"), 2, true, "[\"example:home\"]");
        assertFalse(flag("registered_loaded_dimensions_match"));
        assertEquals("[\"other:unloaded\"]", result.getAsJsonArray("registered_not_loaded_dimensions").toString());
        assertEquals(1, issues.size());
        assertTrue(issues.toString().contains("other:unloaded"));
    }

    @Test void loadedButUnregisteredDimensionIsAnExplicitError() {
        compare(List.of("example:home"), 1, true, "[\"other:unexpected\",\"example:home\"]");
        assertFalse(flag("registered_loaded_dimensions_match"));
        assertEquals("[\"other:unexpected\"]", result.getAsJsonArray("loaded_not_registered_dimensions").toString());
        assertEquals(1, issues.size());
    }

    @Test void equalCountsDoNotProveEqualMembership() {
        compare(List.of("example:a"), 1, true, "[\"example:b\"]");
        assertTrue(flag("registered_dimension_count_matches_ids"));
        assertFalse(flag("registered_loaded_dimensions_match"));
        assertEquals(1, issues.size());
    }

    @Test void nativeCountMismatchFailsEvenWithEqualIdSets() {
        compare(List.of("example:home"), 2, true, "[\"example:home\"]");
        assertTrue(flag("registered_loaded_dimensions_match"));
        assertFalse(flag("registered_dimension_count_matches_ids"));
        assertEquals(2, result.get("registered_dimension_count").getAsInt());
        assertEquals(1, issues.size());
        assertTrue(issues.toString().contains("count mismatch"));
    }

    @Test void unavailableEmptyRegistryCannotMasqueradeAsAnEmptyMatch() {
        compare(List.of(), 0, false, "[]");
        assertFalse(flag("registered_dimension_registry_available"));
        assertFalse(flag("registered_dimension_count_matches_ids"));
        assertFalse(flag("registered_loaded_dimensions_match"));
        assertEquals(1, issues.size());
        assertTrue(issues.toString().contains("unavailable"));
    }

    @Test void duplicateRegisteredIdsFailCountIntegrity() {
        compare(List.of("example:home", "example:home"), 2, true, "[\"example:home\"]");
        assertFalse(flag("registered_dimension_count_matches_ids"));
        assertEquals(1, issues.size());
    }

    @Test void duplicateLoadedIdsCannotProveExactEquality() {
        compare(List.of("example:home"), 1, true, "[\"example:home\",\"example:home\"]");
        assertFalse(flag("registered_loaded_dimensions_match"));
        assertEquals(1, issues.size());
    }

    @Test void existingFieldsAndIssuesArePreserved() {
        JsonObject out = new JsonObject();
        JsonArray loaded = JsonParser.parseString("[\"example:home\"]").getAsJsonArray();
        JsonArray rocket = JsonParser.parseString("[\"example:rocket\"]").getAsJsonArray();
        JsonArray errors = new JsonArray();
        errors.add("existing rocket registry failure");
        out.add("loaded_dimensions", loaded);
        out.add("rocket_accessible_dimensions", rocket);
        DimensionRegistryEvidence.add(out, List.of("example:home"), 1, true, loaded, errors);
        assertSame(loaded, out.get("loaded_dimensions"));
        assertSame(rocket, out.get("rocket_accessible_dimensions"));
        assertEquals(1, errors.size());
        assertEquals("existing rocket registry failure", errors.get(0).getAsString());
    }

    private boolean flag(String key) { return result.get(key).getAsBoolean(); }
}
