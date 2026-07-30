package com.github.xandergos.terraindiffusionmc.biome;

import com.google.gson.annotations.SerializedName;

/**
 * A single condition on a climate variable or boolean flag.
 *
 * <p>Supports numeric range checks and boolean equality.</p>
 */
public final class TerrainBiomeCondition {
    public enum Operator {
        @SerializedName("eq")
        EQ,
        @SerializedName("gt")
        GT,
        @SerializedName("gte")
        GTE,
        @SerializedName("lt")
        LT,
        @SerializedName("lte")
        LTE,
        @SerializedName("between")
        BETWEEN
    }

    /**
     * The {@code variable} string resolved once at registry build time, so hot-path
     * evaluation switches on an enum ordinal instead of hashing/comparing a String.
     */
    public enum Variable {
        ELEVATION_M, TEMPERATURE_C, TEMPERATURE_SEASONALITY, PRECIPITATION_MM, PRECIPITATION_CV,
        MOISTURE, ARIDITY, TREE_MOISTURE, TREE_COVERAGE, SPARSITY, SLOPE, GROWING_SEASON_DAYS,
        OCEAN, SNOWY, BARE_SLOPE, MOUNTAIN, LOWLAND,
        VARIANT_NOISE, CHERRY_NOISE, PALE_NOISE, CLEARING_NOISE, FLOWER_NOISE,
        UNKNOWN
    }

    @SerializedName("variable")
    private String variable;

    @SerializedName("op")
    private Operator operator;

    @SerializedName("value")
    private Number value;

    @SerializedName("value2")
    private Number value2;

    @SerializedName("bool")
    private Boolean boolValue;

    /** Populated by {@link #resolve()}; Gson leaves transient fields at their default. */
    private transient Variable resolvedVariable;

    public String variable() {
        return variable;
    }

    public Operator operator() {
        return operator;
    }

    public float numericValue() {
        return value != null ? value.floatValue() : 0f;
    }

    public float numericValue2() {
        return value2 != null ? value2.floatValue() : 0f;
    }

    public Boolean boolValue() {
        return boolValue;
    }

    /** Resolves {@link #variable} to its enum once. Called from single-threaded registry build. */
    void resolve() {
        resolvedVariable = variable == null ? Variable.UNKNOWN : switch (variable) {
            case "elevationM" -> Variable.ELEVATION_M;
            case "temperatureC" -> Variable.TEMPERATURE_C;
            case "temperatureSeasonality" -> Variable.TEMPERATURE_SEASONALITY;
            case "precipitationMm" -> Variable.PRECIPITATION_MM;
            case "precipitationCv" -> Variable.PRECIPITATION_CV;
            case "moisture" -> Variable.MOISTURE;
            case "aridity" -> Variable.ARIDITY;
            case "treeMoisture" -> Variable.TREE_MOISTURE;
            case "treeCoverage" -> Variable.TREE_COVERAGE;
            case "sparsity" -> Variable.SPARSITY;
            case "slope" -> Variable.SLOPE;
            case "growingSeasonDays" -> Variable.GROWING_SEASON_DAYS;
            case "ocean" -> Variable.OCEAN;
            case "snowy" -> Variable.SNOWY;
            case "bareSlope" -> Variable.BARE_SLOPE;
            case "mountain" -> Variable.MOUNTAIN;
            case "lowland" -> Variable.LOWLAND;
            case "variantNoise" -> Variable.VARIANT_NOISE;
            case "cherryNoise" -> Variable.CHERRY_NOISE;
            case "paleNoise" -> Variable.PALE_NOISE;
            case "clearingNoise" -> Variable.CLEARING_NOISE;
            case "flowerNoise" -> Variable.FLOWER_NOISE;
            default -> Variable.UNKNOWN;
        };
    }

    /** Resolved variable enum. {@link #resolve()} must have run first (registry build does this). */
    public Variable resolvedVariable() {
        if (resolvedVariable == null) resolve();
        return resolvedVariable;
    }

    /**
     * Evaluate this condition against a climate sample.
     */
    public boolean evaluate(TerrainClimateSample sample) {
        if (boolValue != null) {
            boolean actual = getBooleanValue(sample);
            return actual == boolValue;
        }

        float actual = getNumericValue(sample);
        return evaluateNumeric(actual);
    }

    private boolean evaluateNumeric(float actual) {
        switch (operator) {
            case EQ:    return actual == numericValue();
            case GT:    return actual > numericValue();
            case GTE:   return actual >= numericValue();
            case LT:    return actual < numericValue();
            case LTE:   return actual <= numericValue();
            case BETWEEN: return actual >= numericValue() && actual <= numericValue2();
            default:    return false;
        }
    }

    private float getNumericValue(TerrainClimateSample sample) {
        return switch (resolvedVariable()) {
            case ELEVATION_M -> sample.elevationM();
            case TEMPERATURE_C -> sample.temperatureC();
            case TEMPERATURE_SEASONALITY -> sample.temperatureSeasonality();
            case PRECIPITATION_MM -> sample.precipitationMm();
            case PRECIPITATION_CV -> sample.precipitationCv();
            case MOISTURE -> sample.moisture();
            case ARIDITY -> sample.aridity();
            case TREE_MOISTURE -> sample.treeMoisture();
            case TREE_COVERAGE -> sample.treeCoverage();
            case SPARSITY -> sample.sparsity();
            case SLOPE -> sample.slope();
            case GROWING_SEASON_DAYS -> sample.growingSeasonDays();
            default -> Float.NaN;
        };
    }

    private boolean getBooleanValue(TerrainClimateSample sample) {
        return switch (resolvedVariable()) {
            case OCEAN -> sample.ocean();
            case SNOWY -> sample.snowy();
            case BARE_SLOPE -> sample.bareSlope();
            case MOUNTAIN -> sample.mountain();
            case LOWLAND -> sample.lowland();
            default -> false;
        };
    }
}
