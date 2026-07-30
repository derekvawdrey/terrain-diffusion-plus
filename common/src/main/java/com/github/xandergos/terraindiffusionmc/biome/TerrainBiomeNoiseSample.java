package com.github.xandergos.terraindiffusionmc.biome;

/**
 * Noise fields referenced by rule {@code noiseConditions}, keyed by name in
 * {@link #value(TerrainBiomeCondition.Variable)}. A plain record avoids allocating a {@code Map} per
 * classified pixel.
 */
public record TerrainBiomeNoiseSample(
        float variantNoise,
        float cherryNoise,
        float paleNoise,
        float clearingNoise,
        float flowerNoise
) {
    /** Returns {@link Float#NaN} for a variable this sample doesn't carry. */
    public float value(TerrainBiomeCondition.Variable variable) {
        return switch (variable) {
            case VARIANT_NOISE -> variantNoise;
            case CHERRY_NOISE -> cherryNoise;
            case PALE_NOISE -> paleNoise;
            case CLEARING_NOISE -> clearingNoise;
            case FLOWER_NOISE -> flowerNoise;
            default -> Float.NaN;
        };
    }
}
