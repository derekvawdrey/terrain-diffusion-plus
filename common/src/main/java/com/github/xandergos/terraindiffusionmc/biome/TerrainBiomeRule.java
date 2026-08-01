package com.github.xandergos.terraindiffusionmc.biome;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * A data-driven rule that selects a biome when all its conditions are met.
 *
 * <p>Each rule belongs to a {@link TerrainBiomeSettlement} (a biome's full
 * definition). The rule engine evaluates all rules for a given zone
 * (ocean / beach / mountain / lowland), collects every biome whose rules match,
 * and picks among them by {@link #rarity()} -- see {@link BiomeRuleEngine}.</p>
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

    /**
     * Relative weight among the biomes eligible at a pixel: a biome wins a contested pixel with
     * probability {@code rarity / (sum of every eligible biome's rarity)}. Defaults to 1.0 when
     * absent. Zero or negative disables the rule.
     *
     * <p>This is the knob for "how much of the area it could occupy does it actually take" --
     * {@code rarity: 0.15} on a jungle variant means roughly 15% of the jungle it overlaps. It
     * replaces the old integer {@code priority}, which forced authors to encode "this is a rare
     * variant of that" as a brittle numeric ordering: any variant that ended up numerically
     * *below* the broad biome it refined became permanently invisible, silently.</p>
     */
    @SerializedName("rarity")
    private Float rarity;

    /**
     * When true, this rule beats every non-override candidate outright, and only competes on
     * {@link #rarity()} against other override rules. Reserved for genuine structural dominance;
     * ordinary "rarer variant of" relationships want {@code rarity} instead.
     */
    @SerializedName("override")
    private Boolean override;

    /**
     * Optional noise conditions. These are evaluated against the noise
     * values passed to the rule engine, not the climate sample.
     * The variable names are: variantNoise, cherryNoise, paleNoise,
     * clearingNoise, flowerNoise.
     */
    @SerializedName("noiseConditions")
    private List<TerrainBiomeCondition> noiseConditions;

    /**
     * Loader mod ids that must all be installed for this rule to be eligible. Lets a biome that
     * exists unconditionally carry an extra, differently-weighted rule that only applies when an
     * optional mod is present, without duplicating the whole settlement. Absent means
     * unconditional; see {@link TerrainBiomeSettlement#requiredMods()}.
     */
    @SerializedName("requiredMods")
    private List<String> requiredMods;

    /** No-arg constructor kept for Gson (which uses Unsafe and never actually calls it). */
    public TerrainBiomeRule() {
    }

    /**
     * Programmatic constructor for code that builds rules at runtime (e.g. the terrain
     * explorer's "Biome Config" rule generator) instead of deserializing them from JSON.
     */
    public TerrainBiomeRule(String zone, float rarity, List<TerrainBiomeCondition> conditions,
                             List<TerrainBiomeCondition> noiseConditions) {
        this(zone, rarity, false, conditions, noiseConditions);
    }

    public TerrainBiomeRule(String zone, float rarity, boolean override,
                             List<TerrainBiomeCondition> conditions,
                             List<TerrainBiomeCondition> noiseConditions) {
        this.zone = zone;
        this.rarity = rarity;
        this.override = override ? Boolean.TRUE : null;
        this.conditions = conditions;
        this.noiseConditions = noiseConditions;
    }

    public String zone() {
        return zone;
    }

    public List<TerrainBiomeCondition> conditions() {
        return conditions != null ? conditions : List.of();
    }

    /** Relative selection weight; 1.0 when the catalog omits it. */
    public float rarity() {
        return rarity != null ? rarity : 1.0f;
    }

    /** Whether this rule outranks every non-override candidate. */
    public boolean isOverride() {
        return Boolean.TRUE.equals(override);
    }

    public List<TerrainBiomeCondition> noiseConditions() {
        return noiseConditions != null ? noiseConditions : List.of();
    }

    public List<String> requiredMods() {
        return requiredMods != null ? requiredMods : List.of();
    }

    /** Whether every mod this rule requires is installed. */
    public boolean isAvailable() {
        return com.github.xandergos.terraindiffusionmc.platform.PlatformMods.allLoaded(requiredMods());
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
