package com.github.xandergos.terraindiffusionmc.biome;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeCondition.Variable;

/**
 * For an already-configured biome, works out which of the terrain explorer's coarse-map filter
 * channels (Elev/Temp/T std/Precip/Precip CV) can be set so the highlighted area is a reasonable
 * search radius for where that biome's {@link TerrainBiomeRule}s can win a pixel. Used by
 * {@code ExplorerServer}'s {@code /api/biomes/candidate_filters} handler, which the explorer UI's
 * "Biome Config" panel calls when the user selects a biome that already has rules, to auto-fill
 * the sidebar Filter sliders instead of the user guessing ranges by hand.
 *
 * <h2>The soundness contract</h2>
 * <p>Every bound this class emits is <b>necessary but not sufficient</b>: a pixel outside the
 * highlighted region provably cannot satisfy the rule, but plenty of pixels inside it will not
 * either. Whenever a derivation could go either way, it is deliberately loosened, because showing
 * too much area is a mild annoyance and hiding real candidate area makes the feature actively
 * misleading. Nothing here may ever tighten a bound past what the rule strictly implies.</p>
 *
 * <h2>Why per-zone, and why an envelope rather than an exact region</h2>
 * <p>A biome's rules are grouped by {@link TerrainBiomeRule#zone()} because different zones (e.g.
 * {@code taiga}'s {@code lowland} vs {@code mountain} rules) can have very different climate
 * windows -- merging them would produce a filter box wide enough to be useless. Within one zone
 * group, a biome can still have many rules (a noise-gated variant family like
 * {@code cherry_grove}'s ~48 rules is a real example), each an AND of conditions. For a given
 * channel, this takes the AND (intersection) of everything that constrains it *within* each rule,
 * then the OR (union) *across* rules in the zone -- matching how {@link BiomeRuleEngine} actually
 * picks a winner (any one matching rule is enough). If any rule in the zone leaves a channel
 * completely unconstrained, the zone's true reachable range for that channel is unbounded, so no
 * filter is emitted for it at all.</p>
 *
 * <h2>Derived variables are inverted, not discarded</h2>
 * <p>Only 5 of the 17 climate variables are coarse-map channels, but several of the rest are
 * <i>pure deterministic functions</i> of those channels inside
 * {@code BiomeClassifier.classifyPixel}, so a condition on them still implies a real bound on a
 * channel. This class inverts that chain:</p>
 * <pre>
 *   tEff            = max(0, temperatureC + 0.5 * temperatureSeasonality/100)
 *   pet             = max(250, 250 + 25*tEff + 0.7*tEff^2)
 *   aridity         = precipitationMm / max(1, pet)
 *   moisture        = treeMoisture = aridity * seasonPenalty      seasonPenalty in [0.65, 1]
 *   effTreeMoisture = treeMoisture * gsFactor                     gsFactor      in [0, 1]
 *   treeCoverage    = bucket(effTreeMoisture) in {0, 0.35, 0.62, 0.85, 1.00}
 * </pre>
 * <p>Because {@code pet} is monotonically increasing in {@code tEff}, a lower bound on any of
 * {@code aridity}/{@code moisture}/{@code treeMoisture}/{@code treeCoverage} becomes a lower bound
 * on precipitation once evaluated at the rule's <i>smallest</i> reachable {@code pet}. Upper bounds
 * work symmetrically at the largest reachable {@code pet} -- except from {@code treeCoverage}, where
 * {@code gsFactor} can be 0 (an arbitrarily wet but too-cold pixel still has zero tree cover), so a
 * {@code treeCoverage} ceiling implies nothing about precipitation and is not used.</p>
 *
 * <h2>What is still only a caveat</h2>
 * <p>{@code slope} and {@code bareSlope} come from a spatial elevation gradient, and every
 * {@code noiseConditions} entry comes from an independent noise field; neither has any coarse-map
 * proxy. {@code growingSeasonDays} is temperature-derived but only bounds temperature jointly with
 * {@code temperatureSeasonality}, which is too weak to be worth a slider. Those are surfaced as
 * human-readable caveat strings so the UI can tell the user the highlighted area is necessary but
 * not sufficient.</p>
 */
public final class BiomeCandidateFilterCalculator {

    /** Cap on how many distinct caveat strings a single zone group reports, to keep a 48-rule
     * variant family's response readable instead of dumping near-duplicate noise-gate lines. */
    private static final int MAX_CAVEATS = 10;

    // ---- Coarse-map channel indices (must match ExplorerServer.CHANNEL_NAMES) ----
    private static final int CH_ELEVATION = 0;
    private static final int CH_TEMPERATURE = 2;
    private static final int CH_TEMPERATURE_SEASONALITY = 3;
    private static final int CH_PRECIPITATION = 4;
    private static final int CH_PRECIPITATION_CV = 5;

    private static final Set<Variable> FILTERABLE = EnumSet.of(
            Variable.ELEVATION_M, Variable.TEMPERATURE_C, Variable.TEMPERATURE_SEASONALITY,
            Variable.PRECIPITATION_MM, Variable.PRECIPITATION_CV);

    /** Maps a directly-filterable climate variable to its coarse-map channel index. */
    private static final Map<Variable, Integer> VARIABLE_TO_CHANNEL = new EnumMap<>(Map.of(
            Variable.ELEVATION_M, CH_ELEVATION,
            Variable.TEMPERATURE_C, CH_TEMPERATURE,
            Variable.TEMPERATURE_SEASONALITY, CH_TEMPERATURE_SEASONALITY,
            Variable.PRECIPITATION_MM, CH_PRECIPITATION,
            Variable.PRECIPITATION_CV, CH_PRECIPITATION_CV));

    /** Variables whose value is a deterministic function of the coarse channels and can therefore
     * be inverted into a precipitation bound (see class docs). */
    private static final Set<Variable> MOISTURE_FAMILY = EnumSet.of(
            Variable.ARIDITY, Variable.MOISTURE, Variable.TREE_MOISTURE,
            Variable.TREE_COVERAGE, Variable.SPARSITY);

    /**
     * {@code classifyPixel} multiplies the map's precipitation by
     * {@code 1 + 0.2*precipNoise} with {@code precipNoise} in roughly [-1, 1], so the value a rule
     * sees can exceed the coarse-map value by up to this factor. Precipitation bounds are divided
     * (lower) / multiplied (upper) by it so the emitted range stays sound against the map.
     */
    private static final float PRECIP_NOISE_MAX_FACTOR = 1.2f;

    /**
     * {@code classifyPixel} adds {@code 0.4*tempNoiseCoarse + 0.2*tempNoiseFine} (each in roughly
     * [-1, 1]) to the map temperature, so temperature bounds are widened by this many degrees.
     * The much larger elevation lapse-rate correction is deliberately <i>not</i> compensated for --
     * see {@link #TEMPERATURE_IS_APPROXIMATE}.
     */
    private static final float TEMP_NOISE_SLACK_C = 0.6f;

    /**
     * Coarse pixels average a large block of terrain, so a coarse elevation can sit well below the
     * fine-resolution elevation of the pixels inside it (and vice versa). Zone-derived elevation
     * bounds are padded by this much so a coarse cell that merely *contains* qualifying terrain
     * still shows up.
     */
    private static final float ZONE_ELEVATION_SLACK_M = 500f;

    /** {@code classifyPixel}'s mountain-zone cutoff: {@code altM > 2500}. */
    private static final float MOUNTAIN_ELEVATION_M = 2500f;

    /** {@code classifyPixel}'s {@code lowland} boolean flag cutoff: {@code altM < 200}. */
    private static final float LOWLAND_FLAG_ELEVATION_M = 200f;

    /** {@code hasSnow} requires {@code precip > 150} outright. */
    private static final float SNOWY_MIN_PRECIP_MM = 150f;

    /**
     * {@code hasSnow} needs {@code temp + snowNoise} below -0.5 at best, and
     * {@code snowNoise = 3*coarse + 2*fine} reaches about +5, so no pixel warmer than this can
     * ever be snowy.
     */
    private static final float SNOWY_MAX_TEMP_C = 4.5f;

    /** seasonPenalty = 1 - 0.35*clamp01(pCV/100), so it never drops below this. */
    private static final float MIN_SEASON_PENALTY = 0.65f;

    /**
     * Shown to the user whenever a temperature bound is emitted. Coarse channel 2 is the
     * <i>un-lapsed</i> base temperature, while a rule's {@code temperatureC} has already had the
     * per-pixel elevation lapse-rate correction applied, so the two diverge by tens of degrees at
     * altitude. Compensating for that would widen the filter to the entire scale and make it
     * useless, so the divergence is disclosed instead.
     */
    private static final String TEMPERATURE_IS_APPROXIMATE =
            "the map's Temp channel is the un-lapsed base temperature, so it runs warm relative to "
                    + "the rule's temperatureC at high elevation";

    private BiomeCandidateFilterCalculator() {
    }

    /** One zone's worth of merged candidate-filter data for a biome. */
    public record ZoneCandidate(String zone, int ruleCount, float minRarity, float maxRarity,
                                 Map<Integer, float[]> channelFilters, List<String> caveats,
                                 boolean caveatsTruncated) {
    }

    /**
     * Computes one {@link ZoneCandidate} per distinct zone the settlement has rules in, in the
     * order those zones first appear among {@code settlement.rules()}.
     */
    public static List<ZoneCandidate> compute(TerrainBiomeSettlement settlement) {
        Map<String, List<TerrainBiomeRule>> byZone = new LinkedHashMap<>();
        for (TerrainBiomeRule rule : settlement.rules()) {
            byZone.computeIfAbsent(rule.zone(), z -> new ArrayList<>()).add(rule);
        }
        List<ZoneCandidate> result = new ArrayList<>();
        for (Map.Entry<String, List<TerrainBiomeRule>> entry : byZone.entrySet()) {
            result.add(computeZone(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /**
     * A mutable per-channel interval set, built up one rule at a time. Absent channels are
     * unconstrained; {@link Float#NEGATIVE_INFINITY}/{@link Float#POSITIVE_INFINITY} endpoints mean
     * that side is open.
     */
    private static final class Bounds {
        private final Map<Integer, float[]> byChannel = new LinkedHashMap<>();

        void atLeast(int channel, float value) {
            if (Float.isNaN(value) || value == Float.NEGATIVE_INFINITY) return;
            float[] range = range(channel);
            range[0] = Math.max(range[0], value);
        }

        void atMost(int channel, float value) {
            if (Float.isNaN(value) || value == Float.POSITIVE_INFINITY) return;
            float[] range = range(channel);
            range[1] = Math.min(range[1], value);
        }

        private float[] range(int channel) {
            return byChannel.computeIfAbsent(channel,
                    c -> new float[]{Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY});
        }

        float min(int channel) {
            float[] range = byChannel.get(channel);
            return range == null ? Float.NEGATIVE_INFINITY : range[0];
        }

        float max(int channel) {
            float[] range = byChannel.get(channel);
            return range == null ? Float.POSITIVE_INFINITY : range[1];
        }

        boolean constrains(int channel) {
            float[] range = byChannel.get(channel);
            return range != null
                    && (range[0] != Float.NEGATIVE_INFINITY || range[1] != Float.POSITIVE_INFINITY);
        }

        /** True when this rule's conditions contradict each other outright. */
        boolean isEmpty() {
            for (float[] range : byChannel.values()) {
                if (range[0] > range[1]) return true;
            }
            return false;
        }
    }

    private static ZoneCandidate computeZone(String zone, List<TerrainBiomeRule> rules) {
        List<Bounds> perRule = new ArrayList<>(rules.size());
        for (TerrainBiomeRule rule : rules) {
            perRule.add(boundsForRule(zone, rule));
        }

        Map<Integer, float[]> channelFilters = new LinkedHashMap<>();
        for (int channel : new int[]{CH_ELEVATION, CH_TEMPERATURE, CH_TEMPERATURE_SEASONALITY,
                CH_PRECIPITATION, CH_PRECIPITATION_CV}) {
            float[] envelope = unionAcrossRules(perRule, channel);
            if (envelope != null) {
                channelFilters.put(channel, envelope);
            }
        }

        float minRarity = Float.MAX_VALUE;
        float maxRarity = 0f;
        Set<String> caveats = new LinkedHashSet<>();
        for (TerrainBiomeRule rule : rules) {
            minRarity = Math.min(minRarity, rule.rarity());
            maxRarity = Math.max(maxRarity, rule.rarity());
            for (TerrainBiomeCondition condition : rule.conditions()) {
                if (isOpaque(condition.resolvedVariable())) {
                    caveats.add(describe(condition));
                }
            }
            for (TerrainBiomeCondition condition : rule.noiseConditions()) {
                caveats.add(describe(condition));
            }
        }
        if ("beach".equals(zone)) {
            caveats.add("beach zone: only pixels the classifier flags as coastline, which the "
                    + "coarse map cannot resolve");
        }
        if (channelFilters.containsKey(CH_TEMPERATURE)) {
            caveats.add(TEMPERATURE_IS_APPROXIMATE);
        }

        List<String> caveatList = new ArrayList<>(caveats);
        boolean truncated = caveatList.size() > MAX_CAVEATS;
        if (truncated) {
            caveatList = new ArrayList<>(caveatList.subList(0, MAX_CAVEATS));
        }

        return new ZoneCandidate(zone, rules.size(), minRarity, maxRarity, channelFilters,
                caveatList, truncated);
    }

    /**
     * A variable that contributes no channel bound at all, so a condition on it has to be reported
     * as a caveat instead. The moisture family is excluded because it *is* inverted into a
     * precipitation bound, but a {@code treeCoverage} ceiling still adds nothing (see class docs),
     * so those conditions remain worth mentioning -- the union step decides that, not this.
     */
    private static boolean isOpaque(Variable variable) {
        return !FILTERABLE.contains(variable)
                && !MOISTURE_FAMILY.contains(variable)
                && variable != Variable.OCEAN
                && variable != Variable.MOUNTAIN
                && variable != Variable.LOWLAND
                && variable != Variable.SNOWY;
    }

    /**
     * Every coarse-map bound a single rule implies: its zone, its direct conditions on filterable
     * variables, the boolean flags that are pure elevation tests, and the moisture family inverted
     * through the PET chain.
     */
    private static Bounds boundsForRule(String zone, TerrainBiomeRule rule) {
        Bounds bounds = new Bounds();

        applyZoneBounds(zone, bounds);

        // Pass 1: everything that directly constrains a channel. Temperature and seasonality must
        // be known before the moisture family can be inverted, since the PET they divide by
        // depends on both.
        for (TerrainBiomeCondition condition : rule.conditions()) {
            Variable variable = condition.resolvedVariable();
            Integer channel = VARIABLE_TO_CHANNEL.get(variable);
            if (channel != null) {
                applyNumeric(bounds, channel, condition);
            } else if (condition.boolValue() != null) {
                applyBoolean(bounds, variable, condition.boolValue());
            }
        }
        if (bounds.constrains(CH_TEMPERATURE)) {
            // Widen for the per-pixel temperature noise the coarse map does not carry.
            bounds.atLeast(CH_TEMPERATURE, bounds.min(CH_TEMPERATURE) - TEMP_NOISE_SLACK_C);
            bounds.atMost(CH_TEMPERATURE, bounds.max(CH_TEMPERATURE) + TEMP_NOISE_SLACK_C);
        }

        // Pass 2: invert the moisture family against the temperature window pass 1 established.
        float petMin = petAt(minTEff(bounds));
        float petMax = petAt(maxTEff(bounds));
        for (TerrainBiomeCondition condition : rule.conditions()) {
            if (MOISTURE_FAMILY.contains(condition.resolvedVariable())) {
                applyMoistureFamily(bounds, condition, petMin, petMax);
            }
        }

        return bounds;
    }

    /**
     * {@code classifyPixel} dispatches zones purely by elevation, so the zone name itself is a real
     * constraint -- this is what keeps a {@code lowland} biome from highlighting the whole ocean.
     * {@code beach} is left unconstrained on purpose: it is a spatial coastline test whose pixels
     * sit inside coarse cells of essentially any average elevation, and a naive [0, 18] window
     * would hide almost all of the real shoreline.
     */
    private static void applyZoneBounds(String zone, Bounds bounds) {
        switch (zone) {
            case "ocean" -> bounds.atMost(CH_ELEVATION, ZONE_ELEVATION_SLACK_M);
            case "mountain" -> bounds.atLeast(CH_ELEVATION,
                    MOUNTAIN_ELEVATION_M - ZONE_ELEVATION_SLACK_M);
            case "lowland", "bareSlope" -> {
                bounds.atLeast(CH_ELEVATION, -ZONE_ELEVATION_SLACK_M);
                bounds.atMost(CH_ELEVATION, MOUNTAIN_ELEVATION_M + ZONE_ELEVATION_SLACK_M);
            }
            default -> { /* beach, or an unknown custom zone: no elevation implication */ }
        }
    }

    private static void applyNumeric(Bounds bounds, int channel, TerrainBiomeCondition condition) {
        if (condition.boolValue() != null) return;
        switch (condition.operator()) {
            case EQ -> {
                bounds.atLeast(channel, condition.numericValue());
                bounds.atMost(channel, condition.numericValue());
            }
            case GT, GTE -> bounds.atLeast(channel, condition.numericValue());
            case LT, LTE -> bounds.atMost(channel, condition.numericValue());
            case BETWEEN -> {
                bounds.atLeast(channel, condition.numericValue());
                bounds.atMost(channel, condition.numericValue2());
            }
        }
    }

    /**
     * The boolean flags that {@code classifyPixel} derives from elevation alone, plus {@code snowy}
     * (which requires real precipitation and rules out warm pixels outright). {@code bareSlope}
     * is gradient-based and contributes nothing.
     */
    private static void applyBoolean(Bounds bounds, Variable variable, boolean expected) {
        switch (variable) {
            case OCEAN -> {
                if (expected) {
                    bounds.atMost(CH_ELEVATION, ZONE_ELEVATION_SLACK_M);
                } else {
                    bounds.atLeast(CH_ELEVATION, -ZONE_ELEVATION_SLACK_M);
                }
            }
            case MOUNTAIN -> {
                if (expected) {
                    bounds.atLeast(CH_ELEVATION, MOUNTAIN_ELEVATION_M - ZONE_ELEVATION_SLACK_M);
                } else {
                    bounds.atMost(CH_ELEVATION, MOUNTAIN_ELEVATION_M + ZONE_ELEVATION_SLACK_M);
                }
            }
            case LOWLAND -> {
                if (expected) {
                    bounds.atMost(CH_ELEVATION, LOWLAND_FLAG_ELEVATION_M + ZONE_ELEVATION_SLACK_M);
                } else {
                    bounds.atLeast(CH_ELEVATION, -ZONE_ELEVATION_SLACK_M);
                }
            }
            case SNOWY -> {
                // Only snowy == true says anything; a non-snowy pixel can be any climate at all.
                if (expected) {
                    bounds.atLeast(CH_PRECIPITATION, SNOWY_MIN_PRECIP_MM / PRECIP_NOISE_MAX_FACTOR);
                    bounds.atMost(CH_TEMPERATURE, SNOWY_MAX_TEMP_C);
                }
            }
            default -> { /* bareSlope: no coarse-map implication */ }
        }
    }

    /**
     * Turns a condition on {@code aridity}/{@code moisture}/{@code treeMoisture}/
     * {@code treeCoverage}/{@code sparsity} into a precipitation bound, by dividing out every
     * multiplier between it and {@code precipitationMm} at the multiplier's most permissive value.
     */
    private static void applyMoistureFamily(Bounds bounds, TerrainBiomeCondition condition,
                                             float petMin, float petMax) {
        Variable variable = condition.resolvedVariable();
        float lo = Float.NEGATIVE_INFINITY;
        float hi = Float.POSITIVE_INFINITY;
        switch (condition.operator()) {
            case EQ -> {
                lo = condition.numericValue();
                hi = condition.numericValue();
            }
            case GT, GTE -> lo = condition.numericValue();
            case LT, LTE -> hi = condition.numericValue();
            case BETWEEN -> {
                lo = condition.numericValue();
                hi = condition.numericValue2();
            }
        }

        // sparsity = 1 - treeCoverage, so its bounds invert into treeCoverage bounds.
        if (variable == Variable.SPARSITY) {
            float tcLo = (hi == Float.POSITIVE_INFINITY) ? Float.NEGATIVE_INFINITY : 1f - hi;
            float tcHi = (lo == Float.NEGATIVE_INFINITY) ? Float.POSITIVE_INFINITY : 1f - lo;
            lo = tcLo;
            hi = tcHi;
            variable = Variable.TREE_COVERAGE;
        }

        if (variable == Variable.TREE_COVERAGE) {
            // A treeCoverage floor pins the pixel to a discrete bucket, which pins
            // effTreeMoisture; a treeCoverage ceiling implies nothing, because gsFactor can be 0.
            float effFloor = effTreeMoistureFloorFor(lo);
            if (Float.isNaN(effFloor)) return;
            lo = effFloor;
            hi = Float.POSITIVE_INFINITY;
        }

        // Every multiplier between precipitation and these variables is <= 1, so a lower bound
        // passes straight through to aridity untouched. An upper bound has to be divided by the
        // smallest each multiplier can be; only seasonPenalty has a non-zero floor, which is why
        // treeCoverage ceilings were already discarded above.
        float upperSlack = (variable == Variable.ARIDITY) ? 1f : (1f / MIN_SEASON_PENALTY);

        if (lo > 0f && lo != Float.NEGATIVE_INFINITY && petMin > 0f) {
            bounds.atLeast(CH_PRECIPITATION, lo * petMin / PRECIP_NOISE_MAX_FACTOR);
        }
        if (hi >= 0f && hi != Float.POSITIVE_INFINITY && petMax != Float.POSITIVE_INFINITY) {
            bounds.atMost(CH_PRECIPITATION,
                    hi * upperSlack * petMax * PRECIP_NOISE_MAX_FACTOR);
        }
    }

    /**
     * The smallest {@code effTreeMoisture} consistent with {@code treeCoverage >= floor}, using
     * {@code classifyPixel}'s exact bucket edges. Returns NaN when the floor implies nothing (no
     * floor at all, or one at/below the bare bucket).
     */
    private static float effTreeMoistureFloorFor(float floor) {
        if (floor == Float.NEGATIVE_INFINITY || floor <= 0f) return Float.NaN;
        if (floor <= 0.35f) return 0.2f;   // treesSparse
        if (floor <= 0.62f) return 0.5f;   // treesForest
        if (floor <= 0.85f) return 0.8f;   // treesDense
        return 1.3f;                       // treesRainforest
    }

    /** Smallest {@code tEff = max(0, temp + 0.5*tSeason/100)} the rule's window allows. */
    private static float minTEff(Bounds bounds) {
        float temp = bounds.min(CH_TEMPERATURE);
        float seasonality = bounds.min(CH_TEMPERATURE_SEASONALITY);
        if (temp == Float.NEGATIVE_INFINITY) temp = 0f;
        // temperatureSeasonality is a standard deviation, so it is never negative.
        if (seasonality == Float.NEGATIVE_INFINITY || seasonality < 0f) seasonality = 0f;
        return Math.max(0f, temp + 0.5f * seasonality / 100f);
    }

    /** Largest {@code tEff} the rule's window allows, or +inf when either side is open. */
    private static float maxTEff(Bounds bounds) {
        float temp = bounds.max(CH_TEMPERATURE);
        float seasonality = bounds.max(CH_TEMPERATURE_SEASONALITY);
        if (temp == Float.POSITIVE_INFINITY || seasonality == Float.POSITIVE_INFINITY) {
            return Float.POSITIVE_INFINITY;
        }
        return Math.max(0f, temp + 0.5f * seasonality / 100f);
    }

    /** {@code classifyPixel}'s potential-evapotranspiration curve. Increasing in {@code tEff}. */
    private static float petAt(float tEff) {
        if (tEff == Float.POSITIVE_INFINITY) return Float.POSITIVE_INFINITY;
        return Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
    }

    /**
     * Unions each rule's interval for one channel. Returns {@code null} when the channel is
     * unbounded for the zone as a whole -- either no rule constrains it, or at least one rule
     * leaves it fully open, in which case a filter would hide real candidate area.
     */
    private static float[] unionAcrossRules(List<Bounds> perRule, int channel) {
        float envMin = Float.POSITIVE_INFINITY;
        float envMax = Float.NEGATIVE_INFINITY;
        boolean any = false;
        for (Bounds bounds : perRule) {
            // A rule whose own conditions contradict each other can never match, so it contributes
            // no reachable area and must not widen the envelope.
            if (bounds.isEmpty()) continue;
            if (!bounds.constrains(channel)) return null;
            any = true;
            envMin = Math.min(envMin, bounds.min(channel));
            envMax = Math.max(envMax, bounds.max(channel));
        }
        if (!any) return null;
        if (envMin > envMax) return null;
        if (envMin == Float.NEGATIVE_INFINITY && envMax == Float.POSITIVE_INFINITY) return null;
        return new float[]{envMin, envMax};
    }

    private static String describe(TerrainBiomeCondition condition) {
        String label = condition.variable();
        if (condition.boolValue() != null) {
            return label + " = " + condition.boolValue();
        }
        return switch (condition.operator()) {
            case EQ -> label + " = " + fmt(condition.numericValue());
            case GT -> label + " > " + fmt(condition.numericValue());
            case GTE -> label + " ≥ " + fmt(condition.numericValue());
            case LT -> label + " < " + fmt(condition.numericValue());
            case LTE -> label + " ≤ " + fmt(condition.numericValue());
            case BETWEEN -> label + " in [" + fmt(condition.numericValue()) + ", "
                    + fmt(condition.numericValue2()) + "]";
        };
    }

    private static String fmt(float value) {
        if (value == Math.rint(value) && !Float.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
