package com.github.xandergos.terraindiffusionmc.biome;

/**
 * Version-independent biome definition.
 *
 * <p>The numeric index is an internal catalog index only. It is not a Minecraft
 * biome ID and must not be treated as a registry contract.</p>
 */
public record TerrainBiomeProfile(
        short index,
        String key,
        String fallbackKey,
        TerrainBiomeKind kind,
        float minTemp,
        float maxTemp,
        float minMoisture,
        float maxMoisture,
        float minPrecip,
        float maxPrecip,
        float minElevation,
        float maxElevation,
        float minSlope,
        float maxSlope,
        float minSparsity,
        float maxSparsity,
        int priority,
        int color
) {
    public boolean canGenerateOverworld() {
        return kind == TerrainBiomeKind.OVERWORLD
                || kind == TerrainBiomeKind.OCEAN
                || kind == TerrainBiomeKind.RIVER
                || kind == TerrainBiomeKind.BEACH
                || kind == TerrainBiomeKind.MOUNTAIN
                || kind == TerrainBiomeKind.CAVE;
    }
}
