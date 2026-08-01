package com.github.xandergos.terraindiffusionmc.biome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates data-driven biome rules against a climate sample and noise values.
 *
 * <p>For a given zone (ocean / beach / mountain / lowland), collects every biome whose rules match
 * the pixel and returns one of them, chosen by {@link TerrainBiomeRule#rarity()}.</p>
 *
 * <h2>Weighted selection</h2>
 * <p>Among the biomes eligible at a pixel, biome {@code i} wins with probability
 * {@code w_i / sum(w)} -- exactly, not approximately. That is achieved with the
 * Efraimidis-Spirakis weighted-sampling key {@code ln(u_i) / w_i}, taking the largest: if each
 * {@code u_i} is uniform on (0, 1) then {@code argmax} over that key reproduces the weights.
 * Crucially the {@code u_i} here are not white noise but a smooth, per-biome, spatially coherent
 * field ({@link #competitionNoise}), so the result is real biome patches of the right average size
 * rather than pixel confetti -- while the marginal share still lands on the authored weight.</p>
 *
 * <h2>Why weights instead of priorities</h2>
 * <p>Selection used to walk integer priority tiers, highest first, letting a runner-up steal a
 * pixel only by beating the leader's noise by a fixed penalty. That made "rarer variant of" a
 * numeric ordering constraint: any biome whose rules refined a broader biome's but whose priority
 * sat <i>below</i> it was shadowed to near-zero area with no warning at all -- {@code bamboo_jungle}
 * shipped eligible on 0.16% of pixels and winning 0.0000% of them. Weights state the intent
 * directly and cannot be broken by inserting an unrelated biome in between.</p>
 *
 * <h2>Overrides</h2>
 * <p>A rule may set {@link TerrainBiomeRule#isOverride()} to claim structural dominance: if any
 * override rule matches a pixel, only override candidates compete there, still by weight. This is
 * the escape hatch for cases where a biome must strictly win rather than merely win often; the
 * ordinary variant relationship wants a weight.</p>
 */
public final class BiomeRuleEngine {

    /** World-space wavelength of {@link #competitionNoise}'s field, in blocks. */
    private static final float COMPETITION_NOISE_WAVELENGTH = 900f;

    /** Keeps {@code u} off the open interval's endpoints so {@code ln(u)} stays finite. */
    private static final float U_EPSILON = 1e-6f;

    /**
     * CDF of {@link #competitionNoise}'s output rescaled to [0, 1], sampled on a uniform grid and
     * linearly interpolated. Needed because the Efraimidis-Spirakis key is only exact when its
     * {@code u} is uniformly distributed, and value noise emphatically is not: bilinearly blending
     * four hash values concentrates it around the midpoint (measured standard deviation 0.214
     * against a uniform's 0.289). Feeding the raw noise in makes high weights win far more than
     * their share -- a 1.0 / 0.35 / 0.12 contest landed at 83/14/2% instead of 68/24/8%. Passing
     * it through its own CDF first restores exactness while leaving the field's spatial structure
     * completely untouched.
     *
     * <p>Measured over 24M samples of the real field; regenerate with the same procedure if
     * {@link #valueNoise}'s construction ever changes. {@code biomelab/engine.py} carries an
     * identical copy.</p>
     */
    private static final float[] NOISE_CDF = {
        0.000000f, 0.002161f, 0.008784f, 0.019927f, 0.035609f, 0.055724f, 0.080132f, 0.108747f,
        0.141309f, 0.177474f, 0.216943f, 0.259346f, 0.304313f, 0.351328f, 0.399982f, 0.449803f,
        0.500061f, 0.550323f, 0.600022f, 0.648588f, 0.695640f, 0.740613f, 0.783037f, 0.822494f,
        0.858638f, 0.891144f, 0.919765f, 0.944205f, 0.964329f, 0.980018f, 0.991182f, 0.997837f,
        1.000000f
    };

    private final TerrainBiomeRegistry registry;

    /** Pre-grouped rules by zone for fast lookup. */
    private Map<String, RuleEntry[]> zoneGroups;
    private boolean initialized = false;

    public BiomeRuleEngine(TerrainBiomeRegistry registry) {
        this.registry = registry;
    }

    private void init() {
        if (initialized) return;

        Map<String, List<RuleEntry>> byZone = new LinkedHashMap<>();
        for (TerrainBiomeSettlement settlement : registry.all()) {
            for (TerrainBiomeRule rule : settlement.rules()) {
                if (rule.rarity() <= 0f) continue;  // weightless rules can never be selected
                if (!rule.isAvailable()) continue;  // rule belongs to an optional mod that isn't installed
                byZone.computeIfAbsent(rule.zone(), z -> new ArrayList<>())
                        .add(new RuleEntry(settlement.index(), rule));
            }
        }

        Map<String, RuleEntry[]> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<RuleEntry>> entry : byZone.entrySet()) {
            groups.put(entry.getKey(), entry.getValue().toArray(new RuleEntry[0]));
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
        RuleEntry[] rules = zoneGroups.get(zone);
        if (rules == null) return defaultIndex;

        // Single pass, no allocation. A biome with several matching rules simply gets scored once
        // per rule; since the key rises monotonically with weight for a fixed u, keeping the
        // running maximum automatically uses that biome's most generous matching rule.
        //
        // The overwhelmingly common case is exactly one matching rule, where the weight cannot
        // change the outcome, so the first match is only stashed -- the noise lookup and the
        // logarithm are deferred until a second match actually proves there is a contest.
        int matches = 0;
        short firstIndex = -1;
        float firstRarity = 0f;
        boolean firstOverride = false;

        short winner = -1;
        float bestKey = Float.NEGATIVE_INFINITY;
        short overrideWinner = -1;
        float bestOverrideKey = Float.NEGATIVE_INFINITY;

        for (RuleEntry entry : rules) {
            if (!entry.rule.matches(sample)) continue;
            if (!entry.rule.matchesNoise(noiseValues)) continue;

            matches++;
            if (matches == 1) {
                firstIndex = entry.index;
                firstRarity = entry.rule.rarity();
                firstOverride = entry.rule.isOverride();
                continue;
            }
            if (matches == 2) {
                float firstKey = selectionKey(firstIndex, firstRarity, worldX, worldZ);
                if (firstOverride) {
                    bestOverrideKey = firstKey;
                    overrideWinner = firstIndex;
                } else {
                    bestKey = firstKey;
                    winner = firstIndex;
                }
            }

            float key = selectionKey(entry.index, entry.rule.rarity(), worldX, worldZ);
            if (entry.rule.isOverride()) {
                if (key > bestOverrideKey) {
                    bestOverrideKey = key;
                    overrideWinner = entry.index;
                }
            } else if (key > bestKey) {
                bestKey = key;
                winner = entry.index;
            }
        }

        if (matches == 0) return defaultIndex;
        if (matches == 1) return firstIndex;
        if (overrideWinner >= 0) return overrideWinner;
        return winner;
    }

    /**
     * Efraimidis-Spirakis key {@code ln(u) / w}. {@code ln(u)} is negative, so a larger weight
     * pulls the key toward zero and wins more often; taking the argmax over candidates reproduces
     * {@code P(i) = w_i / sum(w)} when the {@code u} values are independent and uniform.
     */
    private static float selectionKey(short biomeIndex, float weight, float worldX, float worldZ) {
        float u = uniformNoise(competitionNoise(biomeIndex, worldX, worldZ));
        return (float) Math.log(u) / weight;
    }

    /**
     * Maps the noise field's roughly [-1, 1] output onto a value uniform on the open interval
     * (0, 1), by rescaling to [0, 1] and applying {@link #NOISE_CDF}.
     */
    private static float uniformNoise(float noise) {
        float x = (noise + 1f) * 0.5f;
        if (x <= 0f) return U_EPSILON;
        if (x >= 1f) return 1f - U_EPSILON;

        float scaled = x * (NOISE_CDF.length - 1);
        int lo = (int) scaled;
        if (lo >= NOISE_CDF.length - 1) lo = NOISE_CDF.length - 2;
        float frac = scaled - lo;
        float u = NOISE_CDF[lo] + (NOISE_CDF[lo + 1] - NOISE_CDF[lo]) * frac;

        if (u < U_EPSILON) return U_EPSILON;
        if (u > 1f - U_EPSILON) return 1f - U_EPSILON;
        return u;
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

    private static final class RuleEntry {
        final short index;
        final TerrainBiomeRule rule;

        RuleEntry(short index, TerrainBiomeRule rule) {
            this.index = index;
            this.rule = rule;
        }
    }
}
