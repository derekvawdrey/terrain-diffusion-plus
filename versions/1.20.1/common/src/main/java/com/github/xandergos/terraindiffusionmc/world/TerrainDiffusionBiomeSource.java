package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeCatalog;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeSettlement;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class TerrainDiffusionBiomeSource extends BiomeSource {
    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));

    private final HolderGetter<Biome> biomeLookup;
    private volatile Map<Short, Holder<Biome>> biomeIndexMap = null;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC.codec();
    }

    private void requireBiomeIndexMap() {
        if (biomeIndexMap != null) return;

        // getNoiseBiome runs concurrently on multiple chunk-generation worker threads;
        // without this lock two threads can race to build the map and one can observe
        // a partially-published biomeIndexMap, causing getNoiseBiome to return null.
        synchronized (this) {
            if (biomeIndexMap != null) return;

            Map<Short, Holder<Biome>> resolved = new LinkedHashMap<>();
            for (TerrainBiomeSettlement settlement : TerrainBiomeRegistry.instance().all()) {
                resolved.put(settlement.index(), resolveBiome(settlement));
            }
            biomeIndexMap = Collections.unmodifiableMap(resolved);
        }
    }

    private Holder<Biome> resolveBiome(TerrainBiomeSettlement settlement) {
        Holder<Biome> primary = resolveBiomeKey(settlement.key());
        if (primary != null) return primary;

        Holder<Biome> fallback = resolveBiomeKey(settlement.fallbackKey());
        if (fallback != null) return fallback;

        return this.biomeLookup.getOrThrow(Biomes.PLAINS);
    }

    private Holder<Biome> resolveBiomeKey(String key) {
        try {
            Optional<Holder.Reference<Biome>> holder = this.biomeLookup.get(biomeResourceKey(key));
            return holder.<Holder<Biome>>map(h -> h).orElse(null);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static ResourceKey<Biome> biomeResourceKey(String key) {
        int sep = key.indexOf(':');
        String namespace = sep >= 0 ? key.substring(0, sep) : "minecraft";
        String path = sep >= 0 ? key.substring(sep + 1) : key;
        return ResourceKey.create(Registries.BIOME, new ResourceLocation(namespace, path));
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIndexMap();
        return biomeIndexMap.values().stream().distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIndexMap();
        Holder<Biome> defaultEntry = biomeIndexMap.get(TerrainBiomeRegistry.instance().defaultBiomeIndex());

        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockY = QuartPos.toBlock(y);
        int blockZ = QuartPos.toBlock(z);

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = blockX >> tileShift;
        int tileZ = blockZ >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        if (data != null && data.biomeIndexes != null) {
            int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));
            short surfaceBiomeIndex = data.biomeIndexes[localZ][localX];
            short caveBiomeIndex = selectUndergroundBiome(data, localX, localZ, surfaceBiomeIndex, blockY);
            Holder<Biome> entry = biomeIndexMap.get(caveBiomeIndex);
            if (entry != null) return entry;
        }

        if (defaultEntry != null) return defaultEntry;

        // Vanilla surface generation calls .is() on this return value with no null check
        // (see SurfaceSystem.buildSurface), so this must never be null.
        return this.biomeLookup.getOrThrow(Biomes.PLAINS);
    }

    /**
     * Selects an underground biome based on depth below the terrain surface and the surface
     * biome's climate characteristics. Returns the surface biome index for positions above
     * the cave threshold.
     */
    private static short selectUndergroundBiome(HeightmapData data, int localX, int localZ,
                                                short surfaceBiomeIndex, int blockY) {
        int surfaceHeight = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
        int depthBelowSurface = surfaceHeight - blockY;

        if (depthBelowSurface < 0) return surfaceBiomeIndex;
        if (depthBelowSurface < 30) return surfaceBiomeIndex;

        short caveKind = selectCaveBiome(localX, localZ, surfaceBiomeIndex, depthBelowSurface);
        return caveKind;
    }

    private static short selectCaveBiome(int localX, int localZ, short surfaceBiomeIndex, int depthBelowSurface) {
        if (depthBelowSurface > 100) {
            return TerrainBiomeCatalog.DEEP_DARK;
        }

        float caveNoise = valueNoise(surfaceCaveBiomeSeed, localX / 256f, localZ / 256f);

        boolean isWarmHumid = isWarmHumidBiome(surfaceBiomeIndex);
        boolean isFrozen = isFrozenBiome(surfaceBiomeIndex);

        if (isFrozen) {
            return TerrainBiomeCatalog.DRIPSTONE_CAVES;
        }

        if (isWarmHumid) {
            if (caveNoise > 0.15f) {
                return TerrainBiomeCatalog.LUSH_CAVES;
            }
            return TerrainBiomeCatalog.DRIPSTONE_CAVES;
        }

        if (caveNoise > 0.4f) {
            return TerrainBiomeCatalog.LUSH_CAVES;
        }
        return TerrainBiomeCatalog.DRIPSTONE_CAVES;
    }

    private static final int surfaceCaveBiomeSeed = 55678;

    private static boolean isWarmHumidBiome(short index) {
        switch (index) {
            case TerrainBiomeCatalog.JUNGLE:
            case TerrainBiomeCatalog.SPARSE_JUNGLE:
            case TerrainBiomeCatalog.BAMBOO_JUNGLE:
            case TerrainBiomeCatalog.SWAMP:
            case TerrainBiomeCatalog.MANGROVE_SWAMP:
            case TerrainBiomeCatalog.DARK_FOREST:
            case TerrainBiomeCatalog.WARM_OCEAN:
                return true;
            default:
                return false;
        }
    }

    private static boolean isFrozenBiome(short index) {
        switch (index) {
            case TerrainBiomeCatalog.SNOWY_PLAINS:
            case TerrainBiomeCatalog.ICE_SPIKES:
            case TerrainBiomeCatalog.SNOWY_TAIGA:
            case TerrainBiomeCatalog.SNOWY_SLOPES:
            case TerrainBiomeCatalog.FROZEN_PEAKS:
            case TerrainBiomeCatalog.JAGGED_PEAKS:
            case TerrainBiomeCatalog.FROZEN_RIVER:
            case TerrainBiomeCatalog.FROZEN_OCEAN:
            case TerrainBiomeCatalog.DEEP_FROZEN_OCEAN:
                return true;
            default:
                return false;
        }
    }

    private static float valueNoise(int seed, float x, float y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = smoothstep(x - x0);
        float fy = smoothstep(y - y0);
        float a = hashUnit(seed, x0, y0);
        float b = hashUnit(seed, x0 + 1, y0);
        float c = hashUnit(seed, x0, y0 + 1);
        float d = hashUnit(seed, x0 + 1, y0 + 1);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fy);
    }

    private static float hashUnit(int seed, int x, int y) {
        long value = seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) y * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 40) / (float) (1L << 24)) * 2.0f - 1.0f;
    }

    private static float smoothstep(float v) {
        return v * v * (3.0f - 2.0f * v);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius, int horizontalBlockCheckInterval, int verticalBlockCheckInterval, Predicate<Holder<Biome>> predicate, Climate.Sampler noiseSampler, LevelReader world) {
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius, int blockCheckInterval, Predicate<Holder<Biome>> predicate, RandomSource random, boolean bl, Climate.Sampler noiseSampler) {
        return null;
    }
}
