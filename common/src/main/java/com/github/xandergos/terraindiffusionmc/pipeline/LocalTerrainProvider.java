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
        public final short[][] heightmap;
        public final short[][] biomeIndexes;
        public final short[][] riverWater;
        public final int width;
        public final int height;

        public HeightmapData(short[][] heightmap, short[][] biomeIndexes, int width, int height) {
            this(heightmap, biomeIndexes, null, width, height);
        }

        public HeightmapData(short[][] heightmap, short[][] biomeIndexes, short[][] riverWater, int width, int height) {
            this.heightmap     = heightmap;
            this.biomeIndexes  = biomeIndexes;
            this.riverWater    = riverWater;
            this.width     = width;
            this.height    = height;
        }
    }

    public static final class RiverTerrainData {
        public final float[] elevation;
        public final float[] climate;
        public final short[] biomeIndexes;
        public final byte[] waterMask;
        public final int width;
        public final int height;

        public RiverTerrainData(float[] elevation, float[] climate, short[] biomeIndexes, byte[] waterMask, int width, int height) {
            this.elevation = elevation;
            this.climate = climate;
            this.biomeIndexes = biomeIndexes;
            this.waterMask = waterMask;
            this.width = width;
            this.height = height;
        }
    }

    private static record CacheKey(int i1, int j1, int i2, int j2) {}
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

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
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
        return submitToInferenceThread(() -> getInstance().buildNativeRiverTerrainData(i1, j1, i2, j2, withBiomes));
    }

    /**
     * Change the world seed used by the pipeline and clear all caches.
     * Note: this also affects terrain generation for new Minecraft chunks.
     */
    public static void changeSeedFromExplorer(long newSeed) throws Exception {
        submitToInferenceThread(() -> {
            LocalTerrainProvider provider = getInstance();
            provider.pipeline.setSeed(newSeed);
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
        CacheKey key = new CacheKey(i1, j1, i2, j2);
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
            return cached.data;
        }

        return this.genHeightmap(key, i1, j1, i2, j2);
    }

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        int scale = WorldScaleManager.getCurrentScale();
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
    // Scale == 1: block coords == native pixel coords
    // =========================================================================

    private HeightmapData handle1x(int i1, int j1, int i2, int j2) {
        RiverTerrainData riverData = buildNativeRiverTerrainData(i1, j1, i2, j2, true);
        return buildHeightmapData(riverData.elevation, riverData.biomeIndexes, riverData.waterMask, riverData.height, riverData.width);
    }

    // =========================================================================
    // Scale > 1: pipeline at native res → bilinear upsample to block res
    // =========================================================================

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        RiverTerrainData riverData = buildUpsampledRiverTerrainData(i1, j1, i2, j2, scale, true);
        return buildHeightmapData(riverData.elevation, riverData.biomeIndexes, riverData.waterMask, riverData.height, riverData.width);
    }

    private RiverTerrainData buildUpsampledRiverTerrainData(int i1, int j1, int i2, int j2, int scale, boolean withBiomes) {
        int H = i2 - i1, W = j2 - j1;
        float pixelSizeM = NATIVE_RESOLUTION / scale;
        int hydroPad = FluvialRiverNetwork.analysisPaddingPixels(pixelSizeM);

        int pi1 = i1 - hydroPad, pj1 = j1 - hydroPad, pi2 = i2 + hydroPad, pj2 = j2 + hydroPad;
        int pH = pi2 - pi1, pW = pj2 - pj1;

        // Convert the padded block window to native pixel coordinates. The two native pixels
        // around it cover bilinear interpolation and one-pixel slope gradients after upsampling.
        int i1n = Math.floorDiv(pi1, scale);
        int j1n = Math.floorDiv(pj1, scale);
        int i2n = -Math.floorDiv(-pi2, scale);
        int j2n = -Math.floorDiv(-pj2, scale);
        int i1p = i1n - 2, j1p = j1n - 2;
        int i2p = i2n + 2, j2p = j2n + 2;
        int nH = i2p - i1p, nW = j2p - j1p;

        float[][] raw = pipeline.get(i1p, j1p, i2p, j2p, true);
        float[] elevNativeFlat = raw[0];
        float[] climateNativeFlat = raw[1];

        float[][] elevNative2D = to2D(elevNativeFlat, nH, nW);
        float[][] elevUp = LaplacianUtils.bilinearResize(elevNative2D, nH * scale, nW * scale);

        int nativePadUp = 2 * scale;
        int offsetI = pi1 - i1n * scale;
        int offsetJ = pj1 - j1n * scale;
        int cropI1 = nativePadUp + offsetI;
        int cropJ1 = nativePadUp + offsetJ;

        float[] elevSmoothPadded = cropFlat(elevUp, cropI1, cropJ1, pH, pW, nH * scale, nW * scale);
        float[] elevSlopePadded = cropFlat(elevUp, cropI1 - 1, cropJ1 - 1, pH + 2, pW + 2, nH * scale, nW * scale);
        float[] climatePadded = upsampleClimate(climateNativeFlat, nH, nW, cropI1, cropJ1, pH, pW, scale, nH * scale, nW * scale);

        float[] elevNoisyPadded = addElevationNoise(elevSmoothPadded, elevSlopePadded, pi1, pj1, pH, pW, pixelSizeM);
        FluvialRiverNetwork.RiverResult riversPadded = FluvialRiverNetwork.build(pi1, pj1, elevNoisyPadded, climatePadded, pH, pW, pixelSizeM);
        FluvialRiverNetwork.RiverResult rivers = riversPadded.crop(hydroPad, hydroPad, H, W);
        float[] elevation = rivers.adjustedElevation();
        float[] climate = cropClimate(climatePadded, pH, pW, hydroPad, hydroPad, H, W);

        short[] biomes = null;
        if (withBiomes) {
            float[] classifierElevation = riversPadded.crop(hydroPad - 1, hydroPad - 1, H + 2, W + 2).adjustedElevation();
            biomes = BiomeClassifier.classify(elevation, climate, i1, j1, classifierElevation, H, W, pixelSizeM);
            FluvialRiverNetwork.applyRiverBiomes(biomes, climate, rivers, H, W);
        }
        return new RiverTerrainData(elevation, climate, biomes, rivers.waterMaskBytes(), W, H);
    }

    private RiverTerrainData buildNativeRiverTerrainData(int i1, int j1, int i2, int j2, boolean withBiomes) {
        int H = i2 - i1, W = j2 - j1;
        int pad = FluvialRiverNetwork.analysisPaddingPixels(NATIVE_RESOLUTION);
        int pi1 = i1 - pad, pj1 = j1 - pad, pi2 = i2 + pad, pj2 = j2 + pad;
        int pH = pi2 - pi1, pW = pj2 - pj1;

        float[][] raw = pipeline.get(pi1, pj1, pi2, pj2, true);
        float[] elevPaddedWindow = raw[0];
        float[] climatePaddedWindow = raw[1];
        FluvialRiverNetwork.RiverResult riversPadded = FluvialRiverNetwork.build(pi1, pj1,
                elevPaddedWindow, climatePaddedWindow, pH, pW, NATIVE_RESOLUTION);
        FluvialRiverNetwork.RiverResult rivers = riversPadded.crop(pad, pad, H, W);
        float[] elevation = rivers.adjustedElevation();
        float[] climate = cropClimate(climatePaddedWindow, pH, pW, pad, pad, H, W);

        short[] biomes = null;
        if (withBiomes) {
            float[] elevForClassifier = riversPadded.crop(pad - 1, pad - 1, H + 2, W + 2).adjustedElevation();
            biomes = BiomeClassifier.classify(elevation, climate, i1, j1, elevForClassifier, H, W, NATIVE_RESOLUTION);
            FluvialRiverNetwork.applyRiverBiomes(biomes, climate, rivers, H, W);
        }
        return new RiverTerrainData(elevation, climate, biomes, rivers.waterMaskBytes(), W, H);
    }

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
        return buildHeightmapData(elevFlat, biomeFlat, null, H, W);
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, short[] biomeFlat, byte[] waterMask, int H, int W) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIndexes  = new short[H][W];
        short[][] riverWater = waterMask != null ? new short[H][W] : null;
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevFlat[idx];
                heightmap[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                biomeIndexes[r][c]  = biomeFlat != null ? biomeFlat[idx] : 0;
                if (riverWater != null) riverWater[r][c] = (short) (waterMask[idx] & 0xFF);
            }
        return new HeightmapData(heightmap, biomeIndexes, riverWater, W, H);
    }
}
