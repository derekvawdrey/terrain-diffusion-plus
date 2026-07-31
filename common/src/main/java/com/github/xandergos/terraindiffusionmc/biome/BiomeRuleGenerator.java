package com.github.xandergos.terraindiffusionmc.biome;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a new {@link TerrainBiomeRule} (and, if needed, a new {@link TerrainBiomeSettlement})
 * for a biome the user picked in the terrain explorer's "Biome Config" panel, from a small set of
 * coarse climate knobs. Used by {@code ExplorerServer}'s {@code /api/biomes/preview} (dry run) and
 * {@code /api/biomes/apply} (dry run + mutate + persist) handlers.
 *
 * <h2>Rarity heuristic</h2>
 * <p>The UI's four rarity buckets map straight onto {@link TerrainBiomeRule#rarity()}, the weight
 * {@link BiomeRuleEngine} uses to share contested pixels. That is the whole mechanism -- there is
 * no tier to pick and no way for a generated rule to shadow an existing biome, because a weight
 * below 1.0 can only ever take a proportional slice of an overlapping niche, never all of it.</p>
 *
 * <p>This replaced a considerably more delicate arrangement. Under the old integer-priority
 * engine, giving each new biome its own tier destabilised cross-tier competition and produced
 * visibly over-mixed terrain, so the generator instead searched for an "anchor" rule to share a
 * tier with, and relied on within-tier ties resolving to the highest biome index -- which meant a
 * freshly generated rule would deterministically win the entire overlap unless it also carried a
 * noise gate, so a gate had to be forced on even for "common". None of that is load-bearing any
 * more. {@link #findAnchor} survives only to tell the user which existing biome's niche they are
 * overlapping; it no longer decides anything.</p>
 */
public final class BiomeRuleGenerator {

    /** Zones a rule can target, mirroring {@code TerrainBiomeRule.zone()}'s accepted values. */
    public static final List<String> ZONES = List.of("ocean", "beach", "mountain", "lowland", "bareSlope");

    public enum TemperatureBand { COLD, TEMPERATE, WARM, HOT }

    public enum MoistureBand { DRY, MODERATE, WET }

    public enum TreeDensity { BARE, SPARSE, FOREST, DENSE, RAINFOREST }

    public enum Rarity { COMMON, UNCOMMON, RARE, VERY_RARE }

    /** Inclusive-ish [lo, hi] window in degrees Celsius. Chosen to span the catalog's observed
     * -5..26C range with headroom on both ends; these are UI-facing coarse buckets, not derived
     * from a hard architectural bound (temperatureC has none). */
    private record Range(float lo, float hi) {
        boolean overlaps(Range other) {
            return lo <= other.hi && other.lo <= hi;
        }
    }

    private static Range tempRange(TemperatureBand band) {
        return switch (band) {
            case COLD -> new Range(-40f, 0f);
            case TEMPERATE -> new Range(0f, 14f);
            case WARM -> new Range(14f, 25f);
            case HOT -> new Range(25f, 45f);
        };
    }

    /** moisture range paired with a corroborating precipitationMm range for the same band. */
    private record MoistureWindow(Range moisture, Range precip) {}

    private static MoistureWindow moistureWindow(MoistureBand band) {
        return switch (band) {
            // moisture/precipitationMm are architecturally >= 0 (see BiomeClassifier.HARD_BOUNDS
            // equivalent: Math.max(0f, ...) before the aridity divide). Upper bounds here are a
            // practical ceiling (catalog's observed max moisture is 2.4), not a hard limit.
            case DRY -> new MoistureWindow(new Range(0.00f, 0.30f), new Range(0f, 500f));
            case MODERATE -> new MoistureWindow(new Range(0.30f, 0.70f), new Range(400f, 1400f));
            case WET -> new MoistureWindow(new Range(0.70f, 3.00f), new Range(1200f, 6000f));
        };
    }

    /** Maps directly onto one of the 5 discrete values classifyPixel can ever produce -- see
     * {@link BiomeRuleValidator#VALID_TREE_COVERAGE}. Never a range spanning multiple buckets. */
    private static float treeCoverageValue(TreeDensity density) {
        return switch (density) {
            case BARE -> 0.00f;
            case SPARSE -> 0.35f;
            case FOREST -> 0.62f;
            case DENSE -> 0.85f;
            case RAINFOREST -> 1.00f;
        };
    }

    /**
     * variantNoise (family oct3_gain055, measured range [-0.779769, 0.768161], see
     * {@link BiomeRuleValidator}) gate thresholds. variantNoise is BiomeClassifier's
     * general-purpose noise field (also used for e.g. the slope-vegetation-clinging split), so
     * it's the idiomatic choice for an ad hoc rarity split here too.
     *
     * <p>We don't have a real quantile/area distribution for this field (only the measured
     * min/max ceiling), so these are fixed fractions of the measured positive ceiling (0.768),
     * chosen with a comfortable safety margin below it so a "very-rare" pick is never
     * accidentally unreachable:</p>
     * <ul>
     *   <li>uncommon: {@code > 0.27} (~35% of ceiling)</li>
     *   <li>rare: {@code > 0.42} (~55% of ceiling)</li>
     *   <li>very-rare: {@code > 0.58} (~75% of ceiling, leaving &gt;0.18 of headroom below the
     *   true 0.768 max)</li>
     * </ul>
     * A real area-fraction calibration would need the Monte Carlo occupancy analysis in
     * {@code tools/biome-lab/biomelab/montecarlo.py}; these are a reasonable, always-reachable
     * first cut, not a claim of exact rarity percentages.
     */
    /**
     * The UI's rarity bucket as a {@link TerrainBiomeRule#rarity()} weight. Because the engine
     * gives biome {@code i} a share of {@code w_i / sum(w)} of the pixels it is eligible for,
     * these read directly: an "uncommon" biome takes about a quarter of a niche it fully shares
     * with one ungated "common" biome, a "very rare" one about 4%.
     */
    private static float rarityWeight(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> 1.0f;
            case UNCOMMON -> 0.35f;
            case RARE -> 0.12f;
            case VERY_RARE -> 0.04f;
        };
    }

    private static Float noiseThresholdFor(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> null;
            case UNCOMMON -> 0.27f;
            case RARE -> 0.42f;
            case VERY_RARE -> 0.58f;
        };
    }

    public record Request(String biomeKey, String zone, TemperatureBand temperatureBand,
                           MoistureBand moistureBand, TreeDensity treeDensity, Rarity rarity) {
    }

    public record AnchorInfo(String biomeKey, short biomeIndex, float rarity, String reason) {
    }

    public record Result(String biomeKey, boolean newSettlement, short assignedIndex,
                          TerrainBiomeSettlement settlement, TerrainBiomeRule rule, float rarity,
                          AnchorInfo anchor, List<String> validationFindings) {
        public boolean valid() {
            return validationFindings.isEmpty();
        }
    }

    private BiomeRuleGenerator() {
    }

    /**
     * Generates (but does not apply/persist) a rule for the given request. Pure/dry-run: never
     * mutates {@code registry}. Callers that want to actually apply the result must call
     * {@link TerrainBiomeSettlement#addRule} on {@link Result#settlement()} themselves (adding
     * the new settlement to the registry first via {@link TerrainBiomeRegistry#register} if
     * {@link Result#newSettlement()} is true), then {@link TerrainBiomeRegistry#rebuild()} and
     * {@link TerrainBiomeRegistry#saveToConfigDir()}.
     */
    public static Result generate(TerrainBiomeRegistry registry, Request req) {
        if (!ZONES.contains(req.zone())) {
            throw new IllegalArgumentException("Unknown zone '" + req.zone() + "', expected one of " + ZONES);
        }

        float targetTreeCoverage = treeCoverageValue(req.treeDensity());
        Range targetTempRange = tempRange(req.temperatureBand());
        MoistureWindow targetMoisture = moistureWindow(req.moistureBand());

        AnchorInfo anchor = findAnchor(registry, req.zone(), targetTreeCoverage, targetTempRange,
                targetMoisture.moisture());

        float rarity = rarityWeight(req.rarity());

        // The noise gate is now purely a shaping choice -- it breaks a rarity band into coherent
        // patches instead of letting the weight scatter it evenly through the niche. It is no
        // longer needed to stop a new rule clobbering an existing one, so "common" keeps its
        // ungated full-niche behaviour whether or not it overlaps something.
        Float noiseThreshold = noiseThresholdFor(req.rarity());

        List<TerrainBiomeCondition> conditions = new ArrayList<>();
        conditions.add(TerrainBiomeCondition.numeric("treeCoverage", TerrainBiomeCondition.Operator.EQ,
                targetTreeCoverage));
        conditions.add(TerrainBiomeCondition.between("temperatureC", targetTempRange.lo(), targetTempRange.hi()));
        conditions.add(TerrainBiomeCondition.between("moisture",
                targetMoisture.moisture().lo(), targetMoisture.moisture().hi()));
        conditions.add(TerrainBiomeCondition.between("precipitationMm",
                targetMoisture.precip().lo(), targetMoisture.precip().hi()));

        List<TerrainBiomeCondition> noiseConditions = new ArrayList<>();
        if (noiseThreshold != null) {
            noiseConditions.add(TerrainBiomeCondition.numeric("variantNoise", TerrainBiomeCondition.Operator.GT,
                    noiseThreshold));
        }

        TerrainBiomeRule rule = new TerrainBiomeRule(req.zone(), rarity, conditions, noiseConditions);
        List<String> findings = BiomeRuleValidator.validate(rule);

        TerrainBiomeSettlement existing = registry.byKey(req.biomeKey());
        boolean isNew = existing == null;
        short assignedIndex;
        TerrainBiomeSettlement settlement;
        if (existing != null) {
            assignedIndex = existing.index();
            settlement = existing;
        } else {
            assignedIndex = (short) registry.indexUpperBound();
            settlement = new TerrainBiomeSettlement(assignedIndex, req.biomeKey(), req.biomeKey(), "OVERWORLD",
                    defaultColorFor(req.biomeKey()), false, true, false, false, true, new ArrayList<>());
        }

        return new Result(req.biomeKey(), isNew, assignedIndex, settlement, rule, rarity, anchor,
                findings);
    }

    /**
     * Looks for an existing rule in {@code zone} whose {@code treeCoverage}/{@code temperatureC}/
     * {@code moisture} conditions are compatible with the requested niche (a rule with no
     * condition on one of those variables is treated as a wildcard on that axis -- it doesn't
     * conflict). Among compatible candidates, prefers the highest rarity weight (the most
     * "established" occupant of that niche), tie-broken by lowest settlement index for
     * determinism.
     */
    private static AnchorInfo findAnchor(TerrainBiomeRegistry registry, String zone, float targetTreeCoverage,
                                          Range targetTempRange, Range targetMoistureRange) {
        TerrainBiomeSettlement bestSettlement = null;
        TerrainBiomeRule bestRule = null;

        for (TerrainBiomeSettlement settlement : registry.all()) {
            for (TerrainBiomeRule rule : settlement.rules()) {
                if (!zone.equals(rule.zone())) continue;

                List<TerrainBiomeCondition> treeConds = conditionsFor(rule, TerrainBiomeCondition.Variable.TREE_COVERAGE);
                List<TerrainBiomeCondition> tempConds = conditionsFor(rule, TerrainBiomeCondition.Variable.TEMPERATURE_C);
                List<TerrainBiomeCondition> moistConds = conditionsFor(rule, TerrainBiomeCondition.Variable.MOISTURE);

                boolean treeOk = treeConds.isEmpty() || BiomeRuleValidator.admitsValue(treeConds, targetTreeCoverage);
                boolean tempOk = tempConds.isEmpty() || groupInterval(tempConds).overlaps(targetTempRange);
                boolean moistOk = moistConds.isEmpty() || groupInterval(moistConds).overlaps(targetMoistureRange);

                if (!(treeOk && tempOk && moistOk)) continue;

                if (bestRule == null
                        || rule.rarity() > bestRule.rarity()
                        || (rule.rarity() == bestRule.rarity() && settlement.index() < bestSettlement.index())) {
                    bestSettlement = settlement;
                    bestRule = rule;
                }
            }
        }

        if (bestRule == null) return null;
        String reason = String.format(
                "overlaps %s in the %s zone (rarity %.2f); its treeCoverage/temperatureC/moisture "
                        + "conditions cover the requested niche, or don't constrain that axis at all, "
                        + "so the two will share the area in proportion to their rarity weights",
                bestSettlement.key(), zone, bestRule.rarity());
        return new AnchorInfo(bestSettlement.key(), bestSettlement.index(), bestRule.rarity(), reason);
    }

    private static List<TerrainBiomeCondition> conditionsFor(TerrainBiomeRule rule,
                                                               TerrainBiomeCondition.Variable variable) {
        List<TerrainBiomeCondition> result = new ArrayList<>();
        for (TerrainBiomeCondition c : rule.conditions()) {
            if (c.resolvedVariable() == variable) result.add(c);
        }
        return result;
    }

    /** Folds AND-combined numeric conditions on one variable into the tightest [lo, hi] interval
     * they jointly allow. Mirrors {@code validators.py}'s {@code _group_interval}. */
    private static Range groupInterval(List<TerrainBiomeCondition> conds) {
        float lo = -Float.MAX_VALUE, hi = Float.MAX_VALUE;
        for (TerrainBiomeCondition c : conds) {
            switch (c.operator()) {
                case EQ -> {
                    lo = Math.max(lo, c.numericValue());
                    hi = Math.min(hi, c.numericValue());
                }
                case GT, GTE -> lo = Math.max(lo, c.numericValue());
                case LT, LTE -> hi = Math.min(hi, c.numericValue());
                case BETWEEN -> {
                    lo = Math.max(lo, c.numericValue());
                    hi = Math.min(hi, c.numericValue2());
                }
                default -> {
                    // no-op
                }
            }
        }
        return new Range(lo, hi);
    }


    /**
     * Deterministic, moderately saturated pastel-ish color for a brand-new settlement's map
     * display, derived from a hash of its key so re-generating the same biome always gets the
     * same color instead of a random one on every apply.
     */
    private static int defaultColorFor(String biomeKey) {
        float hue = (biomeKey.hashCode() & 0xFFFF) / (float) 0xFFFF;
        return Color.HSBtoRGB(hue, 0.55f, 0.85f) & 0xFFFFFF;
    }
}
