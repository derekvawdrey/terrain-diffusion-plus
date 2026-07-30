package com.github.xandergos.terraindiffusionmc.biome;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * A data-driven rule that selects a biome when all its conditions are met.
 *
 * <p>Each rule belongs to a {@link TerrainBiomeSettlement} (a biome's full
 * definition). The rule engine evaluates all rules for a given zone
 * (ocean / beach / mountain / lowland), collects matches, and picks the
 * winner by {@code priority} (highest wins, ties broken by index).</p>
 *
 * <p>Rules support both climate-variable conditions and noise-variable
 * conditions. Noise conditions reference the noise fields computed by
 * {@code BiomeClassifier} (variantNoise, cherryNoise, paleNoise,
 * clearingNoise, flowerNoise).</p>
 */
public final class TerrainBiomeRule {
    /** Zone this rule applies to: "ocean", "beach", "mountain", or "lowland". */
    @SerializedName("zone")
    private String zone;

    /** All conditions must be true for this rule to match. */
    @SerializedName("conditions")
    private List<TerrainBiomeCondition> conditions;

    /** Higher priority wins when multiple rules match. */
    @SerializedName("priority")
    private int priority;

    /**
     * Optional noise conditions. These are evaluated against the noise
     * values passed to the rule engine, not the climate sample.
     * The variable names are: variantNoise, cherryNoise, paleNoise,
     * clearingNoise, flowerNoise.
     */
    @SerializedName("noiseConditions")
    private List<TerrainBiomeCondition> noiseConditions;

    public String zone() {
        return zone;
    }

    public List<TerrainBiomeCondition> conditions() {
        return conditions != null ? conditions : List.of();
    }

    public int priority() {
        return priority;
    }

    public List<TerrainBiomeCondition> noiseConditions() {
        return noiseConditions != null ? noiseConditions : List.of();
    }

    /**
     * Check if all climate conditions are satisfied.
     */
    public boolean matches(TerrainClimateSample sample) {
        for (TerrainBiomeCondition cond : conditions()) {
            if (!cond.evaluate(sample)) return false;
        }
        return true;
    }

    /**
     * Check if all noise conditions are satisfied.
     */
    public boolean matchesNoise(TerrainBiomeNoiseSample noiseValues) {
        for (TerrainBiomeCondition cond : noiseConditions()) {
            if (!evaluateNoiseCondition(cond, noiseValues)) return false;
        }
        return true;
    }

    private boolean evaluateNoiseCondition(TerrainBiomeCondition cond, TerrainBiomeNoiseSample noiseValues) {
        float val = noiseValues.value(cond.resolvedVariable());
        if (Float.isNaN(val)) return false;

        switch (cond.operator()) {
            case EQ:    return val == cond.numericValue();
            case GT:    return val > cond.numericValue();
            case GTE:   return val >= cond.numericValue();
            case LT:    return val < cond.numericValue();
            case LTE:   return val <= cond.numericValue();
            case BETWEEN: return val >= cond.numericValue() && val <= cond.numericValue2();
            default:    return false;
        }
    }
}
