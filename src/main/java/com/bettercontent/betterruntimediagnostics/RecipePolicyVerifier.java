package com.bettercontent.betterruntimediagnostics;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;

/** Operator-only check of committed recipe-manager bytes, not pending KubeJS event JSON. */
public final class RecipePolicyVerifier {
    private RecipePolicyVerifier() {}

    public static Path verify(MinecraftServer server) throws Exception {
        Path root = server.getServerDirectory().toPath();
        byte[] policyBytes = Files.readAllBytes(root.resolve("kubejs/config/cooking_recipes.json"));
        JsonObject policy = JsonParser.parseString(new String(policyBytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (!policy.get("schema").getAsString().equals("better_cooking:recipe_policy.v1") || !policy.get("enabled").getAsBoolean()) {
            throw new IllegalArgumentException("not an enabled recipe-only policy");
        }
        JsonArray rows = new JsonArray();
        boolean passed = true;
        var seen = new HashSet<String>();
        for (var element : policy.getAsJsonArray("definitions")) {
            JsonObject definition = element.getAsJsonObject();
            String idText = definition.get("id").getAsString();
            JsonObject row = new JsonObject(); row.addProperty("id", idText);
            try {
                if (!seen.add(idText)) throw new IllegalArgumentException("duplicate definition");
                var id = new ResourceLocation(idText);
                var raw = definition.getAsJsonObject("json");
                var serializerId = new ResourceLocation(raw.get("type").getAsString());
                RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(serializerId);
                if (serializer == null) throw new IllegalArgumentException("missing serializer " + serializerId);
                Recipe<?> expected = serializer.fromJson(id, raw);
                Recipe<?> actual = server.getRecipeManager().byKey(id).orElseThrow();
                if (actual.getSerializer() != serializer) throw new IllegalArgumentException("wrong loaded serializer");
                byte[] expectedBytes = encode(expected), actualBytes = encode(actual);
                boolean equal = Arrays.equals(expectedBytes, actualBytes);
                row.addProperty("expected_network_sha256", hash(expectedBytes));
                row.addProperty("actual_network_sha256", hash(actualBytes));
                row.addProperty("status", equal ? "passed" : "failed");
                passed &= equal;
            } catch (Exception failure) {
                passed = false; row.addProperty("status", "failed"); row.addProperty("error", failure.toString());
            }
            rows.add(row);
        }
        for (var id : policy.getAsJsonArray("remove_ids")) {
            if (server.getRecipeManager().byKey(new ResourceLocation(id.getAsString())).isPresent()) passed = false;
        }
        JsonObject result = new JsonObject();
        result.addProperty("schema", "bc.recipe_policy_verification.v1");
        result.addProperty("status", passed ? "passed" : "failed");
        result.addProperty("generated_at", Instant.now().toString());
        result.addProperty("policy_file_sha256", hash(policyBytes));
        result.addProperty("definition_count", rows.size()); result.add("definitions", rows);
        Path output = root.resolve("generated/runtime-dumps/recipe-policy-verification.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
        return output;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static byte[] encode(Recipe<?> recipe) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ((RecipeSerializer) recipe.getSerializer()).toNetwork(buffer, recipe);
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        } finally { buffer.release(); }
    }
}
