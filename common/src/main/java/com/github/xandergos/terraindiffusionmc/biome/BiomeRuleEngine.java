package com.github.xandergos.terraindiffusionmc.biome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Evaluates data-driven biome rules against a climate sample and noise values.
 *
 * <p>For a given zone (ocean / beach / mountain / lowland), collects all rules
 * from all settlements, evaluates them, and returns the index of the winning
 * biome (highest priority, ties broken by highest index).</p>
 *
 * <p>Rules are pre-sorted into priority tiers (highest first). Since a match in
 * a higher tier always outranks any match in a lower tier regardless of index,
 * evaluation stops at the first tier that produces a match instead of scanning
 * every rule in the zone.</p>
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

        Map<String, Map<Integer, List<RuleEntry>>> zonePriorityGroups = new LinkedHashMap<>();
        for (TerrainBiomeSettlement settlement : registry.all()) {
            for (TerrainBiomeRule rule : settlement.rules()) {
                zonePriorityGroups
                        .computeIfAbsent(rule.zone(), z -> new TreeMap<>(Comparator.reverseOrder()))
                        .computeIfAbsent(rule.priority(), p -> new ArrayList<>())
                        .add(new RuleEntry(settlement.index(), rule));
            }
        }

        Map<String, RuleGroup> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Integer, List<RuleEntry>>> zoneEntry : zonePriorityGroups.entrySet()) {
            RuleEntry[][] tiers = new RuleEntry[zoneEntry.getValue().size()][];
            int tierIndex = 0;
            for (List<RuleEntry> tier : zoneEntry.getValue().values()) {
                tiers[tierIndex++] = tier.toArray(new RuleEntry[0]);
            }
            groups.put(zoneEntry.getKey(), new RuleGroup(tiers));
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
    public short select(String zone, TerrainClimateSample sample, TerrainBiomeNoiseSample noiseValues, short defaultIndex) {
        init();
        RuleGroup group = zoneGroups.get(zone);
        if (group == null) return defaultIndex;

        for (RuleEntry[] tier : group.tiers) {
            short bestIndex = -1;
            for (RuleEntry entry : tier) {
                if (entry.index <= bestIndex) continue;
                if (!entry.rule.matches(sample)) continue;
                if (!entry.rule.matchesNoise(noiseValues)) continue;
                bestIndex = entry.index;
            }
            if (bestIndex >= 0) return bestIndex;
        }

        return defaultIndex;
    }

    private static final class RuleGroup {
        /** Tiers sorted by priority descending; ties within a tier are broken by highest index. */
        final RuleEntry[][] tiers;

        RuleGroup(RuleEntry[][] tiers) {
            this.tiers = tiers;
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
