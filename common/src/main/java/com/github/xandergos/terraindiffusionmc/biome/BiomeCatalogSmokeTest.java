package com.github.xandergos.terraindiffusionmc.biome;

import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone check (LapseRateTest-style: plain {@code main}, no JUnit/gradle test task, no ONNX
 * or a running game needed) for the terrain explorer's "Biome Config" generate/validate/apply
 * flow ({@link BiomeRuleGenerator}, {@link BiomeRuleValidator},
 * {@link TerrainBiomeRegistry#saveToConfigDir}).
 *
 * <p><b>Never touches a real installation's live {@code biome_catalog.json}.</b> Each mode below
 * points {@link PlatformPaths} at a throwaway scratch directory (passed as {@code args[1]})
 * before the {@link TerrainBiomeRegistry} singleton is ever referenced, since that singleton
 * eagerly loads its catalog from {@code PlatformPaths.configDir()} the first time any code
 * touches the class. Because it's a JVM-wide singleton, each mode needs its own fresh JVM
 * (i.e. a separate {@code java} invocation) -- run this class 3 times with different
 * {@code args[0]}/scratch dirs, not once with all three in sequence.</p>
 *
 * <p>Modes ({@code args[0]}):</p>
 * <ul>
 *   <li>{@code bundled} -- scratch dir has no catalog yet, so the registry falls back to the
 *   bundled classpath {@code biome_catalog.json} (the same one shipped in the jar). Requests a
 *   climate niche ({@code lowland}/temperate/moderate/forest) that a real, well-covered catalog
 *   should already have a rule "close enough" to -- exercises the anchor-sharing (same-tier)
 *   path.</li>
 *   <li>{@code minimal} -- writes a hand-built, deliberately tiny catalog (rules in
 *   {@code lowland} only) into the scratch config dir BEFORE the registry loads, then requests a
 *   {@code mountain}-zone niche that catalog has zero rules for -- guarantees the new-distinct-
 *   priority-tier fallback path, which is difficult to hit organically against the real,
 *   deliberately climate-space-covering bundled catalog (empirically confirmed zero gaps across
 *   all 300 zone/temperature-band/moisture-band/tree-density combinations there).</li>
 *   <li>{@code apply} -- runs a full preview+apply+save+backup+reload round trip against a fresh
 *   scratch dir and verifies the written file parses back with the new rule present, and that a
 *   second apply creates a {@code biome_catalog.pre-apply-*.json} backup of the first.</li>
 * </ul>
 */
public class BiomeCatalogSmokeTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BiomeCatalogSmokeTest <bundled|minimal|apply> <scratchDir>");
            System.exit(2);
        }
        String mode = args[0];
        Path scratchDir = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(scratchDir);

        switch (mode) {
            case "bundled" -> runBundledAnchorCase(scratchDir);
            case "minimal" -> runMinimalNoAnchorCase(scratchDir);
            case "apply" -> runApplyRoundTripCase(scratchDir);
            default -> {
                System.err.println("Unknown mode: " + mode);
                System.exit(2);
            }
        }

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED (" + mode + ")");
    }

    // =========================================================================
    // Mode: bundled — anchor-sharing (same priority tier) path
    // =========================================================================

    private static void runBundledAnchorCase(Path scratchDir) {
        PlatformPaths.configure(scratchDir.resolve("config"), scratchDir);
        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        System.out.println("[bundled] loaded " + registry.all().size() + " settlements from bundled resource");
        expect("bundled catalog is non-trivial", registry.all().size() > 10);

        BiomeRuleGenerator.Request req = new BiomeRuleGenerator.Request(
                "biomelab_test:mossy_glade", "lowland",
                BiomeRuleGenerator.TemperatureBand.TEMPERATE, BiomeRuleGenerator.MoistureBand.MODERATE,
                BiomeRuleGenerator.TreeDensity.FOREST, BiomeRuleGenerator.Rarity.UNCOMMON);
        BiomeRuleGenerator.Result result = BiomeRuleGenerator.generate(registry, req);

        System.out.println("[bundled] newSettlement=" + result.newSettlement()
                + " assignedIndex=" + result.assignedIndex()
                + " priority=" + result.priority() + " newTier=" + result.newTier());
        System.out.println("[bundled] anchor=" + result.anchor());
        System.out.println("[bundled] rule conditions=" + result.rule().conditions());
        System.out.println("[bundled] rule noiseConditions=" + result.rule().noiseConditions());
        System.out.println("[bundled] validation findings=" + result.validationFindings());

        expect("new biome key has no pre-existing settlement", result.newSettlement());
        expect("assigned a fresh index beyond the bundled catalog",
                result.assignedIndex() >= registry.all().size() - 1);
        expect("found an anchor to share a tier with", result.anchor() != null);
        expect("did not fall back to a new tier", !result.newTier());
        expect("uncommon rarity while sharing a tier adds a variantNoise gate",
                !result.rule().noiseConditions().isEmpty());
        expect("generated rule passes validation", result.valid());

        // A second, independent request for a totally different (also well-covered) niche should
        // get its own fresh index (sequential allocation), proving allocation doesn't collide.
        BiomeRuleGenerator.Request req2 = new BiomeRuleGenerator.Request(
                "biomelab_test:frost_hollow", "mountain",
                BiomeRuleGenerator.TemperatureBand.COLD, BiomeRuleGenerator.MoistureBand.WET,
                BiomeRuleGenerator.TreeDensity.SPARSE, BiomeRuleGenerator.Rarity.RARE);
        BiomeRuleGenerator.Result result2 = BiomeRuleGenerator.generate(registry, req2);
        System.out.println("[bundled] second request assignedIndex=" + result2.assignedIndex()
                + " anchor=" + result2.anchor() + " valid=" + result2.valid());
        expect("second new biome also finds an anchor", result2.anchor() != null);
        expect("second generated rule passes validation", result2.valid());
        expect("rare rarity picks a stricter (higher) noise threshold than uncommon",
                result2.rule().noiseConditions().get(0).numericValue()
                        > result.rule().noiseConditions().get(0).numericValue());
    }

    // =========================================================================
    // Mode: minimal — guaranteed no-anchor / new-tier fallback path
    // =========================================================================

    private static void runMinimalNoAnchorCase(Path scratchDir) throws IOException {
        Path configDir = scratchDir.resolve("config");
        Path catalogDir = configDir.resolve("terrain-diffusion-mc");
        Files.createDirectories(catalogDir);
        // A deliberately tiny hand-written catalog: exactly one settlement, one rule, "lowland"
        // zone only. Written to the scratch config dir BEFORE PlatformPaths.configure() below is
        // ever observed by TerrainBiomeRegistry, so loadFromConfigDir() picks it up instead of
        // falling back to the bundled resource.
        String minimalCatalog = """
                [
                  {
                    "index": 1,
                    "key": "minecraft:plains",
                    "fallbackKey": "minecraft:plains",
                    "kind": "OVERWORLD",
                    "color": 9286496,
                    "hardBoundary": false,
                    "blendable": true,
                    "river": false,
                    "frozenRiver": false,
                    "canGenerateOverworld": true,
                    "rules": [
                      {
                        "zone": "lowland",
                        "priority": 40,
                        "conditions": [
                          {"variable": "treeCoverage", "op": "eq", "value": 0.0},
                          {"variable": "temperatureC", "op": "between", "value": -5, "value2": 20}
                        ]
                      }
                    ]
                  }
                ]
                """;
        Files.writeString(catalogDir.resolve("biome_catalog.json"), minimalCatalog, StandardCharsets.UTF_8);

        PlatformPaths.configure(configDir, scratchDir);
        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        System.out.println("[minimal] loaded " + registry.all().size() + " settlement(s) from scratch config dir");
        expect("loaded exactly the hand-written minimal catalog", registry.all().size() == 1);

        // "mountain" zone has zero rules in this minimal catalog -> guaranteed no anchor.
        BiomeRuleGenerator.Request req = new BiomeRuleGenerator.Request(
                "biomelab_test:sky_grove", "mountain",
                BiomeRuleGenerator.TemperatureBand.WARM, BiomeRuleGenerator.MoistureBand.WET,
                BiomeRuleGenerator.TreeDensity.DENSE, BiomeRuleGenerator.Rarity.COMMON);
        BiomeRuleGenerator.Result result = BiomeRuleGenerator.generate(registry, req);

        System.out.println("[minimal] newSettlement=" + result.newSettlement()
                + " priority=" + result.priority() + " newTier=" + result.newTier()
                + " anchor=" + result.anchor());
        System.out.println("[minimal] rule=" + result.rule().conditions());
        System.out.println("[minimal] validation findings=" + result.validationFindings());

        expect("no anchor found in an empty-for-this-zone catalog", result.anchor() == null);
        expect("fell back to a brand-new distinct priority tier", result.newTier());
        expect("new tier priority is a small positive number (max-for-zone[0] + 1)",
                result.priority() == 1);
        expect("common rarity with NO anchor gets no noise gate at all",
                result.rule().noiseConditions().isEmpty());
        expect("generated rule still passes validation", result.valid());
    }

    // =========================================================================
    // Mode: apply — full preview+apply+save+backup+reload round trip
    // =========================================================================

    private static void runApplyRoundTripCase(Path scratchDir) throws IOException {
        Path configDir = scratchDir.resolve("config");
        PlatformPaths.configure(configDir, scratchDir);
        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        int before = registry.all().size();

        BiomeRuleGenerator.Request req = new BiomeRuleGenerator.Request(
                "biomelab_test:cinder_flat", "lowland",
                BiomeRuleGenerator.TemperatureBand.HOT, BiomeRuleGenerator.MoistureBand.DRY,
                BiomeRuleGenerator.TreeDensity.BARE, BiomeRuleGenerator.Rarity.VERY_RARE);
        BiomeRuleGenerator.Result result = BiomeRuleGenerator.generate(registry, req);
        expect("apply-candidate rule passes validation", result.valid());

        Path catalogFile = configDir.resolve("terrain-diffusion-mc").resolve("biome_catalog.json");
        expect("no catalog file exists yet in the fresh scratch dir", !Files.exists(catalogFile));

        // --- apply #1: no pre-existing file, so no backup should be made ---
        result.settlement().addRule(result.rule());
        registry.register(result.settlement());
        registry.rebuild();
        registry.saveToConfigDir();

        expect("catalog file now exists after first save", Files.exists(catalogFile));
        long backupsAfterFirst = countBackups(configDir.resolve("terrain-diffusion-mc"));
        expect("no backup created on first save (nothing to back up)", backupsAfterFirst == 0);

        List<TerrainBiomeSettlement> reloaded = readBack(catalogFile);
        expect("reloaded catalog has one more settlement than before",
                reloaded.size() == before + 1);
        TerrainBiomeSettlement written = null;
        for (TerrainBiomeSettlement s : reloaded) {
            if (s.key().equals("biomelab_test:cinder_flat")) written = s;
        }
        expect("new settlement is present in the saved+reloaded file", written != null);
        expect("new settlement has exactly one rule", written != null && written.rules().size() == 1);
        if (written != null) {
            TerrainBiomeRule rule = written.rules().get(0);
            expect("saved rule kept zone=lowland", "lowland".equals(rule.zone()));
            expect("saved rule kept the very-rare noise gate", !rule.noiseConditions().isEmpty());
        }

        // --- apply #2: file now exists, so this save must back it up first ---
        BiomeRuleGenerator.Request req2 = new BiomeRuleGenerator.Request(
                "biomelab_test:ash_dune", "lowland",
                BiomeRuleGenerator.TemperatureBand.HOT, BiomeRuleGenerator.MoistureBand.DRY,
                BiomeRuleGenerator.TreeDensity.SPARSE, BiomeRuleGenerator.Rarity.RARE);
        BiomeRuleGenerator.Result result2 = BiomeRuleGenerator.generate(registry, req2);
        expect("second apply-candidate rule passes validation", result2.valid());
        result2.settlement().addRule(result2.rule());
        registry.register(result2.settlement());
        registry.rebuild();
        registry.saveToConfigDir();

        long backupsAfterSecond = countBackups(configDir.resolve("terrain-diffusion-mc"));
        expect("exactly one backup created on second save", backupsAfterSecond == 1);

        List<TerrainBiomeSettlement> reloaded2 = readBack(catalogFile);
        expect("reloaded catalog now has two more settlements than the original",
                reloaded2.size() == before + 2);

        System.out.println("[apply] before=" + before + " afterFirst=" + reloaded.size()
                + " afterSecond=" + reloaded2.size() + " backupsAfterSecond=" + backupsAfterSecond);
        System.out.println("[apply] scratch catalog file: " + catalogFile);
    }

    private static long countBackups(Path catalogDir) throws IOException {
        try (var stream = Files.list(catalogDir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("biome_catalog.pre-")).count();
        }
    }

    private static List<TerrainBiomeSettlement> readBack(Path catalogFile) throws IOException {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        java.lang.reflect.Type type =
                new com.google.gson.reflect.TypeToken<List<TerrainBiomeSettlement>>() {}.getType();
        try (var reader = Files.newBufferedReader(catalogFile, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, type);
        }
    }

    private static void expect(String description, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + description);
        } else {
            System.out.println("  FAIL: " + description);
            failures++;
        }
    }
}
