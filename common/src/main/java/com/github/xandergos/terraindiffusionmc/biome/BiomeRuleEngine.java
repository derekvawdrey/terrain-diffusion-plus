package com.github.xandergos.terraindiffusionmc.biome;

import java.util.ArrayList;
import java.util.HashMap;
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
 *
 * <h2>Compiled rules</h2>
 * <p>Selection runs once per generated block, so the authored rule objects are compiled at first
 * use into flat primitive arrays ({@link CompiledZone}) and matched against a preloaded
 * {@link Scratch} value vector. That removes, from the inner loop, the per-condition list
 * iterators, {@code Number}/{@code Boolean} unboxing and per-zone map lookup that the object form
 * would pay for on every one of the ~4 million pixels in a hydrology tile. The compiled form
 * evaluates exactly the conditions the objects do, in the same order, with the same float
 * comparisons -- {@link TerrainBiomeRule#matches} stays as the readable reference implementation
 * for cold paths such as the explorer's overlay.</p>
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

    /** Condition kinds in the compiled form. */
    private static final byte KIND_NUMERIC = 0;
    private static final byte KIND_BOOL_FALSE = 1;
    private static final byte KIND_BOOL_TRUE = 2;

    /** Operator codes; {@link #OP_NEVER} stands in for a catalog rule with no operator. */
    private static final byte OP_NEVER = -1;

    /** {@code Variable.values()} clones its array on every call, so hold one copy. */
    private static final TerrainBiomeCondition.Variable[] VARIABLES = TerrainBiomeCondition.Variable.values();
    private static final int VARIABLE_COUNT = VARIABLES.length;

    private final TerrainBiomeRegistry registry;

    /** Compiled rules per zone, published as a whole once built. */
    private volatile Compiled compiled;

    public BiomeRuleEngine(TerrainBiomeRegistry registry) {
        this.registry = registry;
    }

    /** Supplies a noise field's value at a world position, for fields sampled only on demand. */
    @FunctionalInterface
    public interface NoiseSampler {
        float sample(TerrainBiomeCondition.Variable variable, float worldX, float worldZ);
    }

    /** Per-thread value vectors a compiled match reads. Reused across pixels; never shared. */
    public static final class Scratch {
        private final float[] climateValues = new float[VARIABLE_COUNT];
        private final boolean[] climateFlags = new boolean[VARIABLE_COUNT];
        private final float[] noiseValues = new float[VARIABLE_COUNT];

        /** Variables still to be sampled for this pixel, one bit per {@code Variable} ordinal. */
        private int deferred;
        private NoiseSampler sampler;
        private float worldX;
        private float worldZ;

        public Scratch() {
            // Variables a sample doesn't carry read as NaN, so every numeric comparison against
            // them is false -- the behaviour of the object form's `default -> Float.NaN`.
            java.util.Arrays.fill(climateValues, Float.NaN);
            java.util.Arrays.fill(noiseValues, Float.NaN);
        }

        /** Loads the twelve numeric climate variables and the five boolean flags. */
        public void setClimate(TerrainClimateSample sample) {
            float[] values = climateValues;
            values[TerrainBiomeCondition.Variable.ELEVATION_M.ordinal()] = sample.elevationM();
            values[TerrainBiomeCondition.Variable.TEMPERATURE_C.ordinal()] = sample.temperatureC();
            values[TerrainBiomeCondition.Variable.TEMPERATURE_SEASONALITY.ordinal()] = sample.temperatureSeasonality();
            values[TerrainBiomeCondition.Variable.PRECIPITATION_MM.ordinal()] = sample.precipitationMm();
            values[TerrainBiomeCondition.Variable.PRECIPITATION_CV.ordinal()] = sample.precipitationCv();
            values[TerrainBiomeCondition.Variable.MOISTURE.ordinal()] = sample.moisture();
            values[TerrainBiomeCondition.Variable.ARIDITY.ordinal()] = sample.aridity();
            values[TerrainBiomeCondition.Variable.TREE_MOISTURE.ordinal()] = sample.treeMoisture();
            values[TerrainBiomeCondition.Variable.TREE_COVERAGE.ordinal()] = sample.treeCoverage();
            values[TerrainBiomeCondition.Variable.SPARSITY.ordinal()] = sample.sparsity();
            values[TerrainBiomeCondition.Variable.SLOPE.ordinal()] = sample.slope();
            values[TerrainBiomeCondition.Variable.GROWING_SEASON_DAYS.ordinal()] = sample.growingSeasonDays();

            boolean[] flags = climateFlags;
            flags[TerrainBiomeCondition.Variable.OCEAN.ordinal()] = sample.ocean();
            flags[TerrainBiomeCondition.Variable.SNOWY.ordinal()] = sample.snowy();
            flags[TerrainBiomeCondition.Variable.BARE_SLOPE.ordinal()] = sample.bareSlope();
            flags[TerrainBiomeCondition.Variable.MOUNTAIN.ordinal()] = sample.mountain();
            flags[TerrainBiomeCondition.Variable.LOWLAND.ordinal()] = sample.lowland();
        }

        /** Loads the six noise fields rules may gate on. */
        public void setNoise(TerrainBiomeNoiseSample noise) {
            setNoise(noise.variantNoise(), noise.cherryNoise(), noise.paleNoise(), noise.clearingNoise(),
                    noise.flowerNoise(), noise.regionNoise());
        }

        /** Field-wise variant, so a per-pixel loop need not build a {@link TerrainBiomeNoiseSample}. */
        public void setNoise(float variantNoise, float cherryNoise, float paleNoise, float clearingNoise,
                             float flowerNoise, float regionNoise) {
            float[] values = noiseValues;
            values[TerrainBiomeCondition.Variable.VARIANT_NOISE.ordinal()] = variantNoise;
            values[TerrainBiomeCondition.Variable.CHERRY_NOISE.ordinal()] = cherryNoise;
            values[TerrainBiomeCondition.Variable.PALE_NOISE.ordinal()] = paleNoise;
            values[TerrainBiomeCondition.Variable.CLEARING_NOISE.ordinal()] = clearingNoise;
            values[TerrainBiomeCondition.Variable.FLOWER_NOISE.ordinal()] = flowerNoise;
            values[TerrainBiomeCondition.Variable.REGION_NOISE.ordinal()] = regionNoise;
            deferred = 0;
        }

        /**
         * Marks the given noise variables as sampled on first use at {@code (worldX, worldZ)}.
         *
         * <p>Noise conditions gate a handful of rare biomes and are only reached once a rule's
         * climate conditions have all passed, so at most pixels those fields are never read at
         * all. Sampling them eagerly meant evaluating their octaves for every generated block.
         * The value a rule sees is the same either way -- these fields are pure functions of the
         * position.
         */
        public void deferNoise(NoiseSampler sampler, float worldX, float worldZ,
                               TerrainBiomeCondition.Variable... variables) {
            this.sampler = sampler;
            this.worldX = worldX;
            this.worldZ = worldZ;
            int mask = 0;
            for (TerrainBiomeCondition.Variable variable : variables) mask |= 1 << variable.ordinal();
            this.deferred = mask;
        }

        private float noise(int variableOrdinal) {
            if ((deferred & (1 << variableOrdinal)) != 0) {
                deferred &= ~(1 << variableOrdinal);
                noiseValues[variableOrdinal] = sampler.sample(VARIABLES[variableOrdinal], worldX, worldZ);
            }
            return noiseValues[variableOrdinal];
        }
    }

    /** Every zone's compiled rules plus the zone-name lookup, built and published together. */
    private record Compiled(Map<String, Integer> zoneIds, CompiledZone[] zones) {}

    /** Temperature index covering -60..60 C in 1-degree buckets; the ends absorb everything beyond. */
    private static final float BUCKET_MIN_C = -60f;
    private static final int BUCKET_COUNT = 120;

    /**
     * One zone's rules in struct-of-arrays form. Conditions of all rules live in shared arrays;
     * rule {@code r} owns the slice {@code [start[r], start[r + 1])}.
     */
    private static final class CompiledZone {
        final int ruleCount;
        final short[] biomeIndex;
        final float[] rarity;
        final boolean[] override;

        final int[] climateStart;
        final byte[] climateVar;
        final byte[] climateOp;
        final byte[] climateKind;
        final float[] climateValue;
        final float[] climateValue2;

        final int[] noiseStart;
        final byte[] noiseVar;
        final byte[] noiseOp;
        final float[] noiseValue;
        final float[] noiseValue2;

        /**
         * Rules that can possibly match at a given temperature, in rule order.
         *
         * <p>Three quarters of the catalog's rules name a temperature range, and a zone can hold
         * over a hundred rules, so without this every pixel evaluates the leading condition of
         * every rule in its zone just to reject it. A rule appears in a bucket whenever its
         * temperature interval overlaps that bucket, so skipping the others cannot change which
         * rule matches -- they would have failed their own temperature test.
         */
        final int[][] rulesByTemperature;

        CompiledZone(List<RuleEntry> entries) {
            ruleCount = entries.size();
            biomeIndex = new short[ruleCount];
            rarity = new float[ruleCount];
            override = new boolean[ruleCount];
            climateStart = new int[ruleCount + 1];
            noiseStart = new int[ruleCount + 1];

            int climateCount = 0;
            int noiseCount = 0;
            for (RuleEntry entry : entries) {
                climateCount += entry.rule.conditions().size();
                noiseCount += entry.rule.noiseConditions().size();
            }
            climateVar = new byte[climateCount];
            climateOp = new byte[climateCount];
            climateKind = new byte[climateCount];
            climateValue = new float[climateCount];
            climateValue2 = new float[climateCount];
            noiseVar = new byte[noiseCount];
            noiseOp = new byte[noiseCount];
            noiseValue = new float[noiseCount];
            noiseValue2 = new float[noiseCount];

            int climateAt = 0;
            int noiseAt = 0;
            for (int rule = 0; rule < ruleCount; rule++) {
                RuleEntry entry = entries.get(rule);
                biomeIndex[rule] = entry.index;
                rarity[rule] = entry.rule.rarity();
                override[rule] = entry.rule.isOverride();

                climateStart[rule] = climateAt;
                for (TerrainBiomeCondition condition : entry.rule.conditions()) {
                    climateVar[climateAt] = (byte) condition.resolvedVariable().ordinal();
                    climateOp[climateAt] = operatorCode(condition);
                    Boolean expected = condition.boolValue();
                    climateKind[climateAt] = expected == null
                            ? KIND_NUMERIC
                            : (expected ? KIND_BOOL_TRUE : KIND_BOOL_FALSE);
                    climateValue[climateAt] = condition.numericValue();
                    climateValue2[climateAt] = condition.numericValue2();
                    climateAt++;
                }

                noiseStart[rule] = noiseAt;
                // Noise conditions are always compared numerically: the object form's
                // evaluateNoiseCondition switches on the operator and never consults boolValue.
                for (TerrainBiomeCondition condition : entry.rule.noiseConditions()) {
                    noiseVar[noiseAt] = (byte) condition.resolvedVariable().ordinal();
                    noiseOp[noiseAt] = operatorCode(condition);
                    noiseValue[noiseAt] = condition.numericValue();
                    noiseValue2[noiseAt] = condition.numericValue2();
                    noiseAt++;
                }
            }
            climateStart[ruleCount] = climateAt;
            noiseStart[ruleCount] = noiseAt;
            rulesByTemperature = buildTemperatureIndex(entries);
        }

        private static int[][] buildTemperatureIndex(List<RuleEntry> entries) {
            int ruleCount = entries.size();
            float[] lowest = new float[ruleCount];
            float[] highest = new float[ruleCount];
            for (int rule = 0; rule < ruleCount; rule++) {
                float low = Float.NEGATIVE_INFINITY;
                float high = Float.POSITIVE_INFINITY;
                for (TerrainBiomeCondition condition : entries.get(rule).rule.conditions()) {
                    if (condition.resolvedVariable() != TerrainBiomeCondition.Variable.TEMPERATURE_C
                            || condition.boolValue() != null || condition.operator() == null) {
                        continue;
                    }
                    float value = condition.numericValue();
                    switch (condition.operator()) {
                        case EQ -> { low = Math.max(low, value); high = Math.min(high, value); }
                        case GT, GTE -> low = Math.max(low, value);
                        case LT, LTE -> high = Math.min(high, value);
                        case BETWEEN -> {
                            low = Math.max(low, value);
                            high = Math.min(high, condition.numericValue2());
                        }
                    }
                }
                lowest[rule] = low;
                highest[rule] = high;
            }

            int[][] buckets = new int[BUCKET_COUNT][];
            int[] scratch = new int[ruleCount];
            for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
                // The first and last buckets stand for everything past the indexed range.
                float bucketLow = bucket == 0 ? Float.NEGATIVE_INFINITY : BUCKET_MIN_C + bucket;
                float bucketHigh = bucket == BUCKET_COUNT - 1
                        ? Float.POSITIVE_INFINITY : BUCKET_MIN_C + bucket + 1;
                int count = 0;
                for (int rule = 0; rule < ruleCount; rule++) {
                    if (lowest[rule] <= bucketHigh && highest[rule] >= bucketLow) {
                        scratch[count++] = rule;
                    }
                }
                buckets[bucket] = java.util.Arrays.copyOf(scratch, count);
            }
            return buckets;
        }

        /** Candidate rules for a pixel's temperature. A NaN temperature lands in bucket 0, whose
         *  rules are exactly those that could still match when every temperature test fails. */
        int[] candidates(float temperatureC) {
            int bucket = (int) (temperatureC - BUCKET_MIN_C);
            if (bucket < 0) bucket = 0;
            if (bucket >= BUCKET_COUNT) bucket = BUCKET_COUNT - 1;
            return rulesByTemperature[bucket];
        }

        private static byte operatorCode(TerrainBiomeCondition condition) {
            TerrainBiomeCondition.Operator operator = condition.operator();
            return operator == null ? OP_NEVER : (byte) operator.ordinal();
        }

        boolean matches(int rule, Scratch scratch) {
            float[] values = scratch.climateValues;
            boolean[] flags = scratch.climateFlags;
            for (int at = climateStart[rule], end = climateStart[rule + 1]; at < end; at++) {
                byte kind = climateKind[at];
                if (kind == KIND_NUMERIC) {
                    if (!compare(values[climateVar[at]], climateOp[at], climateValue[at], climateValue2[at])) {
                        return false;
                    }
                } else if (flags[climateVar[at]] != (kind == KIND_BOOL_TRUE)) {
                    return false;
                }
            }
            for (int at = noiseStart[rule], end = noiseStart[rule + 1]; at < end; at++) {
                if (!compare(scratch.noise(noiseVar[at]), noiseOp[at], noiseValue[at], noiseValue2[at])) {
                    return false;
                }
            }
            return true;
        }

        /** NaN inputs fail every comparison, matching the object form for absent variables. */
        private static boolean compare(float actual, byte operator, float value, float value2) {
            return switch (operator) {
                case 0 -> actual == value;                          // EQ
                case 1 -> actual > value;                           // GT
                case 2 -> actual >= value;                          // GTE
                case 3 -> actual < value;                           // LT
                case 4 -> actual <= value;                          // LTE
                case 5 -> actual >= value && actual <= value2;      // BETWEEN
                default -> false;
            };
        }
    }

    private Compiled compiled() {
        Compiled current = compiled;
        if (current != null) return current;
        synchronized (this) {
            if (compiled != null) return compiled;

            Map<String, List<RuleEntry>> byZone = new LinkedHashMap<>();
            for (TerrainBiomeSettlement settlement : registry.all()) {
                for (TerrainBiomeRule rule : settlement.rules()) {
                    if (rule.rarity() <= 0f) continue;  // weightless rules can never be selected
                    if (!rule.isAvailable()) continue;  // rule belongs to an optional mod that isn't installed
                    byZone.computeIfAbsent(rule.zone(), zone -> new ArrayList<>())
                            .add(new RuleEntry(settlement.index(), rule));
                }
            }

            Map<String, Integer> zoneIds = new HashMap<>();
            CompiledZone[] zones = new CompiledZone[byZone.size()];
            int nextId = 0;
            for (Map.Entry<String, List<RuleEntry>> entry : byZone.entrySet()) {
                zones[nextId] = new CompiledZone(entry.getValue());
                zoneIds.put(entry.getKey(), nextId);
                nextId++;
            }
            compiled = new Compiled(zoneIds, zones);
            return compiled;
        }
    }

    /**
     * Resolves a zone name to the id {@link #select(int, Scratch, short, float, float)} takes.
     * Callers classifying many pixels resolve their zones once instead of hashing a string per
     * pixel. Returns -1 for a zone no rule uses, which selects nothing.
     */
    public int zoneId(String zone) {
        Integer id = compiled().zoneIds().get(zone);
        return id == null ? -1 : id;
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
        Scratch scratch = new Scratch();
        scratch.setClimate(sample);
        scratch.setNoise(noiseValues);
        return select(zoneId(zone), scratch, defaultIndex, worldX, worldZ);
    }

    /**
     * Hot-path selection: the zone is already an id and the pixel's variables are already loaded
     * into {@code scratch}. Semantically identical to
     * {@link #select(String, TerrainClimateSample, TerrainBiomeNoiseSample, short, float, float)}.
     */
    public short select(int zoneId, Scratch scratch, short defaultIndex, float worldX, float worldZ) {
        if (zoneId < 0) return defaultIndex;
        CompiledZone zone = compiled().zones()[zoneId];

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

        int[] candidates = zone.candidates(
                scratch.climateValues[TerrainBiomeCondition.Variable.TEMPERATURE_C.ordinal()]);
        for (int candidate = 0, candidateCount = candidates.length; candidate < candidateCount; candidate++) {
            int rule = candidates[candidate];
            if (!zone.matches(rule, scratch)) continue;

            matches++;
            if (matches == 1) {
                firstIndex = zone.biomeIndex[rule];
                firstRarity = zone.rarity[rule];
                firstOverride = zone.override[rule];
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

            short index = zone.biomeIndex[rule];
            float key = selectionKey(index, zone.rarity[rule], worldX, worldZ);
            if (zone.override[rule]) {
                if (key > bestOverrideKey) {
                    bestOverrideKey = key;
                    overrideWinner = index;
                }
            } else if (key > bestKey) {
                bestKey = key;
                winner = index;
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
