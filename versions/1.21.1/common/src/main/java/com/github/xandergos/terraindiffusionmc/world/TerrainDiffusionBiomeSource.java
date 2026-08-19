package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeCatalog;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeSettlement;
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
    /** Which {@link OverworldBiomeDelegate} generation {@link #possibleBiomes} was built against. */
    private volatile int delegateStamp = -1;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private void requireBiomeIndexMap() {
        // The delegate resolves when the server loads its levels, which can be after this map was
        // first built; its stamp changing means the set of biomes we may return has grown.
        if (biomeIndexMap != null && delegateStamp == OverworldBiomeDelegate.stamp()) return;

        // getNoiseBiome runs concurrently on multiple chunk-generation worker threads;
        // without this lock two threads can race to build the map and one can observe
        // a partially-published biomeIndexMap, causing getNoiseBiome to return null.
        synchronized (this) {
            int stamp = OverworldBiomeDelegate.stamp();
            if (biomeIndexMap != null && delegateStamp == stamp) return;

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
                // redefines biomes on both sides (say minecraft:river and
                // minecraft:small_end_islands) makes the orders contradict and worldgen dies with
                // "Feature order cycle found". Rivers and cave biomes stay -- getNoiseBiome and
                // selectUndergroundBiome really do return those.
                if (settlement.canGenerateOverworld() && !possible.contains(biome)) {
                    possible.add(biome);
                }
            }
            // Biomes the pack places only below the surface. selectUndergroundBiome really can
            // return these, and a biome source that returns something it never advertised breaks
            // feature sorting and structure placement. They are overworld biomes by construction
            // -- they came from an overworld table -- so they carry no cross-dimension risk.
            for (Holder<Biome> underground : OverworldBiomeDelegate.undergroundBiomes()) {
                if (!possible.contains(underground)) possible.add(underground);
            }
            possibleBiomes = Collections.unmodifiableList(possible);
            biomeIndexMap = Collections.unmodifiableMap(resolved);
            delegateStamp = stamp;
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

            Holder<Biome> surfaceBiome = biomeIndexMap.get(surfaceBiomeIndex);
            if (surfaceBiome != null) {
                Holder<Biome> delegated = delegatedUndergroundBiome(
                        data, localX, localZ, blockX, blockZ, blockY, surfaceBiome);
                if (delegated != null) return delegated;
            }

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
     * Asks the pack's own overworld table what belongs this far under our surface biome, per
     * {@link OverworldBiomeDelegate}. Null means it had nothing to say and the built-in placement
     * below should decide -- either because no pack table was available, or because it puts
     * nothing but the surface biome here.
     */
    private static Holder<Biome> delegatedUndergroundBiome(HeightmapData data, int localX, int localZ,
                                                           int blockX, int blockZ, int blockY,
                                                           Holder<Biome> surfaceBiome) {
        int surfaceHeight = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
        return OverworldBiomeDelegate.undergroundBiome(surfaceBiome, surfaceHeight - blockY, blockY,
                regionNoise(EROSION_JITTER_NOISE_SEED, blockX, blockZ),
                regionNoise(CONTINENTALNESS_JITTER_NOISE_SEED, blockX, blockZ),
                regionNoise(WEIRDNESS_JITTER_NOISE_SEED, blockX, blockZ));
    }

    /**
     * Selects the biome for a position below the terrain surface.
     *
     * <p>This is the fallback for packs with no multi-noise overworld to delegate to. It
     * approximates what vanilla's table would have done: the surface biome extends downward, and
     * lush/dripstone caves are sparse patches picked on humidity and continentalness within a
     * depth band, with the deep dark reserved for the bottom of the world -- using the terrain
     * heightmap for depth and a coherent world-space noise field for the patch shapes.</p>
     *
     * <p>It deliberately knows about no mod's biomes. A mod that adds cave biomes puts them in the
     * overworld table, and {@link OverworldBiomeDelegate} places them from there, with the mod's
     * own parameters rather than a rule written here on its behalf.</p>
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
    private static final int CAVE_BIOME_MIN_DEPTH = OverworldBiomeDelegate.MIN_DEPTH_BELOW_SURFACE;
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

    // Fields that break the delegated underground into patches. Separate seeds from the ones
    // above so the two placements do not share their patch shapes.
    private static final int EROSION_JITTER_NOISE_SEED = 61183;
    private static final int CONTINENTALNESS_JITTER_NOISE_SEED = 74902;
    private static final int WEIRDNESS_JITTER_NOISE_SEED = 18844;

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
