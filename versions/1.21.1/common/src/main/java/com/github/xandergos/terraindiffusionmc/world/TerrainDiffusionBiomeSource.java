package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeCatalog;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeSettlement;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.BiomeClassifier;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** The subset of {@link #biomeIndexMap} this source will actually ever return. */
    private volatile List<Holder<Biome>> possibleBiomes = null;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private void requireBiomeIndexMap() {
        if (biomeIndexMap != null) return;

        // getNoiseBiome runs concurrently on multiple chunk-generation worker threads;
        // without this lock two threads can race to build the map and one can observe
        // a partially-published biomeIndexMap, causing getNoiseBiome to return null.
        synchronized (this) {
            if (biomeIndexMap != null) return;

            Map<Short, Holder<Biome>> resolved = new LinkedHashMap<>();
            List<Holder<Biome>> possible = new ArrayList<>();
            for (TerrainBiomeSettlement settlement : TerrainBiomeRegistry.instance().all()) {
                Holder<Biome> biome = resolveBiome(settlement);
                resolved.put(settlement.index(), biome);
                // Only overworld-capable entries may be advertised as possible. The catalog also
                // carries the Nether/End/Void biomes so their indices resolve, but this source
                // never returns them, and claiming them here makes vanilla's FeatureSorter build
                // one global feature order spanning all three dimensions at once. Vanilla's own
                // biomes happen to be mutually consistent, so that went unnoticed; a mod that
                // redefines biomes on both sides (Sengoku Jidai redefines minecraft:river and
                // minecraft:small_end_islands) makes the orders contradict and worldgen dies with
                // "Feature order cycle found". Rivers and cave biomes stay -- getNoiseBiome and
                // selectUndergroundBiome really do return those.
                if (settlement.canGenerateOverworld() && !possible.contains(biome)) {
                    possible.add(biome);
                }
            }
            possibleBiomes = Collections.unmodifiableList(possible);
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
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIndexMap();
        return possibleBiomes.stream();
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
            short caveBiomeIndex = selectUndergroundBiome(data, localX, localZ, blockX, blockZ, surfaceBiomeIndex, blockY);
            Holder<Biome> entry = biomeIndexMap.get(caveBiomeIndex);
            if (entry != null) return entry;
        }

        if (defaultEntry != null) return defaultEntry;

        // Vanilla surface generation calls .is() on this return value with no null check
        // (see SurfaceSystem.buildSurface), so this must never be null.
        return this.biomeLookup.getOrThrow(Biomes.PLAINS);
    }

    /**
     * Selects the biome for a position below the terrain surface.
     *
     * <p>Vanilla does not treat the underground as a solid slab of cave biomes: the surface
     * biome extends downward, and lush/dripstone caves are sparse patches picked by the
     * climate sampler (humidity for lush, continentalness for dripstone) within a depth band,
     * with the deep dark reserved for the bottom of the world. This reproduces that layout
     * using the terrain heightmap for depth and a coherent world-space noise field for the
     * patch shapes.</p>
     */
    private static short selectUndergroundBiome(HeightmapData data, int localX, int localZ,
                                                int blockX, int blockZ,
                                                short surfaceBiomeIndex, int blockY) {
        int surfaceHeight = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
        int depthBelowSurface = surfaceHeight - blockY;

        // Above the surface, and in the shallow band just below it, the surface biome applies.
        if (depthBelowSurface < CAVE_BIOME_MIN_DEPTH) return surfaceBiomeIndex;

        // The deep dark belongs to the bottom of the world rather than to anything merely deep
        // under tall terrain, so it needs both an absolute and a relative depth.
        if (blockY < DEEP_DARK_MAX_Y && depthBelowSurface > DEEP_DARK_MIN_DEPTH
                && regionNoise(DEEP_DARK_NOISE_SEED, blockX, blockZ) > DEEP_DARK_THRESHOLD) {
            return TerrainBiomeCatalog.DEEP_DARK;
        }

        // Sengoku Jidai's two cave biomes, when that mod is installed and this is inside the
        // Japan region. Checked ahead of the vanilla pair so they read as the regional variant
        // rather than as leftovers in the gaps; each still claims only a slice of its noise
        // field, so vanilla caves keep the majority.
        if (isInJapanRegion(blockX, blockZ)) {
            if (SUISHO_CAVES_INDEX >= 0 && isSnowySurfaceBiome(surfaceBiomeIndex)
                    && regionNoise(SUISHO_CAVES_NOISE_SEED, blockX, blockZ) > SUISHO_CAVES_THRESHOLD) {
                return SUISHO_CAVES_INDEX;
            }
            if (SENGOKU_CAVERNS_INDEX >= 0 && isInlandBiome(surfaceBiomeIndex)
                    && regionNoise(SENGOKU_CAVERNS_NOISE_SEED, blockX, blockZ) > SENGOKU_CAVERNS_THRESHOLD) {
                return SENGOKU_CAVERNS_INDEX;
            }
        }

        // Lush caves under humid surfaces; vanilla selects them on humidity >= 0.7.
        if (isHumidBiome(surfaceBiomeIndex)
                && regionNoise(LUSH_CAVES_NOISE_SEED, blockX, blockZ) > LUSH_CAVES_THRESHOLD) {
            return TerrainBiomeCatalog.LUSH_CAVES;
        }

        // Dripstone caves inland; vanilla selects them on continentalness >= 0.8.
        if (isInlandBiome(surfaceBiomeIndex)
                && regionNoise(DRIPSTONE_NOISE_SEED, blockX, blockZ) > DRIPSTONE_THRESHOLD) {
            return TerrainBiomeCatalog.DRIPSTONE_CAVES;
        }

        // Everywhere else the surface biome carries on underground, as it does in vanilla.
        return surfaceBiomeIndex;
    }

    /** Depth below the surface where cave biomes start (vanilla depth parameter ~0.2). */
    private static final int CAVE_BIOME_MIN_DEPTH = 24;
    /** Depth below the surface the deep dark needs (vanilla depth parameter ~1.1). */
    private static final int DEEP_DARK_MIN_DEPTH = 100;
    /** The deep dark is additionally confined to the bottom of the world. */
    private static final int DEEP_DARK_MAX_Y = 0;
    /** Width in blocks of one cave-biome region cell. */
    private static final float CAVE_REGION_SIZE_BLOCKS = 256f;

    // Thresholds are the fraction of eligible area each biome covers: ~30%, ~40% and ~30%.
    private static final int DEEP_DARK_NOISE_SEED = 55678;
    private static final float DEEP_DARK_THRESHOLD = 0.25f;
    private static final int LUSH_CAVES_NOISE_SEED = 91027;
    private static final float LUSH_CAVES_THRESHOLD = 0.10f;
    private static final int DRIPSTONE_NOISE_SEED = 33419;
    private static final float DRIPSTONE_THRESHOLD = 0.25f;
    // Higher thresholds than the vanilla pair: these are a regional accent, so they take roughly
    // a quarter of eligible area rather than a third.
    private static final int SUISHO_CAVES_NOISE_SEED = 61183;
    private static final float SUISHO_CAVES_THRESHOLD = 0.35f;
    private static final int SENGOKU_CAVERNS_NOISE_SEED = 74902;
    private static final float SENGOKU_CAVERNS_THRESHOLD = 0.40f;

    private static final TerrainBiomeRegistry REGISTRY = TerrainBiomeRegistry.instance();

    /**
     * Catalog indices for Sengoku's cave biomes, or -1 when that mod isn't installed. Resolved by
     * key rather than being {@code TerrainBiomeCatalog} constants because these entries are
     * mod-gated -- {@link TerrainBiomeRegistry#build} drops them entirely without the mod, and a
     * fixed constant would then point at nothing.
     */
    private static final short SENGOKU_CAVERNS_INDEX = optionalBiomeIndex("sengoku:caverns");
    private static final short SUISHO_CAVES_INDEX = optionalBiomeIndex("sengoku:suisho_caves");

    private static short optionalBiomeIndex(String key) {
        TerrainBiomeSettlement settlement = REGISTRY.byKey(key);
        return settlement != null ? settlement.index() : (short) -1;
    }

    /**
     * Whether this column is inside the Japan region, sharing the exact field the catalog's
     * {@code japanRegion} rules use so cave placement and surface placement can't disagree about
     * where the region is.
     */
    private static boolean isInJapanRegion(int blockX, int blockZ) {
        return BiomeClassifier.sampleJapanRegion(blockX, blockZ) >= 0f;
    }

    /**
     * Samples the cave-region noise field in world space, so patches do not repeat per
     * heightmap tile.
     */
    private static float regionNoise(int seed, int blockX, int blockZ) {
        return valueNoise(seed, blockX / CAVE_REGION_SIZE_BLOCKS, blockZ / CAVE_REGION_SIZE_BLOCKS);
    }

    /** Surface biomes vanilla would place at humidity >= 0.7, where lush caves form. */
    private static boolean isHumidBiome(short index) {
        switch (index) {
            case TerrainBiomeCatalog.JUNGLE:
            case TerrainBiomeCatalog.SPARSE_JUNGLE:
            case TerrainBiomeCatalog.BAMBOO_JUNGLE:
            case TerrainBiomeCatalog.SWAMP:
            case TerrainBiomeCatalog.MANGROVE_SWAMP:
            case TerrainBiomeCatalog.DARK_FOREST:
            case TerrainBiomeCatalog.PALE_GARDEN:
            case TerrainBiomeCatalog.OLD_GROWTH_SPRUCE_TAIGA:
            case TerrainBiomeCatalog.OLD_GROWTH_PINE_TAIGA:
            case TerrainBiomeCatalog.MUSHROOM_FIELDS:
                return true;
            default:
                return false;
        }
    }

    /**
     * Cold surfaces, under which Sengoku's ice-crystal caves form. Deliberately the frozen
     * *land* biomes only: the oceanic ones sit under water rather than under cave-bearing rock.
     */
    private static boolean isSnowySurfaceBiome(short index) {
        switch (index) {
            case TerrainBiomeCatalog.SNOWY_PLAINS:
            case TerrainBiomeCatalog.ICE_SPIKES:
            case TerrainBiomeCatalog.SNOWY_TAIGA:
            case TerrainBiomeCatalog.SNOWY_SLOPES:
            case TerrainBiomeCatalog.FROZEN_PEAKS:
            case TerrainBiomeCatalog.GROVE:
                return true;
            default:
                return false;
        }
    }

    /** Stands in for vanilla's continentalness >= 0.8 requirement for dripstone caves. */
    private static boolean isInlandBiome(short index) {
        switch (index) {
            case TerrainBiomeCatalog.WARM_OCEAN:
            case TerrainBiomeCatalog.LUKEWARM_OCEAN:
            case TerrainBiomeCatalog.DEEP_LUKEWARM_OCEAN:
            case TerrainBiomeCatalog.OCEAN:
            case TerrainBiomeCatalog.DEEP_OCEAN:
            case TerrainBiomeCatalog.COLD_OCEAN:
            case TerrainBiomeCatalog.DEEP_COLD_OCEAN:
            case TerrainBiomeCatalog.FROZEN_OCEAN:
            case TerrainBiomeCatalog.DEEP_FROZEN_OCEAN:
            case TerrainBiomeCatalog.BEACH:
            case TerrainBiomeCatalog.SNOWY_BEACH:
            case TerrainBiomeCatalog.STONY_SHORE:
            case TerrainBiomeCatalog.RIVER:
            case TerrainBiomeCatalog.FROZEN_RIVER:
                return false;
            default:
                return true;
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
