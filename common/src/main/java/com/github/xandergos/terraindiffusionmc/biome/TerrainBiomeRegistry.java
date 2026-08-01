package com.github.xandergos.terraindiffusionmc.biome;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry of biome settlements. Loads from a JSON data file and
 * exposes the same API surface as the legacy {@code TerrainBiomeCatalog},
 * plus programmatic registration for mod integration.
 *
 * <p>The JSON file is loaded from the classpath resource
 * {@code /biome_catalog.json}. At runtime, mod authors can add or override
 * biomes via {@link #register(TerrainBiomeSettlement)} before calling
 * {@link #rebuild()}.</p>
 */
public final class TerrainBiomeRegistry {
    private static final String RESOURCE_PATH = "/biome_catalog.json";
    private static final String CONFIG_FILE_NAME = "biome_catalog.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<TerrainBiomeSettlement>>() {}.getType();

    private static final TerrainBiomeRegistry INSTANCE = new TerrainBiomeRegistry();

    /** Everything the catalog file declared, including entries gated off by a missing mod. */
    private final List<TerrainBiomeSettlement> settlements = new ArrayList<>();
    /** The subset of {@link #settlements} whose {@code requiredMods} are all installed. */
    private List<TerrainBiomeSettlement> activeSettlements = List.of();
    private Map<Short, TerrainBiomeSettlement> byIndex;
    private Map<String, TerrainBiomeSettlement> byKey;
    private List<TerrainBiomeSettlement> overworldCandidates;
    private int indexUpperBound = 2;
    private boolean built = false;

    private TerrainBiomeRegistry() {
        loadFromConfigDir();
        if (settlements.isEmpty()) {
            loadFromResource();
        }
        build();
    }

    public static TerrainBiomeRegistry instance() {
        return INSTANCE;
    }

    /**
     * Try to load from the user's config directory first. This allows mod
     * authors to drop a custom {@code biome_catalog.json} into
     * {@code config/terrain-diffusion-mc/} without touching the JAR.
     */
    private void loadFromConfigDir() {
        try {
            Path configDir = com.github.xandergos.terraindiffusionmc.platform.PlatformPaths.configDir();
            Path configBiomeDir = configDir.resolve("terrain-diffusion-mc");
            Path filePath = configBiomeDir.resolve(CONFIG_FILE_NAME);
            if (Files.exists(filePath)) {
                try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                    List<TerrainBiomeSettlement> loaded = GSON.fromJson(reader, LIST_TYPE);
                    if (loaded != null) {
                        settlements.addAll(loaded);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load biome_catalog.json from config dir: " + e.getMessage());
        }
    }

    private void loadFromResource() {
        // Read straight from the stream: the catalog normally lives inside the mod jar, whose
        // "jar:file:...!/biome_catalog.json" URI Path.of(URI) rejects outright. Going through a
        // Path only ever worked in dev runs, where resources sit unpacked on disk.
        try (java.io.InputStream is = TerrainBiomeRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                System.err.println("biome_catalog.json not found on classpath - biome classification will be empty");
                return;
            }
            try (Reader reader = new java.io.InputStreamReader(is, StandardCharsets.UTF_8)) {
                List<TerrainBiomeSettlement> loaded = GSON.fromJson(reader, LIST_TYPE);
                if (loaded != null) {
                    settlements.addAll(loaded);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load biome_catalog.json: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Register a custom biome settlement (for mod integration).
     * Call {@link #rebuild()} after all registrations are complete.
     */
    public void register(TerrainBiomeSettlement settlement) {
        settlements.removeIf(s -> s.index() == settlement.index());
        settlements.add(settlement);
        built = false;
    }

    /**
     * Rebuild internal lookup maps after registrations.
     */
    public void rebuild() {
        build();
    }

    /**
     * Persists the current in-memory settlement list back to
     * {@code <config-dir>/terrain-diffusion-mc/biome_catalog.json}, preserving the exact JSON
     * schema Gson already round-trips (same field names/structure the file was loaded with).
     * If a file already exists at that path it is backed up first, following the same
     * {@code biome_catalog.pre-<label>-<timestamp>.json} naming pattern used for the earlier
     * hand-fix backup, so an apply that turns out to be wrong is always recoverable.
     *
     * <p>Used by the terrain explorer's "Biome Config" apply flow
     * ({@code ExplorerServer}'s {@code /api/biomes/apply} handler). Callers should already have
     * validated the newly added rule (see {@code BiomeRuleValidator}) before calling this --
     * this method just writes whatever the in-memory state currently is.</p>
     */
    public synchronized void saveToConfigDir() throws IOException {
        saveToConfigDir(com.github.xandergos.terraindiffusionmc.platform.PlatformPaths.configDir());
    }

    /**
     * Same as {@link #saveToConfigDir()} but against an arbitrary base config directory --
     * used by standalone test harnesses to verify the save/backup logic against a scratch
     * directory without ever touching a real installation's live catalog.
     */
    public synchronized void saveToConfigDir(Path baseConfigDir) throws IOException {
        Path configBiomeDir = baseConfigDir.resolve("terrain-diffusion-mc");
        Files.createDirectories(configBiomeDir);
        Path filePath = configBiomeDir.resolve(CONFIG_FILE_NAME);
        if (Files.exists(filePath)) {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
            Path backup = configBiomeDir.resolve("biome_catalog.pre-apply-" + timestamp + ".json");
            Files.copy(filePath, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            GSON.toJson(settlements, LIST_TYPE, writer);
        }
    }

    private void build() {
        if (built) return;

        Map<Short, TerrainBiomeSettlement> indexMap = new LinkedHashMap<>();
        Map<String, TerrainBiomeSettlement> keyMap = new LinkedHashMap<>();
        List<TerrainBiomeSettlement> active = new ArrayList<>();
        List<TerrainBiomeSettlement> overworld = new ArrayList<>();
        short maxIndex = 0;
        int gatedOut = 0;

        for (TerrainBiomeSettlement s : settlements) {
            // Biomes belonging to an absent optional mod are dropped here rather than filtered at
            // every call site, so the rule engine, the biome source and the explorer all agree on
            // one active set.
            if (!s.isAvailable()) {
                gatedOut++;
                continue;
            }
            active.add(s);
            indexMap.put(s.index(), s);
            keyMap.put(s.key(), s);
            if (s.canGenerateOverworld()) overworld.add(s);
            resolveRuleConditions(s);
        }

        // Sized off every declared index, not just the active ones: a world generated with an
        // optional mod installed has its indices baked into cached terrain data, and that cache
        // outlives the mod being uninstalled. Sizing to the active max would turn a later launch
        // without the mod into an out-of-bounds on an index-keyed scratch array.
        for (TerrainBiomeSettlement s : settlements) {
            if (s.index() > maxIndex) maxIndex = s.index();
        }

        if (gatedOut > 0) {
            System.out.println("terrain-diffusion-mc: " + gatedOut + " of " + settlements.size()
                    + " catalog biomes disabled (required mods not installed)");
        }

        activeSettlements = Collections.unmodifiableList(active);
        byIndex = Collections.unmodifiableMap(indexMap);
        byKey = Collections.unmodifiableMap(keyMap);
        overworldCandidates = Collections.unmodifiableList(overworld);
        // +1 so index-sized scratch arrays (e.g. BiomeClassifier's local majority counts) can
        // safely use any registered index, including custom catalogs beyond the bundled one.
        indexUpperBound = Math.max(maxIndex + 1, 2);
        built = true;
    }

    /**
     * Resolves each rule's condition variables to enums once, here on the single-threaded
     * build path, so the classifier's per-pixel hot path never has to.
     */
    private void resolveRuleConditions(TerrainBiomeSettlement settlement) {
        for (TerrainBiomeRule rule : settlement.rules()) {
            for (TerrainBiomeCondition condition : rule.conditions()) {
                condition.resolve();
            }
            for (TerrainBiomeCondition condition : rule.noiseConditions()) {
                condition.resolve();
            }
        }
    }

    // ---- Legacy TerrainBiomeCatalog-compatible API ----

    /**
     * The biomes actually taking part in generation: catalog entries minus those gated off by a
     * missing optional mod. This is what the rule engine and biome sources build from.
     */
    public List<TerrainBiomeSettlement> all() {
        return activeSettlements;
    }

    /**
     * Every entry the catalog declared, gated-off ones included. Only for code that must round
     * trip the file itself -- {@link #saveToConfigDir()} writes this so an apply performed
     * without Biomes O' Plenty installed doesn't silently delete its biomes from the catalog.
     */
    public List<TerrainBiomeSettlement> allDeclared() {
        return Collections.unmodifiableList(settlements);
    }

    public List<TerrainBiomeSettlement> overworldCandidates() {
        return overworldCandidates;
    }

    public TerrainBiomeSettlement byIndex(short index) {
        TerrainBiomeSettlement s = byIndex.get(index);
        if (s != null) return s;
        return byIndex.get((short) 1); // fallback to plains
    }

    public TerrainBiomeSettlement byKey(String key) {
        return byKey.get(key);
    }

    public String keyForIndex(short index) {
        return byIndex(index).key();
    }

    public int colorForIndex(short index) {
        return byIndex(index).color();
    }

    public Map<Short, String> indexToKeyMap() {
        Map<Short, String> result = new LinkedHashMap<>();
        for (TerrainBiomeSettlement s : activeSettlements) {
            result.put(s.index(), s.key());
        }
        return result;
    }

    public Map<Short, Integer> indexToColorMap() {
        Map<Short, Integer> result = new LinkedHashMap<>();
        for (TerrainBiomeSettlement s : activeSettlements) {
            result.put(s.index(), s.color());
        }
        return result;
    }

    // ---- Property queries for smoothing and river logic ----

    public boolean isHardBoundary(short index) {
        TerrainBiomeSettlement s = byIndex.get(index);
        return s != null && s.hardBoundary();
    }

    public boolean isBlendableLandBiome(short index) {
        TerrainBiomeSettlement s = byIndex.get(index);
        return s != null && s.blendable();
    }

    public boolean isRiver(short index) {
        TerrainBiomeSettlement s = byIndex.get(index);
        return s != null && s.isRiver();
    }

    public boolean isFrozenRiver(short index) {
        TerrainBiomeSettlement s = byIndex.get(index);
        return s != null && s.isFrozenRiver();
    }

    /** Returns the index of the default fallback biome (plains). */
    public short defaultBiomeIndex() {
        TerrainBiomeSettlement plains = byIndex.get((short) 1);
        return plains != null ? plains.index() : (short) 1;
    }

    /**
     * One past the highest registered biome index. Sized for index-keyed scratch arrays;
     * always covers every currently registered settlement, including custom catalogs
     * (config-dir overrides or mod {@link #register}) that go beyond the bundled default.
     */
    public int indexUpperBound() {
        return indexUpperBound;
    }

    /** Returns the index of the river biome. */
    public short riverBiomeIndex() {
        for (TerrainBiomeSettlement s : activeSettlements) {
            if (s.isRiver()) return s.index();
        }
        return (short) 38;
    }

    /** Returns the index of the frozen river biome. */
    public short frozenRiverBiomeIndex() {
        for (TerrainBiomeSettlement s : activeSettlements) {
            if (s.isFrozenRiver()) return s.index();
        }
        return (short) 39;
    }
}
