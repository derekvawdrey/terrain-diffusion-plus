package com.github.xandergos.terraindiffusionmc.biome;

/** Climate and terrain variables used to select a logical vanilla biome. */
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
