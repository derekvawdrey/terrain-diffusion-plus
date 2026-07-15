package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.dimension.DimensionType;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies scale-specific dimension types during world creation.
 */
public final class WorldScaleDimensionOptions {
    private static final String MOD_ID = "terrain-diffusion-mc";

    private WorldScaleDimensionOptions() {
    }

    /**
     * Returns whether the holder's overworld uses Terrain Diffusion generation.
     */
    public static boolean usesTerrainDiffusion(DimensionOptionsRegistryHolder dimensions) {
        return dimensions.getOrEmpty(DimensionOptions.OVERWORLD)
                .map(DimensionOptions::chunkGenerator)
                .map(chunkGenerator -> chunkGenerator.getBiomeSource() instanceof TerrainDiffusionBiomeSource)
                .orElse(false);
    }

    /**
     * Replaces only the overworld dimension type with the registered variant for the selected scale.
     */
    public static DimensionOptionsRegistryHolder withScaleDimensionType(
            RegistryEntryLookup<DimensionType> dimensionTypeLookup,
            DimensionOptionsRegistryHolder dimensions,
            int configuredScale
    ) {
        DimensionOptions overworldOptions = dimensions.getOrEmpty(DimensionOptions.OVERWORLD).orElse(null);
        if (overworldOptions == null) {
            return dimensions;
        }

        int scale = WorldScaleManager.clampScale(configuredScale);
        RegistryKey<DimensionType> dimensionTypeKey = RegistryKey.of(
                RegistryKeys.DIMENSION_TYPE,
                Identifier.of(MOD_ID, "terrain_diffusion_scale_" + scale)
        );
        RegistryEntry.Reference<DimensionType> dimensionTypeEntry = dimensionTypeLookup.getOrThrow(dimensionTypeKey);

        DimensionOptions updatedOverworldOptions = new DimensionOptions(
                dimensionTypeEntry,
                overworldOptions.chunkGenerator()
        );
        Map<RegistryKey<DimensionOptions>, DimensionOptions> updatedDimensionMap =
                new HashMap<>(dimensions.dimensions());
        updatedDimensionMap.put(DimensionOptions.OVERWORLD, updatedOverworldOptions);
        return new DimensionOptionsRegistryHolder(updatedDimensionMap);
    }
}
