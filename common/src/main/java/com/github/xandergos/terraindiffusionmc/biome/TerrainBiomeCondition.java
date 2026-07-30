package com.github.xandergos.terraindiffusionmc.biome;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

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

    /**
     * Evaluate this condition against a climate sample.
     */
    public boolean evaluate(TerrainClimateSample sample) {
        if (boolValue != null) {
            boolean actual = getBooleanValue(sample, variable);
            return actual == boolValue;
        }

        float actual = getNumericValue(sample, variable);
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

    private float getNumericValue(TerrainClimateSample sample, String var) {
        return switch (var) {
            case "elevationM" -> sample.elevationM();
            case "temperatureC" -> sample.temperatureC();
            case "temperatureSeasonality" -> sample.temperatureSeasonality();
            case "precipitationMm" -> sample.precipitationMm();
            case "precipitationCv" -> sample.precipitationCv();
            case "moisture" -> sample.moisture();
            case "aridity" -> sample.aridity();
            case "treeMoisture" -> sample.treeMoisture();
            case "treeCoverage" -> sample.treeCoverage();
            case "sparsity" -> sample.sparsity();
            case "slope" -> sample.slope();
            case "growingSeasonDays" -> sample.growingSeasonDays();
            default -> Float.NaN;
        };
    }

    private boolean getBooleanValue(TerrainClimateSample sample, String var) {
        return switch (var) {
            case "ocean" -> sample.ocean();
            case "snowy" -> sample.snowy();
            case "bareSlope" -> sample.bareSlope();
            case "mountain" -> sample.mountain();
            case "lowland" -> sample.lowland();
            default -> false;
        };
    }
}
