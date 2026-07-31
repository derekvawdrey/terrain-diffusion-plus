package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.DetailedRiverCarver;
import com.github.xandergos.terraindiffusionmc.hydrology.FluvialRiverNetwork;
import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyParallel;
import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyProvider;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Random;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Provides terrain heightmap and biome data from the local WorldPipeline.
 *
 * <p>When scale=1 the pipeline is sampled at native model resolution directly.
 * When scale>1 the pipeline is sampled at native resolution and the result is
 * bilinearly upsampled, giving 1 block = nativeResolution/scale.
 */
public final class LocalTerrainProvider {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);

    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 1f/24f, 3, 2f, 0.5f);
    private static final FastNoiseLite ELEV_NOISE_FINE   = makeFnl(88888, 1f/6f,  2, 2f, 0.6f);

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static final class HeightmapData {
        /** Sentinel used in riverWaterSurface when no fluvial water is present. */
        public static final short NO_FLUVIAL_WATER = Short.MIN_VALUE;

        public final short[][] heightmap;
        public final short[][] biomeIndexes;
        public final short[][] riverWater;
        /** Water surface in model elevation metres; convert with HeightConverter before block placement. */
        public final short[][] riverWaterSurface;
        public final int width;
        public final int height;

        public HeightmapData(short[][] heightmap, short[][] biomeIndexes, int width, int height) {
            this(heightmap, biomeIndexes, null, null, width, height);
        }

        public HeightmapData(short[][] heightmap, short[][] biomeIndexes, short[][] riverWater,
                             int width, int height) {
            this(heightmap, biomeIndexes, riverWater, null, width, height);
        }

        public HeightmapData(short[][] heightmap, short[][] biomeIndexes, short[][] riverWater,
                             short[][] riverWaterSurface, int width, int height) {
            this.heightmap = heightmap;
            this.biomeIndexes = biomeIndexes;
            this.riverWater = riverWater;
            this.riverWaterSurface = riverWaterSurface;
            this.width = width;
            this.height = height;
        }
    }

    public static final class RiverTerrainData {
        public final float[] elevation;
        public final float[] climate;
        public final short[] biomeIndexes;
        public final byte[] waterMask;
        /** Stable fluvial water surface in model elevation metres; NaN means no river/lake water. */
        public final float[] waterSurface;
        public final int width;
        public final int height;

        public RiverTerrainData(float[] elevation, float[] climate, short[] biomeIndexes, byte[] waterMask,
                                int width, int height) {
            this(elevation, climate, biomeIndexes, waterMask, null, width, height);
        }

        public RiverTerrainData(float[] elevation, float[] climate, short[] biomeIndexes, byte[] waterMask,
                                float[] waterSurface, int width, int height) {
            this.elevation = elevation;
            this.climate = climate;
            this.biomeIndexes = biomeIndexes;
            this.waterMask = waterMask;
            this.waterSurface = waterSurface;
            this.width = width;
            this.height = height;
        }
    }

    /** Compact data sent by the explorer detail endpoint. Temperature is encoded in centi-degrees Celsius. */
    public static final class ExplorerDetailData {
        public final short[] elevation;
        public final short[] temperatureCentiC;
        public final short[] biomeIndexes;
        public final byte[] waterMask;
        public final short[] waterSurface;
        public final int width;
        public final int height;

        public ExplorerDetailData(short[] elevation, short[] temperatureCentiC, short[] biomeIndexes,
                                  byte[] waterMask, short[] waterSurface, int width, int height) {
            this.elevation = elevation;
            this.temperatureCentiC = temperatureCentiC;
            this.biomeIndexes = biomeIndexes;
            this.waterMask = waterMask;
            this.waterSurface = waterSurface;
            this.width = width;
            this.height = height;
        }
    }

    private static record CacheKey(long seed, int scale, boolean blockLowAltitudeSources, int i1, int j1, int i2, int j2) {}
    private static record CacheEntry(HeightmapData data, AtomicLong lastAccessed, long bytes) {}

    private static final int MAX_CACHE_ENTRIES = TerrainDiffusionConfig.terrainRegionCacheMaxEntries();
    private static final long MAX_CACHE_BYTES = TerrainDiffusionConfig.terrainRegionCacheMaxBytes();
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();
    private static final AtomicLong CACHE_BYTES = new AtomicLong();
    private static final Map<CacheKey, Future<HeightmapData>> PENDING = new ConcurrentHashMap<>();

    /**
     * Sized by {@link TerrainDiffusionConfig#inferenceWorkerThreads()}: 1 when models are
     * offloaded between stages (GPU-slot swapping already serializes inference; more threads
     * there would only add queuing overhead and risk swap thrashing), otherwise a small pool
     * since {@link com.github.xandergos.terraindiffusionmc.infinitetensor.MemoryTileStore} and
     * {@link com.github.xandergos.terraindiffusionmc.infinitetensor.InfiniteTensor} are
     * per-tensor-locked and already safe for concurrent access.
     */
    private static final ExecutorService INFERENCE_EXECUTOR = Executors.newFixedThreadPool(
            TerrainDiffusionConfig.inferenceWorkerThreads(), new ThreadFactory() {
                private final AtomicInteger index = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "terrain-diffusion-inference-" + index.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    /**
     * Guards {@link WorldPipeline#setSeed} against running concurrently with in-flight
     * generation. Normal fetches hold the read lock (many can run at once); a seed change
     * takes the write lock, so it waits for in-flight fetches to finish and blocks new ones
     * until {@code MemoryTileStore} has been cleared for the new seed. Without this, a fetch
     * still computing under the old seed could cache its result after the clear and poison
     * the pipeline's tensor cache with stale-seed data (the cache isn't keyed by seed).
     */
    private static final ReentrantReadWriteLock SEED_LOCK = new ReentrantReadWriteLock();

    private static volatile LocalTerrainProvider INSTANCE;
    private static long instanceSeed;

    private final WorldPipeline pipeline;
    private final HydrologyProvider hydrologyProvider;

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
        this.hydrologyProvider = new HydrologyProvider(this::computeHydrologyTile);
    }

    /** Seed is 64-bit world seed. Creates provider once; later worlds only update seed and clear caches (lightweight). */
    public static synchronized void init(long seed) {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) throw new IllegalStateException("PipelineModels failed to load");
        if (INSTANCE == null) {
            INSTANCE = new LocalTerrainProvider(seed, models);
            instanceSeed = seed;
        } else if (instanceSeed != seed) {
            INSTANCE.pipeline.setSeed(seed);
            INSTANCE.hydrologyProvider.clear();
            instanceSeed = seed;
            clearRegionCaches();
        }
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE != null) return INSTANCE;

        synchronized(INIT_LOCK) {
            if (INSTANCE != null) return INSTANCE;
            PipelineModels.awaitLoad();
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) throw new IllegalStateException("PipelineModels failed to load");
            INSTANCE = new LocalTerrainProvider(0L, models);
            instanceSeed = 0L;
        }

        return INSTANCE;
    }

    public static void clearCache() {
        clearRegionCaches();
        LocalTerrainProvider provider = INSTANCE;
        if (provider != null) {
            provider.hydrologyProvider.clear();
            provider.pipeline.clearCaches();
        }
    }

    // =========================================================================
    // Explorer API — all pipeline calls routed through INFERENCE_EXECUTOR
    // =========================================================================

    /** Returns the current world seed used by the pipeline. */
    public static long getSeed() {
        return instanceSeed;
    }

    /**
     * Run elevation and climate inference on the inference thread.
     *
     * @return float[2]: [0] = elev (H*W), [1] = climate (5*H*W, or null)
     */
    public static float[][] getPipelineData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.get(i1, j1, i2, j2, withClimate));
    }

    /**
     * Fetch a coarse tensor slice on the inference thread.
     * Coordinates are in coarse index units (1 unit = 256 native pixels).
     *
     * @return FloatTensor with shape [7, ci1-ci0, cj1-cj0]
     */
    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    /** Build canonical terrain and hydrology at the exact scale selected for the active world. */
    public static RiverTerrainData getRiverTerrainData(int i1, int j1, int i2, int j2, boolean withBiomes) throws Exception {
        return submitToInferenceThread(() -> {
            int scale = WorldScaleManager.getCurrentScale();
            return getInstance().buildRiverTerrainData(i1, j1, i2, j2, scale, withBiomes);
        });
    }

    /** Explorer detail uses the same canonical scale, cache and water arrays as chunk generation. */
    public static ExplorerDetailData getExplorerDetailData(int i1, int j1, int i2, int j2) throws Exception {
        return submitToInferenceThread(() -> {
            int scale = WorldScaleManager.getCurrentScale();
            return getInstance().buildExplorerDetailData(i1, j1, i2, j2, scale);
        });
    }

    /**
     * Change the world seed used by the pipeline and clear all caches.
     * Note: this also affects terrain generation for new Minecraft chunks.
     */
    public static void changeSeedFromExplorer(long newSeed) throws Exception {
        submitExclusiveToInferenceThread(() -> {
            LocalTerrainProvider provider = getInstance();
            provider.pipeline.setSeed(newSeed);
            provider.hydrologyProvider.clear();
            instanceSeed = newSeed;
            clearRegionCaches();
            return null;
        });
    }

    /** Change to a random new seed; returns the new seed value. */
    public static long generateRandomSeedFromExplorer() throws Exception {
        long newSeed = new Random().nextLong();
        changeSeedFromExplorer(newSeed);
        return newSeed;
    }

    /** Runs under the shared read lock: any number of these can run concurrently. */
    private static <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        return INFERENCE_EXECUTOR.submit(() -> {
            SEED_LOCK.readLock().lock();
            try {
                return task.call();
            } finally {
                SEED_LOCK.readLock().unlock();
            }
        }).get();
    }

    /** Runs under the exclusive write lock: waits for in-flight fetches, blocks new ones. */
    private static <T> T submitExclusiveToInferenceThread(Callable<T> task) throws Exception {
        return INFERENCE_EXECUTOR.submit(() -> {
            SEED_LOCK.writeLock().lock();
            try {
                return task.call();
            } finally {
                SEED_LOCK.writeLock().unlock();
            }
        }).get();
    }

    /**
     * Fetch heightmap for a block-coordinate region (i=Z, j=X).
     * Coordinates are in block space; scale from config determines blocks per native pixel.
     * Blocks the calling thread until the tile is ready (one tile can take 10–30+ seconds).
     * If the caller is the server or a chunk worker, the game will stall until this returns.
     */
    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        int scale = WorldScaleManager.getCurrentScale();
        boolean blockLowAltitudeSources = WorldScaleManager.shouldBlockLowAltitudeSources();
        CacheKey key = new CacheKey(instanceSeed, scale, blockLowAltitudeSources, i1, j1, i2, j2);
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
            return cached.data;
        }

        return this.genHeightmap(key, i1, j1, i2, j2);
    }

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        int scale = key.scale();
        FutureTask<HeightmapData> task = new FutureTask<>(() -> {
            SEED_LOCK.readLock().lock();
            try {
                long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
                HeightmapData data = scale <= 1
                        ? handle1x(i1, j1, i2, j2, key.blockLowAltitudeSources())
                        : handleUpsampled(i1, j1, i2, j2, scale, key.blockLowAltitudeSources());
                long computedWindowCountAfter = pipeline.getTotalComputedWindowCount();

                long newlyComputedWindowCount = computedWindowCountAfter - computedWindowCountBefore;
                int regionWidth = j2 - j1;
                int regionHeight = i2 - i1;
                LOG.info(
                        "Terrain Diffusion ({}) finished generating region {}x{} ({} newly computed windows)",
                        OnnxModel.getResolvedInferenceProvider(), regionWidth, regionHeight, newlyComputedWindowCount);
                cacheHeightmap(key, data);
                PENDING.remove(key);
                return data;
            } finally {
                SEED_LOCK.readLock().unlock();
            }
        });
        Future<HeightmapData> existing = PENDING.putIfAbsent(key, task);
        FutureTask<HeightmapData> toRun = (existing == null) ? task : (FutureTask<HeightmapData>) existing;
        if (existing == null) {
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) uncached region requested: ({}, {})-({}, {}) size {}x{}",
                    OnnxModel.getResolvedInferenceProvider(), j1, i1, j2, i2, regionWidth, regionHeight);
            INFERENCE_EXECUTOR.submit(toRun);
        }
        try {
            return toRun.get();
        } catch (Exception e) {
            PENDING.remove(key);
            throw new RuntimeException("Terrain tile failed: " + key, e);
        }
    }

    private static void clearRegionCaches() {
        CACHE.clear();
        CACHE_BYTES.set(0L);
        PENDING.clear();
    }

    private static void cacheHeightmap(CacheKey key, HeightmapData data) {
        long bytes = estimateHeightmapBytes(data);
        CacheEntry previous = CACHE.put(key, new CacheEntry(data, new AtomicLong(CACHE_CLOCK.incrementAndGet()), bytes));
        if (previous != null) {
            CACHE_BYTES.addAndGet(-previous.bytes());
        }
        long retainedBytes = CACHE_BYTES.addAndGet(bytes);
        evictLruIfNeeded(retainedBytes);
    }

    private static void evictLruIfNeeded(long retainedBytes) {
        if (CACHE.size() <= MAX_CACHE_ENTRIES && retainedBytes <= MAX_CACHE_BYTES) {
            return;
        }

        CACHE.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed.get()))
                .map(Map.Entry::getKey)
                .forEach(key -> {
                    if (CACHE.size() <= MAX_CACHE_ENTRIES && CACHE_BYTES.get() <= MAX_CACHE_BYTES) {
                        return;
                    }
                    CacheEntry removed = CACHE.remove(key);
                    if (removed != null) {
                        CACHE_BYTES.addAndGet(-removed.bytes());
                    }
                });
    }

    private static long estimateHeightmapBytes(HeightmapData data) {
        if (data == null) return 0L;
        return estimateShortMatrixBytes(data.heightmap)
                + estimateShortMatrixBytes(data.biomeIndexes)
                + estimateShortMatrixBytes(data.riverWater)
                + estimateShortMatrixBytes(data.riverWaterSurface)
                + 64L;
    }

    private static long estimateShortMatrixBytes(short[][] values) {
        if (values == null) return 0L;
        long bytes = 16L + (long) values.length * Long.BYTES;
        for (short[] row : values) {
            if (row != null) {
                bytes += 16L + (long) row.length * Short.BYTES;
            }
        }
        return bytes;
    }

    // =========================================================================
    // Canonical hydrology shared by explorer and Minecraft world generation
    // =========================================================================

    private HeightmapData handle1x(int i1, int j1, int i2, int j2, boolean blockLowAltitudeSources) {
        HydrologyProvider.HydrologyRegion hydrology = hydrologyProvider.getRegion(
                instanceSeed, i1, j1, i2, j2, 1, blockLowAltitudeSources, true);
        return buildHeightmapData(hydrology.adjustedElevation(), hydrology.biomeIndexes(),
                hydrology.waterMask(), hydrology.waterSurface(), hydrology.height(), hydrology.width());
    }

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale,
                                                boolean blockLowAltitudeSources) {
        HydrologyProvider.HydrologyRegion hydrology = hydrologyProvider.getRegion(
                instanceSeed, i1, j1, i2, j2, scale, blockLowAltitudeSources, true);
        return buildHeightmapData(hydrology.adjustedElevation(), hydrology.biomeIndexes(),
                hydrology.waterMask(), hydrology.waterSurface(), hydrology.height(), hydrology.width());
    }

    /** Compatibility/full-data path. World generation and detail_raw use compact specialised paths instead. */
    private RiverTerrainData buildRiverTerrainData(int i1, int j1, int i2, int j2, int scale, boolean withBiomes) {
        HydrologyProvider.HydrologyRegion hydrology = hydrologyProvider.getRegion(
                instanceSeed, i1, j1, i2, j2, scale, WorldScaleManager.shouldBlockLowAltitudeSources(), withBiomes);
        float[] climate = sampleClimate(i1, j1, i2, j2, scale);
        return new RiverTerrainData(
                shortsToFloats(hydrology.adjustedElevation()),
                climate,
                withBiomes ? hydrology.biomeIndexes() : null,
                hydrology.waterMask(),
                decodeWaterSurface(hydrology.waterSurface()),
                hydrology.width(),
                hydrology.height()
        );
    }

    private ExplorerDetailData buildExplorerDetailData(int i1, int j1, int i2, int j2, int scale) {
        HydrologyProvider.HydrologyRegion hydrology = hydrologyProvider.getRegion(
                instanceSeed, i1, j1, i2, j2, scale,
                WorldScaleManager.shouldBlockLowAltitudeSources(), true);
        float[] climate = sampleClimate(i1, j1, i2, j2, scale);
        short[] temperature = null;
        if (climate != null) {
            int n = Math.multiplyExact(hydrology.height(), hydrology.width());
            temperature = new short[n];
            for (int index = 0; index < n; index++) {
                temperature[index] = clampTemperatureCentiToShort(climate[index]);
            }
        }
        return new ExplorerDetailData(hydrology.adjustedElevation(), temperature,
                hydrology.biomeIndexes(), hydrology.waterMask(), hydrology.waterSurface(),
                hydrology.width(), hydrology.height());
    }

    /** Generate exactly one fixed hydrology tile, including its fixed analysis halo. */
    private HydrologyProvider.HydrologyTile computeHydrologyTile(
            int coreI0, int coreJ0, int coreSize, int halo, int scale,
            boolean blockLowAltitudeSources) {
        int analysisI0 = coreI0 - halo;
        int analysisJ0 = coreJ0 - halo;
        int analysisI1 = coreI0 + coreSize + halo;
        int analysisJ1 = coreJ0 + coreSize + halo;
        int analysisHeight = analysisI1 - analysisI0;
        int analysisWidth = analysisJ1 - analysisJ0;
        float pixelSizeM = NATIVE_RESOLUTION / Math.max(1, scale);

        long tSampleStart = System.nanoTime();
        float[] elevation;
        float[] climate;
        if (scale <= 1) {
            float[][] raw = pipeline.get(analysisI0, analysisJ0, analysisI1, analysisJ1, true);
            elevation = raw[0];
            climate = raw[1];
        } else {
            UpsampledTerrainSample sample = sampleUpsampledTerrain(
                    analysisI0, analysisJ0, analysisI1, analysisJ1, scale);
            elevation = addElevationNoise(sample.elevation(), sample.elevationWithBorder(),
                    analysisI0, analysisJ0, analysisHeight, analysisWidth, pixelSizeM);
            climate = sample.climate();
        }
        long tSample = System.nanoTime();

        FluvialRiverNetwork.RiverTopology topology = FluvialRiverNetwork.build(
                instanceSeed, analysisI0, analysisJ0, elevation, climate, analysisHeight, analysisWidth,
                pixelSizeM, blockLowAltitudeSources, WorldScaleManager.MINIMUM_SOURCE_ELEVATION_METERS);
        long tRiverBuild = System.nanoTime();
        DetailedRiverCarver.CarvedTerrain carved = DetailedRiverCarver.carve(
                elevation, topology, analysisHeight, analysisWidth, pixelSizeM);
        long tCarve = System.nanoTime();
        float[] coreElevation = carved.cropAdjustedElevation(
                halo, halo, coreSize, coreSize, analysisWidth);
        float[] coreClimate = cropClimate(climate, analysisHeight, analysisWidth,
                halo, halo, coreSize, coreSize);
        float[] classifierElevation = carved.cropAdjustedElevation(
                halo - 1, halo - 1, coreSize + 2, coreSize + 2, analysisWidth);
        long tCrop = System.nanoTime();
        short[] biomes = BiomeClassifier.classify(coreElevation, coreClimate,
                coreI0, coreJ0, classifierElevation, coreSize, coreSize, pixelSizeM);
        long tClassify = System.nanoTime();
        FluvialRiverNetwork.applyRiverBiomesFromWindow(
                biomes, coreClimate, topology, halo, halo, coreSize, coreSize);
        long tRiverBiomes = System.nanoTime();

        int cells = Math.multiplyExact(coreSize, coreSize);
        short[] compactElevation = new short[cells];
        byte[] compactWaterMask = new byte[cells];
        short[] compactWaterSurface = new short[cells];
        float[] channelProfile = topology.channelProfile();
        float[] channelLoad = topology.channelLoad();
        float[] lakeDepth = topology.lakeDepth();
        float[] waterSurface = topology.waterSurface();
        HydrologyParallel.forEachRow(0, coreSize, coreSize, row -> {
            int sourceOffset = (halo + row) * analysisWidth + halo;
            int targetOffset = row * coreSize;
            for (int col = 0; col < coreSize; col++) {
                int sourceIndex = sourceOffset + col;
                int targetIndex = targetOffset + col;
                compactElevation[targetIndex] = clampTerrainElevationToShort(coreElevation[targetIndex]);
                compactWaterMask[targetIndex] = FluvialRiverNetwork.encodeWaterMask(
                        channelProfile[sourceIndex], channelLoad[sourceIndex], lakeDepth[sourceIndex]);
                float surface = waterSurface[sourceIndex];
                compactWaterSurface[targetIndex] = Float.isFinite(surface)
                        ? clampWaterElevationToShort(surface)
                        : HeightmapData.NO_FLUVIAL_WATER;
            }
        });
        long tCompact = System.nanoTime();

        LOG.info("Generated canonical hydrology tile at ({}, {}) size {} scale {} with halo {}, {} workers ({} MiB compact)",
                coreJ0, coreI0, coreSize, scale, halo,
                HydrologyParallel.workerThreads(),
                (compactElevation.length * 7L) / (1024L * 1024L));
        LOG.info("Hydrology tile ({}, {}) phase breakdown (ms): terrainSample={} riverBuild={} riverCarve={} "
                        + "crop={} biomeClassify={} riverBiomes={} compact={} total={}",
                coreJ0, coreI0,
                millis(tSampleStart, tSample), millis(tSample, tRiverBuild), millis(tRiverBuild, tCarve),
                millis(tCarve, tCrop), millis(tCrop, tClassify), millis(tClassify, tRiverBiomes),
                millis(tRiverBiomes, tCompact), millis(tSampleStart, tCompact));
        return new HydrologyProvider.HydrologyTile(
                coreI0,
                coreJ0,
                compactElevation,
                compactWaterMask,
                compactWaterSurface,
                biomes,
                coreSize,
                coreSize
        );
    }

    private static long millis(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000L;
    }

    private float[] sampleClimate(int i1, int j1, int i2, int j2, int scale) {
        if (scale <= 1) {
            return pipeline.get(i1, j1, i2, j2, true)[1];
        }
        return sampleUpsampledTerrain(i1, j1, i2, j2, scale).climate();
    }

    /**
     * Sample deterministic upscaled terrain/climate for an exact block-space region. The returned
     * elevationWithBorder contains one extra output pixel on every side for slope calculation.
     */
    private UpsampledTerrainSample sampleUpsampledTerrain(int i1, int j1, int i2, int j2, int scale) {
        int height = i2 - i1;
        int width = j2 - j1;

        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);
        int i1p = i1n - 2;
        int j1p = j1n - 2;
        int i2p = i2n + 2;
        int j2p = j2n + 2;
        int nativeHeight = i2p - i1p;
        int nativeWidth = j2p - j1p;

        float[][] raw = pipeline.get(i1p, j1p, i2p, j2p, true);
        float[][] nativeElevation = to2D(raw[0], nativeHeight, nativeWidth);
        int upHeight = nativeHeight * scale;
        int upWidth = nativeWidth * scale;
        float[][] upscaledElevation = LaplacianUtils.bilinearResize(nativeElevation, upHeight, upWidth);

        int nativePadUp = 2 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI = nativePadUp + offsetI;
        int cropJ = nativePadUp + offsetJ;

        float[] elevation = cropFlat(upscaledElevation, cropI, cropJ,
                height, width, upHeight, upWidth);
        float[] elevationWithBorder = cropFlat(upscaledElevation, cropI - 1, cropJ - 1,
                height + 2, width + 2, upHeight, upWidth);
        float[] climate = upsampleClimate(raw[1], nativeHeight, nativeWidth,
                cropI, cropJ, height, width, scale, upHeight, upWidth);
        return new UpsampledTerrainSample(elevation, elevationWithBorder, climate);
    }

    private record UpsampledTerrainSample(float[] elevation, float[] elevationWithBorder, float[] climate) {}

    // =========================================================================
    // Helpers
    // =========================================================================

    private float[] addElevationNoise(float[] elevSmooth, float[] elevPadded,
                                       int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = elevSmooth.clone();
        float normFactor = 40f * pixelSizeM / NATIVE_RESOLUTION;
        float ampC = 100f * pixelSizeM / NATIVE_RESOLUTION;
        float ampF = 70f  * pixelSizeM / NATIVE_RESOLUTION;

        HydrologyParallel.forEachRow(0, H, W, r -> {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];
                if (e < 0f) continue;

                float grad = slopeGradient[idx];
                float sf = Math.min(1f, grad / normFactor);
                sf = sf * sf * (float) Math.sqrt(sf);

                float nx = j1 + c, ny = i1 + r;
                elevOut[idx] = e
                        + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                        + ELEV_NOISE_FINE.GetNoise(nx, ny)   * ampF * sf;
            }
        });
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        HydrologyParallel.forEachRow(0, H, W, r -> {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k/3) * pW + (c + k%3)];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }
                dx /= 8f; dy /= 8f;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        });
        return result;
    }

    private static float[] upsampleClimate(float[] climNative, int nH, int nW,
                                            int cropI1, int cropJ1, int H, int W,
                                            int scale, int upH, int upW) {
        if (climNative == null) return null;
        float[] result = new float[4 * H * W];
        for (int ch = 0; ch < 4; ch++) {
            int channel = ch;
            float[][] chNative = new float[nH][nW];
            HydrologyParallel.forEachRow(0, nH, nW, r ->
                    System.arraycopy(climNative, channel * nH * nW + r * nW,
                            chNative[r], 0, nW));
            float[][] chUp = LaplacianUtils.bilinearResize(chNative, upH, upW);
            HydrologyParallel.forEachRow(0, H, W, r -> {
                for (int c = 0; c < W; c++)
                    result[channel * H * W + r * W + c] =
                            chUp[cropI1 + r][cropJ1 + c];
            });
        }
        return result;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];
        HydrologyParallel.forEachRow(0, H, W, r -> {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));
            for (int c = 0; c < W; c++)
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
        });
        return out;
    }

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        HydrologyParallel.forEachRow(0, H, W, r ->
                System.arraycopy(flat, r * W, a[r], 0, W));
        return a;
    }

    private static float[] cropClimate(float[] src, int srcH, int srcW, int row0, int col0, int H, int W) {
        if (src == null) return null;
        int srcPlane = srcH * srcW;
        int channels = Math.max(1, src.length / srcPlane);
        float[] out = new float[channels * H * W];
        int outPlane = H * W;
        for (int ch = 0; ch < channels; ch++) {
            int channel = ch;
            HydrologyParallel.forEachRow(0, H, W, r -> {
                int sr = Math.max(0, Math.min(srcH - 1, row0 + r));
                for (int c = 0; c < W; c++) {
                    int sc = Math.max(0, Math.min(srcW - 1, col0 + c));
                    out[channel * outPlane + r * W + c] =
                            src[channel * srcPlane + sr * srcW + sc];
                }
            });
        }
        return out;
    }

    private static float[] padElevationOnePixel(float[] src, int H, int W) {
        float[] out = new float[(H + 2) * (W + 2)];
        HydrologyParallel.forEachRow(0, H + 2, W + 2, r -> {
            int sr = Math.max(0, Math.min(H - 1, r - 1));
            for (int c = 0; c < W + 2; c++) {
                int sc = Math.max(0, Math.min(W - 1, c - 1));
                out[r * (W + 2) + c] = src[sr * W + sc];
            }
        });
        return out;
    }

    private static HeightmapData buildHeightmapData(short[] elevFlat, short[] biomeFlat, byte[] waterMask,
                                                     short[] waterSurface, int H, int W) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIndexes = new short[H][W];
        short[][] riverWater = waterMask != null ? new short[H][W] : null;
        short[][] riverWaterSurface = waterSurface != null ? new short[H][W] : null;
        HydrologyParallel.forEachRow(0, H, W, row -> {
            int offset = row * W;
            System.arraycopy(elevFlat, offset, heightmap[row], 0, W);
            if (biomeFlat != null) System.arraycopy(biomeFlat, offset, biomeIndexes[row], 0, W);
            for (int col = 0; col < W; col++) {
                int index = offset + col;
                if (riverWater != null) riverWater[row][col] = (short) (waterMask[index] & 0xFF);
                if (riverWaterSurface != null) riverWaterSurface[row][col] = waterSurface[index];
            }
        });
        return new HeightmapData(heightmap, biomeIndexes, riverWater, riverWaterSurface, W, H);
    }

    private static float[] shortsToFloats(short[] values) {
        float[] out = new float[values.length];
        HydrologyParallel.forEachIndex(0, values.length, index -> out[index] = values[index]);
        return out;
    }

    private static float[] decodeWaterSurface(short[] values) {
        if (values == null) return null;
        float[] out = new float[values.length];
        HydrologyParallel.forEachIndex(0, values.length, index -> {
            short value = values[index];
            out[index] = value == HeightmapData.NO_FLUVIAL_WATER ? Float.NaN : value;
        });
        return out;
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, short[] biomeFlat, int H, int W) {
        return buildHeightmapData(elevFlat, biomeFlat, null, null, H, W);
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, short[] biomeFlat, byte[] waterMask,
                                                    float[] waterSurface, int H, int W) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIndexes = new short[H][W];
        short[][] riverWater = waterMask != null ? new short[H][W] : null;
        short[][] riverWaterSurface = waterSurface != null ? new short[H][W] : null;
        HydrologyParallel.forEachRow(0, H, W, r -> {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevFlat[idx];
                heightmap[r][c] = clampTerrainElevationToShort(e);
                biomeIndexes[r][c] = biomeFlat != null ? biomeFlat[idx] : 0;
                if (riverWater != null) {
                    riverWater[r][c] = (short) (waterMask[idx] & 0xFF);
                }
                if (riverWaterSurface != null) {
                    float surface = waterSurface[idx];
                    riverWaterSurface[r][c] = Float.isFinite(surface)
                            ? clampWaterElevationToShort(surface)
                            : HeightmapData.NO_FLUVIAL_WATER;
                }
            }
        });
        return new HeightmapData(heightmap, biomeIndexes, riverWater, riverWaterSurface, W, H);
    }

    private static short clampTemperatureCentiToShort(float temperatureC) {
        return (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(temperatureC * 100.0f)));
    }

    private static short clampTerrainElevationToShort(float elevation) {
        return (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, (int) Math.floor(elevation)));
    }

    private static short clampWaterElevationToShort(float elevation) {
        return (short) Math.max(Short.MIN_VALUE + 1,
                Math.min(Short.MAX_VALUE, (int) Math.floor(elevation)));
    }
}
