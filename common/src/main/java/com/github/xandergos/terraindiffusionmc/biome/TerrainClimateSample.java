package com.github.xandergos.terraindiffusionmc.biome;

/**
 * Climate and terrain variables used to select a logical vanilla biome.
 *
 * <p>{@code elevationM} is <b>signed</b>: negative over ocean, where it is the real seafloor depth
 * in meters. The {@code mountain}/{@code lowland}/{@code snowy} flags are derived from a
 * zero-clamped copy instead, since they are land concepts, so only ocean-zone rules ever observe a
 * negative value.</p>
 */
public record TerrainClimateSample(
        float elevationM,
        float temperatureC,
        float temperatureSeasonality,
        float precipitationMm,
        float precipitationCv,
        float moisture,
        float aridity,
        float treeMoisture,
        float treeCoverage,
        float sparsity,
        float slope,
        float growingSeasonDays,
        boolean ocean,
        boolean snowy,
        boolean bareSlope,
        boolean mountain,
        boolean lowland
) {}
