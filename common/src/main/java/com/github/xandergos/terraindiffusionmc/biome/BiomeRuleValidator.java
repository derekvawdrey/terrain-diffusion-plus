package com.github.xandergos.terraindiffusionmc.biome;

import java.util.ArrayList;
import java.util.List;

/**
 * Fast, static (no Monte Carlo needed) validity checks for a single {@link TerrainBiomeRule},
 * meant to run in well under a millisecond so the terrain explorer's "Biome Config" preview/apply
 * flow can call it on every request.
 *
 * <p>This is a direct Java port of the <i>logic and constants</i> from
 * {@code tools/biome-lab/biomelab/validators.py}'s {@code check_discreteness} and
 * {@code check_noise_ceiling} (see that file for the original derivation and commentary) --
 * not a translation of the Python code itself. Two checks:</p>
 *
 * <ol>
 *   <li><b>Discreteness</b> -- {@code BiomeClassifier.classifyPixel} only ever produces one of
 *   5 exact {@code treeCoverage} values (and the matching 5 {@code sparsity} values), see
 *   {@code classifyPixel} lines ~343-349. A rule's combined conditions on {@code treeCoverage}
 *   (or {@code sparsity}) must jointly admit at least one of those 5 discrete values, or the
 *   rule can never match any real pixel.</li>
 *   <li><b>Noise ceiling</b> -- every {@code noiseConditions} threshold is checked against the
 *   real measured min/max for that noise field's (octaves, gain) family, re-measured this
 *   morning by {@code tools/biome-lab/java/NoiseProbe.java} over 20M+ samples/family and
 *   recorded in {@code tools/biome-lab/data/noise_quantiles/summary.json}. A threshold beyond
 *   the measured range can never be satisfied. These numbers are hardcoded here (not read from
 *   that file) because this mod ships as a jar without the {@code tools/} dev directory.</li>
 * </ol>
 */
public final class BiomeRuleValidator {

    private static final float EPS = 1e-6f;

    /**
     * The 5 discrete {@code treeCoverage} values {@code classifyPixel} can ever produce
     * (none/sparse/forest/dense/rainforest buckets). See {@code catalog.VALID_TREE_COVERAGE} in
     * the Python tool for the same constant.
     */
    public static final float[] VALID_TREE_COVERAGE = {0.00f, 0.35f, 0.62f, 0.85f, 1.00f};

    /** {@code sparsity = clamp01(1 - treeCoverage)}, so these mirror {@link #VALID_TREE_COVERAGE}. */
    public static final float[] VALID_SPARSITY = {1.00f, 0.65f, 0.38f, 0.15f, 0.00f};

    /**
     * A noise field's measured (octaves, gain) family range. Values below are copied from
     * {@code tools/biome-lab/data/noise_quantiles/summary.json}, generated this morning by
     * {@code NoiseProbe} over 20M+ samples per family -- these are the real measured min/max,
     * not estimates.
     */
    public record NoiseFamily(String name, int octaves, float gain, float min, float max) {
        boolean unreachable(TerrainBiomeCondition cond) {
            switch (cond.operator()) {
                case EQ:
                    return !(min - EPS <= cond.numericValue() && cond.numericValue() <= max + EPS);
                case GT:
                case GTE:
                    return cond.numericValue() > max;
                case LT:
                case LTE:
                    return cond.numericValue() < min;
                case BETWEEN:
                    return cond.numericValue() > max || cond.numericValue2() < min;
                default:
                    return false;
            }
        }
    }

    // Measured 2026-07-30 by NoiseProbe; see tools/biome-lab/data/noise_quantiles/summary.json.
    // BiomeClassifier's FastNoiseLite instances that back each rule-condition noise variable
    // (see BiomeClassifier's static initializer): variantNoise/cherryNoise/paleNoise are all
    // FBm(octaves=3, gain=0.55); clearingNoise/flowerNoise are FBm(octaves=3, gain=0.54);
    // regionNoise is FBm(octaves=2, gain=0.50).
    private static final NoiseFamily FAMILY_OCT3_GAIN055 =
            new NoiseFamily("oct3_gain055", 3, 0.55f, -0.779769f, 0.768161f);
    private static final NoiseFamily FAMILY_OCT3_GAIN054 =
            new NoiseFamily("oct3_gain054", 3, 0.54f, -0.781112f, 0.768861f);
    private static final NoiseFamily FAMILY_OCT2_GAIN050 =
            new NoiseFamily("oct2_gain050", 2, 0.50f, -0.803125f, 0.807837f);

    private static NoiseFamily familyFor(TerrainBiomeCondition.Variable variable) {
        return switch (variable) {
            case VARIANT_NOISE, CHERRY_NOISE, PALE_NOISE -> FAMILY_OCT3_GAIN055;
            case CLEARING_NOISE, FLOWER_NOISE -> FAMILY_OCT3_GAIN054;
            case REGION_NOISE -> FAMILY_OCT2_GAIN050;
            default -> null;
        };
    }

    private BiomeRuleValidator() {
    }

    /**
     * Validates a single rule (not yet attached to any settlement). Returns a list of
     * human-readable "dead rule" findings; empty means the rule passed both checks and can, in
     * principle, match a real pixel.
     */
    public static List<String> validate(TerrainBiomeRule rule) {
        List<String> findings = new ArrayList<>();
        findings.addAll(checkDiscreteness(rule));
        findings.addAll(checkNoiseCeiling(rule));
        return findings;
    }

    /**
     * Does some hypothetical pixel with {@code cond.variable == candidate} satisfy this single
     * condition? Mirrors {@code validators._candidate_satisfies}.
     */
    private static boolean candidateSatisfies(TerrainBiomeCondition cond, float candidate) {
        switch (cond.operator()) {
            case EQ:
                return Math.abs(candidate - cond.numericValue()) < EPS;
            case GT:
                return candidate > cond.numericValue() - EPS;
            case GTE:
                return candidate >= cond.numericValue() - EPS;
            case LT:
                return candidate < cond.numericValue() + EPS;
            case LTE:
                return candidate <= cond.numericValue() + EPS;
            case BETWEEN:
                return cond.numericValue() - EPS <= candidate && candidate <= cond.numericValue2() + EPS;
            default:
                return true; // unknown op: don't flag
        }
    }

    /**
     * True if some discrete valid value satisfies EVERY condition on this variable simultaneously
     * (conditions within a rule are AND-combined, exactly like {@code TerrainBiomeRule.matches}).
     * Testing each real discrete candidate directly -- rather than doing interval algebra on
     * gt/gte/lt/lte/between/eq -- sidesteps a subtle bug: a rule can gate the same variable with
     * two separate conditions (e.g. {@code treeCoverage >= 0.02} AND {@code treeCoverage < 0.15})
     * where each is individually satisfiable but their conjunction straddles no real value.
     */
    private static boolean groupReachable(List<TerrainBiomeCondition> conds, float[] validValues) {
        for (float candidate : validValues) {
            if (admitsValue(conds, candidate)) return true;
        }
        return false;
    }

    /**
     * True if every condition in {@code conds} (AND-combined, matching
     * {@link TerrainBiomeRule#matches}) is satisfied by a hypothetical pixel whose variable
     * value equals {@code candidate}. Public so {@link BiomeRuleGenerator}'s anchor search can
     * reuse the exact same "is this discrete value reachable" logic the validator uses, e.g. to
     * check whether an existing rule's {@code treeCoverage} conditions admit a newly requested
     * tree-density bucket.
     */
    public static boolean admitsValue(List<TerrainBiomeCondition> conds, float candidate) {
        for (TerrainBiomeCondition c : conds) {
            if (!candidateSatisfies(c, candidate)) return false;
        }
        return true;
    }

    private static List<String> checkDiscreteness(TerrainBiomeRule rule) {
        List<String> findings = new ArrayList<>();
        findings.addAll(checkDiscretenessForVariable(rule, "treeCoverage",
                TerrainBiomeCondition.Variable.TREE_COVERAGE, VALID_TREE_COVERAGE));
        findings.addAll(checkDiscretenessForVariable(rule, "sparsity",
                TerrainBiomeCondition.Variable.SPARSITY, VALID_SPARSITY));
        return findings;
    }

    private static List<String> checkDiscretenessForVariable(TerrainBiomeRule rule, String varName,
                                                               TerrainBiomeCondition.Variable variable,
                                                               float[] validValues) {
        List<TerrainBiomeCondition> conds = new ArrayList<>();
        for (TerrainBiomeCondition c : rule.conditions()) {
            if (c.resolvedVariable() == variable) conds.add(c);
        }
        if (conds.isEmpty() || groupReachable(conds, validValues)) return List.of();

        StringBuilder joint = new StringBuilder();
        for (int i = 0; i < conds.size(); i++) {
            if (i > 0) joint.append(" AND ");
            joint.append(describe(conds.get(i)));
        }
        String message = conds.size() == 1
                ? String.format("%s only ever takes values %s. '%s' straddles none of them and can never match.",
                        varName, java.util.Arrays.toString(validValues), describe(conds.get(0)))
                : String.format("%s only ever takes values %s. This rule's combined conditions ('%s') "
                        + "straddle none of them and can never jointly match.",
                        varName, java.util.Arrays.toString(validValues), joint);
        return List.of(message);
    }

    private static List<String> checkNoiseCeiling(TerrainBiomeRule rule) {
        List<String> findings = new ArrayList<>();
        for (TerrainBiomeCondition cond : rule.noiseConditions()) {
            NoiseFamily fam = familyFor(cond.resolvedVariable());
            if (fam == null) continue;
            if (fam.unreachable(cond)) {
                findings.add(String.format(
                        "%s (family %s, octaves=%d, gain=%.2f) was measured to stay within [%.4f, %.4f] "
                                + "-- '%s' falls outside that and can never match.",
                        cond.variable(), fam.name(), fam.octaves(), fam.gain(), fam.min(), fam.max(),
                        describe(cond)));
            }
        }
        return findings;
    }

    private static String describe(TerrainBiomeCondition cond) {
        if (cond.boolValue() != null) {
            return cond.variable() + " == " + cond.boolValue();
        }
        if (cond.operator() == TerrainBiomeCondition.Operator.BETWEEN) {
            return cond.numericValue() + " <= " + cond.variable() + " <= " + cond.numericValue2();
        }
        String sym = switch (cond.operator()) {
            case EQ -> "==";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            default -> cond.operator().toString();
        };
        return cond.variable() + " " + sym + " " + cond.numericValue();
    }
}
