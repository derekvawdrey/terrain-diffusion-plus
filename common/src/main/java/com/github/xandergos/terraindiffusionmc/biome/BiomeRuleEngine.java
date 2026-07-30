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
 * <p>Rules are pre-sorted into priority tiers (highest first). A pixel usually has
 * exactly one eligible biome per zone, in which case the highest matching tier wins
 * outright, same as before. When a pixel's climate also satisfies a <i>different</i>
 * biome's rule at a lower tier (e.g. a narrow modded-biome rule nested inside a broad
 * vanilla rule's range), that biome is no longer automatically shut out: up to two
 * runner-up candidates are collected, and the winner among all eligible candidates is
 * resolved by a smooth, per-biome Perlin-style noise field ({@link #competitionNoise}),
 * biased toward the higher-priority candidate but not deterministic. This keeps
 * genuinely eligible but lower-priority biomes present as real, spatially coherent
 * patches instead of being permanently invisible.</p>
 */
public final class BiomeRuleEngine {

    /**
     * How much a runner-up candidate's {@link #competitionNoise} score is docked per
     * rank below the top matching candidate. Noise values are roughly in [-1, 1], so a
     * rank-1 runner-up needs to beat the leader's noise by more than this to win --
     * giving it a real minority share of its eligible area rather than none. Raise this
     * to make runner-ups (e.g. modded biomes shadowed by broad vanilla rules) rarer;
     * lower it to make them more prevalent.
     */
    private static final float COMPETITION_RANK_PENALTY = 0.8f;

    /** World-space wavelength of {@link #competitionNoise}'s field, in blocks. */
    private static final float COMPETITION_NOISE_WAVELENGTH = 900f;

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
     * @param worldX      world X of this pixel, for {@link #competitionNoise}
     * @param worldZ      world Z of this pixel, for {@link #competitionNoise}
     * @return winning biome index, or {@code defaultIndex} if no rule matches
     */
    public short select(String zone, TerrainClimateSample sample, TerrainBiomeNoiseSample noiseValues,
                         short defaultIndex, float worldX, float worldZ) {
        init();
        RuleGroup group = zoneGroups.get(zone);
        if (group == null) return defaultIndex;

        // Collect up to three distinct eligible biomes, in priority-tier order. Almost every
        // pixel only ever has one (candidate1 stays unset), in which case this is exactly as
        // cheap and deterministic as the old first-tier-wins behavior.
        short candidate0 = -1, candidate1 = -1, candidate2 = -1;
        for (RuleEntry[] tier : group.tiers) {
            short bestIndex = -1;
            for (RuleEntry entry : tier) {
                if (entry.index <= bestIndex) continue;
                if (!entry.rule.matches(sample)) continue;
                if (!entry.rule.matchesNoise(noiseValues)) continue;
                bestIndex = entry.index;
            }
            if (bestIndex < 0) continue;

            if (candidate0 < 0) {
                candidate0 = bestIndex;
            } else if (bestIndex != candidate0 && candidate1 < 0) {
                candidate1 = bestIndex;
            } else if (bestIndex != candidate0 && bestIndex != candidate1 && candidate2 < 0) {
                candidate2 = bestIndex;
                break;
            }
        }

        if (candidate0 < 0) return defaultIndex;
        if (candidate1 < 0) return candidate0;

        short winner = candidate0;
        float bestScore = competitionNoise(candidate0, worldX, worldZ);
        float score1 = competitionNoise(candidate1, worldX, worldZ) - COMPETITION_RANK_PENALTY;
        if (score1 > bestScore) {
            bestScore = score1;
            winner = candidate1;
        }
        if (candidate2 >= 0) {
            float score2 = competitionNoise(candidate2, worldX, worldZ) - 2f * COMPETITION_RANK_PENALTY;
            if (score2 > bestScore) {
                winner = candidate2;
            }
        }
        return winner;
    }

    /**
     * Smooth, spatially coherent noise field independent per biome index, used to resolve
     * which of several genuinely-eligible biomes wins at a given pixel. Deliberately a
     * self-contained hash-based value noise (not {@code FastNoiseLite}) so the {@code biome}
     * package doesn't need to depend on {@code pipeline}; same single-octave/smoothstep
     * construction as the value noise in {@code hydrology.FluvialRiverNetwork}.
     */
    private static float competitionNoise(short biomeIndex, float worldX, float worldZ) {
        return valueNoise(biomeIndex, worldX / COMPETITION_NOISE_WAVELENGTH, worldZ / COMPETITION_NOISE_WAVELENGTH);
    }

    private static float valueNoise(int seed, float x, float y) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        float fx = smoothstep(x - x0);
        float fy = smoothstep(y - y0);
        float a = hashUnit(seed, x0, y0);
        float b = hashUnit(seed, x0 + 1, y0);
        float c = hashUnit(seed, x0, y0 + 1);
        float d = hashUnit(seed, x0 + 1, y0 + 1);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fy);
    }

    private static float hashUnit(int seed, int x, int y) {
        long value = seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) y * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 40) / (float) (1L << 24)) * 2.0f - 1.0f;
    }

    private static int fastFloor(float value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static float smoothstep(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
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
