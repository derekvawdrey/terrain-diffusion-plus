package com.github.xandergos.terraindiffusionmc.biome;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evaluates data-driven biome rules against a climate sample and noise values.
 *
 * <p>For a given zone (ocean / beach / mountain / lowland), collects all rules
 * from all settlements, evaluates them, and returns the index of the winning
 * biome (highest priority, ties broken by highest index).</p>
 */
public final class BiomeRuleEngine {

    private final TerrainBiomeRegistry registry;

    /** Pre-grouped rules by zone for fast lookup. */
    private Map<String, RuleGroup> zoneGroups;
    private boolean initialized = false;

    public BiomeRuleEngine(TerrainBiomeRegistry registry) {
        this.registry = registry;
    }

    private void init() {
        if (initialized) return;

        Map<String, RuleGroup> groups = new LinkedHashMap<>();
        for (TerrainBiomeSettlement settlement : registry.all()) {
            for (TerrainBiomeRule rule : settlement.rules()) {
                String zone = rule.zone();
                groups.computeIfAbsent(zone, z -> new RuleGroup()).add(settlement.index(), rule);
            }
        }
        zoneGroups = groups;
        initialized = true;
    }

    /**
     * Select the best matching biome index for a given zone.
     *
     * @param zone        "ocean", "beach", "mountain", or "lowland"
     * @param sample      climate data for this pixel
     * @param noiseValues noise fields: variantNoise, cherryNoise, paleNoise, clearingNoise, flowerNoise
     * @return winning biome index, or {@code defaultIndex} if no rule matches
     */
    public short select(String zone, TerrainClimateSample sample, Map<String, Float> noiseValues, short defaultIndex) {
        init();
        RuleGroup group = zoneGroups.get(zone);
        if (group == null) return defaultIndex;

        short bestIndex = defaultIndex;
        int bestPriority = -1;

        for (RuleEntry entry : group.entries) {
            if (!entry.rule.matches(sample)) continue;
            if (!entry.rule.matchesNoise(noiseValues)) continue;

            int pri = entry.rule.priority();
            if (pri > bestPriority || (pri == bestPriority && entry.index > bestIndex)) {
                bestPriority = pri;
                bestIndex = entry.index;
            }
        }

        return bestIndex;
    }

    private static final class RuleGroup {
        final java.util.List<RuleEntry> entries = new java.util.ArrayList<>();

        void add(short index, TerrainBiomeRule rule) {
            entries.add(new RuleEntry(index, rule));
        }
    }

    private static final class RuleEntry {
        final short index;
        final TerrainBiomeRule rule;

        RuleEntry(short index, TerrainBiomeRule rule) {
            this.index = index;
            this.rule = rule;
        }
    }
}
