package com.github.xandergos.terraindiffusionmc.world;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which numbers in a carver's configuration are altitudes, so that a cave mod's carver can be
 * moved into our stretched world without this mod knowing anything about that mod.
 *
 * <h2>The problem this solves</h2>
 * <p>{@link ScaledCarvers} lifts a carver's altitudes so that content authored for a
 * vanilla-height world reaches our terrain. It can do that unaided only for the configuration
 * types the game itself defines, because those are the only ones whose fields it can name. A cave
 * mod that ships its own configuration class -- YUNG's Better Caves keeps its cave and cavern
 * bands in {@code bottom_y}/{@code top_y} pairs, for one -- is otherwise passed through untouched,
 * and its caves then stop wherever the mod's own author put the ceiling: around y=80 for Better
 * Caves, which in a world scaled 6x leaves every mountain above that solid.</p>
 *
 * <h2>The answer: let the number be declared, not guessed</h2>
 * <p>A carver type may declare which JSON keys of its configuration hold absolute altitudes. Any
 * such key found anywhere in that carver's serialized configuration is lifted by
 * {@link ScaledAltitude#worldY}, exactly as a vanilla carver's {@code y} range is. Nothing else in
 * the configuration is touched, and a carver type with no declaration is passed through as before
 * -- so guessing never happens, and a mod that wants its own vertical bounds respected simply says
 * nothing (or lists itself under {@code excluded}).</p>
 *
 * <p>Declarations ship for the cave mods this mod bundles or is commonly run with, and a pack or
 * mod author can add to them, override them, or opt a carver out entirely by dropping
 * {@code config/terrain-diffusion-mc/carver_altitudes.json}:</p>
 *
 * <pre>{@code
 * {
 *   "altitudeKeys": {
 *     "somemod:crystal_cavern": ["min_height", "max_height"]
 *   },
 *   "excluded": [
 *     "othermod:vertical_shaft"
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code altitudeKeys} is keyed by carver <i>type</i> id -- the {@code "type"} of a configured
 * carver JSON -- and merges with the built-in table, an empty list disabling a built-in entry.
 * {@code excluded} is a list of <i>configured</i> carver ids that are never rewritten at all, not
 * even when their configuration is a vanilla one; that is the escape hatch for a mod or pack that
 * has already tuned its carver for a tall world.</p>
 */
public final class CarverAltitudeRules {

    private static final String FILE_NAME = "carver_altitudes.json";
    private static final Gson GSON = new Gson();

    /**
     * Carver types whose altitude keys we know first-hand.
     *
     * <p>Better Caves is the one this mod bundles. Its configuration nests a list of cave bands
     * and a list of cavern bands, each band carrying the {@code bottom_y}/{@code top_y} pair that
     * decides how far up its noise reaches; both names are unambiguous within its own config, and
     * a key of either name occurs nowhere else in it.</p>
     */
    private static final Map<String, Set<String>> BUILT_IN = Map.of(
            "bettercaves:better_cave", Set.of("bottom_y", "top_y")
    );

    private static volatile Rules rules;

    private CarverAltitudeRules() {}

    /** JSON keys of {@code carverTypeId}'s configuration that hold absolute altitudes. */
    public static Set<String> altitudeKeys(String carverTypeId) {
        if (carverTypeId == null) return Set.of();
        Set<String> keys = load().altitudeKeys.get(carverTypeId);
        return keys == null ? Set.of() : keys;
    }

    /** Whether {@code configuredCarverId} opted out of being rewritten at all. */
    public static boolean isExcluded(String configuredCarverId) {
        return configuredCarverId != null && load().excluded.contains(configuredCarverId);
    }

    /** Re-reads the override file. For tests and for a reload command. */
    public static void reload() {
        rules = null;
    }

    private static Rules load() {
        Rules current = rules;
        if (current != null) return current;
        return loadSynchronized();
    }

    private static synchronized Rules loadSynchronized() {
        if (rules != null) return rules;
        Map<String, Set<String>> keys = new HashMap<>(BUILT_IN);
        Set<String> excluded = new HashSet<>();
        try {
            Path file = PlatformPaths.configDir().resolve("terrain-diffusion-mc").resolve(FILE_NAME);
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    applyOverrides(GSON.fromJson(reader, JsonObject.class), keys, excluded);
                }
            }
        } catch (Exception e) {
            // A malformed override must not take worldgen down: fall back to the built-in table,
            // which is what an install without the file already uses.
            System.err.println("Failed to load " + FILE_NAME + ", using built-in carver altitude rules: " + e);
        }
        Rules loaded = new Rules(Map.copyOf(keys), Set.copyOf(excluded));
        rules = loaded;
        return loaded;
    }

    private static void applyOverrides(JsonObject root, Map<String, Set<String>> keys, Set<String> excluded) {
        if (root == null) return;
        JsonElement altitudeKeys = root.get("altitudeKeys");
        if (altitudeKeys != null && altitudeKeys.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : altitudeKeys.getAsJsonObject().entrySet()) {
                Set<String> declared = readStrings(entry.getValue());
                if (declared.isEmpty()) {
                    keys.remove(entry.getKey());
                } else {
                    keys.put(entry.getKey(), declared);
                }
            }
        }
        excluded.addAll(readStrings(root.get("excluded")));
    }

    private static Set<String> readStrings(JsonElement element) {
        if (element == null || !element.isJsonArray()) return Set.of();
        JsonArray array = element.getAsJsonArray();
        Set<String> values = new LinkedHashSet<>(array.size());
        for (JsonElement item : array) {
            if (item != null && item.isJsonPrimitive()) values.add(item.getAsString());
        }
        return values;
    }

    private record Rules(Map<String, Set<String>> altitudeKeys, Set<String> excluded) {}
}
