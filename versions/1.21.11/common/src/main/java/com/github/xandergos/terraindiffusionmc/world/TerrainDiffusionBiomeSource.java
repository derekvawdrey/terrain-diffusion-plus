package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeCatalog;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeProfile;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
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
    private Map<Short, Holder<Biome>> biomeIndexMap = null;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private void requireBiomeIndexMap() {
        if (biomeIndexMap != null) return;

        Map<Short, Holder<Biome>> resolved = new LinkedHashMap<>();
        for (TerrainBiomeProfile profile : TerrainBiomeCatalog.all()) {
            resolved.put(profile.index(), resolveBiome(profile));
        }
        biomeIndexMap = Collections.unmodifiableMap(resolved);
    }

    private Holder<Biome> resolveBiome(TerrainBiomeProfile profile) {
        Holder<Biome> primary = resolveBiomeKey(profile.key());
        if (primary != null) return primary;

        Holder<Biome> fallback = resolveBiomeKey(profile.fallbackKey());
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
        return ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(namespace, path));
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIndexMap();
        return biomeIndexMap.values().stream().distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIndexMap();
        Holder<Biome> defaultEntry = biomeIndexMap.get(TerrainBiomeCatalog.PLAINS);

        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
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
            Holder<Biome> entry = biomeIndexMap.get(data.biomeIndexes[localZ][localX]);
            if (entry != null) return entry;
        }

        return defaultEntry;
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
