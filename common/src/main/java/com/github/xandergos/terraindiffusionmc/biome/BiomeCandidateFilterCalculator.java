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
 * <h2>Why per-zone, and why an envelope rather than an exact region</h2>
 * <p>A biome's rules are grouped by {@link TerrainBiomeRule#zone()} because different zones (e.g.
 * {@code taiga}'s {@code lowland} vs {@code mountain} rules) can have very different climate
 * windows -- merging them would produce a filter box wide enough to be useless. Within one zone
 * group, a biome can still have many rules (a noise-gated variant family like
 * {@code cherry_grove}'s ~48 rules is a real example), each an AND of conditions. For a given
 * translatable variable, this takes the AND (intersection) of that variable's bounds *within* each
 * rule, then the OR (union) *across* rules in the zone -- matching how {@link BiomeRuleEngine}
 * actually picks a winner (any one matching rule is enough). If any rule in the zone leaves the
 * variable completely unconstrained, the zone's true reachable range for that variable is
 * unbounded, so no filter is emitted for that channel at all (better to show too much than to
 * hide real candidate area).</p>
 *
 * <h2>Only 5 of 17 climate variables are coarse-map channels</h2>
 * <p>The coarse tensor the explorer maps to pixels only carries elevation, temperature,
 * temperature-seasonality, precipitation, and precipitation-CV (see {@code ExplorerServer}'s
 * {@code CHANNEL_NAMES} / {@code coarseChannel}). Conditions on anything else -- {@code slope},
 * {@code treeCoverage}/{@code sparsity} (which also depend on {@code slope}), {@code snowy},
 * {@code bareSlope}, {@code moisture}/{@code aridity}/{@code treeMoisture}, and every
 * {@code noiseConditions} entry -- come from fine-resolution per-pixel data or independent noise
 * fields the coarse map has no proxy for. Those are surfaced as human-readable caveat strings
 * instead of a slider, so the UI can tell the user the highlighted area is necessary but not
 * sufficient.</p>
 */
public final class BiomeCandidateFilterCalculator {

    /** Cap on how many distinct caveat strings a single zone group reports, to keep a 48-rule
     * variant family's response readable instead of dumping near-duplicate noise-gate lines. */
    private static final int MAX_CAVEATS = 10;

    private static final Set<Variable> FILTERABLE = EnumSet.of(
            Variable.ELEVATION_M, Variable.TEMPERATURE_C, Variable.TEMPERATURE_SEASONALITY,
            Variable.PRECIPITATION_MM, Variable.PRECIPITATION_CV);

    /** Maps a filterable climate variable to the explorer coarse-map channel index that
     * represents it (or approximates it -- see class docs for exactness caveats per variable). */
    private static final Map<Variable, Integer> VARIABLE_TO_CHANNEL = new EnumMap<>(Map.of(
            Variable.ELEVATION_M, 0,
            Variable.TEMPERATURE_C, 2,
            Variable.TEMPERATURE_SEASONALITY, 3,
            Variable.PRECIPITATION_MM, 4,
            Variable.PRECIPITATION_CV, 5));

    private BiomeCandidateFilterCalculator() {
    }

    /** One zone's worth of merged candidate-filter data for a biome. */
    public record ZoneCandidate(String zone, int ruleCount, int minPriority, int maxPriority,
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

    private static ZoneCandidate computeZone(String zone, List<TerrainBiomeRule> rules) {
        Map<Integer, float[]> channelFilters = new LinkedHashMap<>();
        for (Variable variable : FILTERABLE) {
            float[] envelope = unionAcrossRules(rules, variable);
            if (envelope != null) {
                channelFilters.put(VARIABLE_TO_CHANNEL.get(variable), envelope);
            }
        }

        int minPriority = Integer.MAX_VALUE;
        int maxPriority = Integer.MIN_VALUE;
        Set<String> caveats = new LinkedHashSet<>();
        for (TerrainBiomeRule rule : rules) {
            minPriority = Math.min(minPriority, rule.priority());
            maxPriority = Math.max(maxPriority, rule.priority());
            for (TerrainBiomeCondition condition : rule.conditions()) {
                if (!FILTERABLE.contains(condition.resolvedVariable())) {
                    caveats.add(describe(condition));
                }
            }
            for (TerrainBiomeCondition condition : rule.noiseConditions()) {
                caveats.add(describe(condition));
            }
        }

        List<String> caveatList = new ArrayList<>(caveats);
        boolean truncated = caveatList.size() > MAX_CAVEATS;
        if (truncated) {
            caveatList = new ArrayList<>(caveatList.subList(0, MAX_CAVEATS));
        }

        return new ZoneCandidate(zone, rules.size(), minPriority, maxPriority, channelFilters,
                caveatList, truncated);
    }

    /**
     * Intersects {@code variable}'s bounds within each rule (AND, since a rule's conditions are
     * AND-combined), then unions those per-rule bounds across all rules in the zone (OR, since any
     * matching rule wins). Returns {@code null} if the variable is unbounded overall -- either no
     * rule constrains it, or at least one rule in the zone leaves it fully open.
     */
    private static float[] unionAcrossRules(List<TerrainBiomeRule> rules, Variable variable) {
        float envMin = Float.POSITIVE_INFINITY;
        float envMax = Float.NEGATIVE_INFINITY;
        for (TerrainBiomeRule rule : rules) {
            float ruleMin = Float.NEGATIVE_INFINITY;
            float ruleMax = Float.POSITIVE_INFINITY;
            boolean constrained = false;
            for (TerrainBiomeCondition condition : rule.conditions()) {
                if (condition.resolvedVariable() != variable) continue;
                constrained = true;
                switch (condition.operator()) {
                    case EQ -> {
                        ruleMin = Math.max(ruleMin, condition.numericValue());
                        ruleMax = Math.min(ruleMax, condition.numericValue());
                    }
                    case GT, GTE -> ruleMin = Math.max(ruleMin, condition.numericValue());
                    case LT, LTE -> ruleMax = Math.min(ruleMax, condition.numericValue());
                    case BETWEEN -> {
                        ruleMin = Math.max(ruleMin, condition.numericValue());
                        ruleMax = Math.min(ruleMax, condition.numericValue2());
                    }
                }
            }
            if (!constrained) {
                // This rule alone can match with no bound on `variable` at all, so the zone as a
                // whole has no reachable-range limit for it -- emitting a filter here would hide
                // real candidate area rather than merely being imprecise.
                return null;
            }
            envMin = Math.min(envMin, ruleMin);
            envMax = Math.max(envMax, ruleMax);
        }
        if (envMin > envMax) return null;
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
