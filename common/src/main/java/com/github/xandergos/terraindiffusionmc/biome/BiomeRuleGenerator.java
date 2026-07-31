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
 * <h2>Priority-tier heuristic</h2>
 * <p>The naive approach -- always giving a newly added biome its own brand-new priority tier --
 * is exactly what caused a real, previously-diagnosed bug: many biomes each getting a unique
 * priority tier made {@link BiomeRuleEngine}'s cross-tier competition-noise candidate selection
 * unstable, visibly "too mixed" terrain (that session's fix damped
 * {@code COMPETITION_RANK_PENALTY}/{@code COMPETITION_NOISE_WAVELENGTH} and redesigned several
 * biomes to share a tier with a {@code variantNoise}-gated split instead of colliding across
 * tiers). So: before picking a priority, {@link #findAnchor} looks for an existing rule in the
 * same zone whose {@code treeCoverage}/{@code temperatureC}/{@code moisture} conditions roughly
 * overlap the requested niche. If found, the new rule is inserted at the <b>same priority
 * tier</b> as that anchor rather than a new one.</p>
 *
 * <p>Sharing a tier is safe here specifically because of how {@link BiomeRuleEngine#select}
 * resolves ties <i>within</i> one priority tier: it is NOT the same noise-based competition used
 * across tiers -- within a tier the rule belonging to the <b>highest biome index</b> that matches
 * wins outright, deterministically (see select's inner loop, "if (entry.index &lt;=
 * bestIndex) continue"). A freshly generated settlement always gets the next unused (i.e.
 * highest) index, so as long as our new rule's noise-gated condition set is a strict subset of
 * the anchor's climate window, the two behave as clean, deterministic, spatially-coherent
 * territory: wherever {@code variantNoise} clears our threshold AND the shared climate
 * conditions hold, our higher-index rule wins outright; everywhere else in that same climate
 * window, only the (ungated) anchor rule matches and it keeps winning as before. We never need
 * to (and must not) retroactively edit the anchor's own rule for this to work.</p>
 *
 * <p>Because of that clobber risk, a noise gate is added whenever we share a tier with an
 * anchor -- even for "common" rarity, which is floored to the "uncommon" threshold in that case
 * (see resolveNoiseThreshold) so the new biome never fully overwrites 100% of the
 * anchor's existing territory in their overlapping niche. Only a genuinely new, non-overlapping
 * tier (no anchor found) allows a true no-gate "common" pick.</p>
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

    public record AnchorInfo(String biomeKey, short biomeIndex, int priority, String reason) {
    }

    public record Result(String biomeKey, boolean newSettlement, short assignedIndex,
                          TerrainBiomeSettlement settlement, TerrainBiomeRule rule, int priority,
                          AnchorInfo anchor, boolean newTier, List<String> validationFindings) {
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

        int priority;
        boolean newTier;
        if (anchor != null) {
            priority = anchor.priority();
            newTier = false;
        } else {
            priority = maxPriorityForZone(registry, req.zone()) + 1;
            newTier = true;
        }

        // See class javadoc: always gate with a noise condition when sharing a tier (floor
        // "common" up to "uncommon"), since our new settlement's higher index would otherwise
        // deterministically win the ENTIRE overlap with the anchor, not just a rare pocket of it.
        Rarity effectiveRarity = (anchor != null && req.rarity() == Rarity.COMMON) ? Rarity.UNCOMMON : req.rarity();
        Float noiseThreshold = noiseThresholdFor(effectiveRarity);

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

        TerrainBiomeRule rule = new TerrainBiomeRule(req.zone(), priority, conditions, noiseConditions);
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

        return new Result(req.biomeKey(), isNew, assignedIndex, settlement, rule, priority, anchor, newTier,
                findings);
    }

    /**
     * Looks for an existing rule in {@code zone} whose {@code treeCoverage}/{@code temperatureC}/
     * {@code moisture} conditions are compatible with the requested niche (a rule with no
     * condition on one of those variables is treated as a wildcard on that axis -- it doesn't
     * conflict). Among compatible candidates, prefers the highest priority (most "established"
     * tier), tie-broken by lowest settlement index for determinism.
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
                        || rule.priority() > bestRule.priority()
                        || (rule.priority() == bestRule.priority() && settlement.index() < bestSettlement.index())) {
                    bestSettlement = settlement;
                    bestRule = rule;
                }
            }
        }

        if (bestRule == null) return null;
        String reason = String.format(
                "shares %s tier %d with %s (its treeCoverage/temperatureC/moisture conditions overlap "
                        + "the requested niche, or don't constrain that axis at all)",
                zone, bestRule.priority(), bestSettlement.key());
        return new AnchorInfo(bestSettlement.key(), bestSettlement.index(), bestRule.priority(), reason);
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

    private static int maxPriorityForZone(TerrainBiomeRegistry registry, String zone) {
        int max = 0;
        for (TerrainBiomeSettlement settlement : registry.all()) {
            for (TerrainBiomeRule rule : settlement.rules()) {
                if (zone.equals(rule.zone()) && rule.priority() > max) max = rule.priority();
            }
        }
        return max;
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
