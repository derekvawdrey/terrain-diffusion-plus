package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
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
import java.util.concurrent.atomic.AtomicLong;

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

    private static record CacheKey(long seed, int scale, int i1, int j1, int i2, int j2) {}
    private static record CacheEntry(HeightmapData data, AtomicLong lastAccessed, long bytes) {}

    private static final int MAX_CACHE_ENTRIES = TerrainDiffusionConfig.terrainRegionCacheMaxEntries();
    private static final long MAX_CACHE_BYTES = TerrainDiffusionConfig.terrainRegionCacheMaxBytes();
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();
    private static final AtomicLong CACHE_BYTES = new AtomicLong();
    private static final Map<CacheKey, Future<HeightmapData>> PENDING = new ConcurrentHashMap<>();
    /** Single thread for pipeline.get() so MemoryTileStore is not accessed concurrently. */
    private static final ExecutorService INFERENCE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "terrain-diffusion-inference");
        t.setDaemon(true);
        return t;
    });

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

    /**
     * Build pipeline elevation/climate plus fluvial routing for the explorer at native model resolution.
     */
    public static RiverTerrainData getRiverTerrainData(int i1, int j1, int i2, int j2, boolean withBiomes) throws Exception {
        return submitToInferenceThread(() -> getInstance().buildRiverTerrainData(i1, j1, i2, j2, 1, withBiomes));
    }

    /**
     * Change the world seed used by the pipeline and clear all caches.
     * Note: this also affects terrain generation for new Minecraft chunks.
     */
    public static void changeSeedFromExplorer(long newSeed) throws Exception {
        submitToInferenceThread(() -> {
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

    private static <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        return INFERENCE_EXECUTOR.submit(task).get();
    }

    /**
     * Fetch heightmap for a block-coordinate region (i=Z, j=X).
     * Coordinates are in block space; scale from config determines blocks per native pixel.
     * Blocks the calling thread until the tile is ready (one tile can take 10–30+ seconds).
     * If the caller is the server or a chunk worker, the game will stall until this returns.
     */
    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        int scale = WorldScaleManager.getCurrentScale();
        CacheKey key = new CacheKey(instanceSeed, scale, i1, j1, i2, j2);
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
            long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
            HeightmapData data = scale <= 1
                    ? handle1x(i1, j1, i2, j2)
                    : handleUpsampled(i1, j1, i2, j2, scale);
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

    private HeightmapData handle1x(int i1, int j1, int i2, int j2) {
        RiverTerrainData riverData = buildRiverTerrainData(i1, j1, i2, j2, 1, true);
        return buildHeightmapData(riverData.elevation, riverData.biomeIndexes, riverData.waterMask,
                riverData.waterSurface, riverData.height, riverData.width);
    }

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        RiverTerrainData riverData = buildRiverTerrainData(i1, j1, i2, j2, scale, true);
        return buildHeightmapData(riverData.elevation, riverData.biomeIndexes, riverData.waterMask,
                riverData.waterSurface, riverData.height, riverData.width);
    }

    /**
     * Assemble stable fluvial output from canonical tiles. Climate is sampled for the exact requested
     * region because it is already spatially deterministic and does not need a second large tile cache.
     */
    private RiverTerrainData buildRiverTerrainData(int i1, int j1, int i2, int j2, int scale, boolean withBiomes) {
        HydrologyProvider.HydrologyRegion hydrology = hydrologyProvider.getRegion(
                instanceSeed, i1, j1, i2, j2, scale, withBiomes);
        float[] climate = sampleClimate(i1, j1, i2, j2, scale);
        short[] biomes = withBiomes ? hydrology.biomeIndexes() : null;
        return new RiverTerrainData(
                hydrology.adjustedElevation(),
                climate,
                biomes,
                hydrology.waterMaskBytes(),
                hydrology.waterSurface(),
                hydrology.width(),
                hydrology.height()
        );
    }

    /** Generate exactly one fixed hydrology tile, including its fixed analysis halo. */
    private HydrologyProvider.HydrologyTile computeHydrologyTile(
            int coreI0, int coreJ0, int coreSize, int halo, int scale) {
        int analysisI0 = coreI0 - halo;
        int analysisJ0 = coreJ0 - halo;
        int analysisI1 = coreI0 + coreSize + halo;
        int analysisJ1 = coreJ0 + coreSize + halo;
        int analysisHeight = analysisI1 - analysisI0;
        int analysisWidth = analysisJ1 - analysisJ0;
        float pixelSizeM = NATIVE_RESOLUTION / Math.max(1, scale);

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

        FluvialRiverNetwork.RiverResult padded = FluvialRiverNetwork.build(
                analysisI0, analysisJ0, elevation, climate, analysisHeight, analysisWidth, pixelSizeM);
        FluvialRiverNetwork.RiverResult core = padded.crop(halo, halo, coreSize, coreSize);
        float[] coreClimate = cropClimate(climate, analysisHeight, analysisWidth,
                halo, halo, coreSize, coreSize);
        float[] classifierElevation = padded.crop(
                halo - 1, halo - 1, coreSize + 2, coreSize + 2).adjustedElevation();
        short[] biomes = BiomeClassifier.classify(core.adjustedElevation(), coreClimate,
                coreI0, coreJ0, classifierElevation, coreSize, coreSize, pixelSizeM);
        FluvialRiverNetwork.applyRiverBiomes(biomes, coreClimate, core, coreSize, coreSize);

        LOG.info("Generated canonical hydrology tile at ({}, {}) size {} scale {} with halo {}",
                coreJ0, coreI0, coreSize, scale, halo);
        return new HydrologyProvider.HydrologyTile(
                coreI0,
                coreJ0,
                core.adjustedElevation(),
                core.riverStrength(),
                core.lakeDepth(),
                core.waterSurface(),
                biomes,
                coreSize,
                coreSize
        );
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

        for (int r = 0; r < H; r++) {
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
        }
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        for (int r = 0; r < H; r++) {
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
        }
        return result;
    }

    private static float[] upsampleClimate(float[] climNative, int nH, int nW,
                                            int cropI1, int cropJ1, int H, int W,
                                            int scale, int upH, int upW) {
        if (climNative == null) return null;
        float[] result = new float[4 * H * W];
        for (int ch = 0; ch < 4; ch++) {
            float[][] chNative = new float[nH][nW];
            for (int r = 0; r < nH; r++)
                System.arraycopy(climNative, ch * nH * nW + r * nW, chNative[r], 0, nW);
            float[][] chUp = LaplacianUtils.bilinearResize(chNative, upH, upW);
            for (int r = 0; r < H; r++)
                for (int c = 0; c < W; c++)
                    result[ch * H * W + r * W + c] = chUp[cropI1 + r][cropJ1 + c];
        }
        return result;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++) {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));
            for (int c = 0; c < W; c++)
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
        }
        return out;
    }

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
    }

    private static float[] cropClimate(float[] src, int srcH, int srcW, int row0, int col0, int H, int W) {
        if (src == null) return null;
        int srcPlane = srcH * srcW;
        int channels = Math.max(1, src.length / srcPlane);
        float[] out = new float[channels * H * W];
        int outPlane = H * W;
        for (int ch = 0; ch < channels; ch++) {
            for (int r = 0; r < H; r++) {
                int sr = Math.max(0, Math.min(srcH - 1, row0 + r));
                for (int c = 0; c < W; c++) {
                    int sc = Math.max(0, Math.min(srcW - 1, col0 + c));
                    out[ch * outPlane + r * W + c] = src[ch * srcPlane + sr * srcW + sc];
                }
            }
        }
        return out;
    }

    private static float[] padElevationOnePixel(float[] src, int H, int W) {
        float[] out = new float[(H + 2) * (W + 2)];
        for (int r = 0; r < H + 2; r++) {
            int sr = Math.max(0, Math.min(H - 1, r - 1));
            for (int c = 0; c < W + 2; c++) {
                int sc = Math.max(0, Math.min(W - 1, c - 1));
                out[r * (W + 2) + c] = src[sr * W + sc];
            }
        }
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
        for (int r = 0; r < H; r++) {
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
        }
        return new HeightmapData(heightmap, biomeIndexes, riverWater, riverWaterSurface, W, H);
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
