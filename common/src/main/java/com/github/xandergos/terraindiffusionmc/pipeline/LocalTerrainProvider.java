package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.CoarseDrainageProvider;
import com.github.xandergos.terraindiffusionmc.hydrology.DetailedRiverCarver;
import com.github.xandergos.terraindiffusionmc.hydrology.FluvialRiverNetwork;
import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyParallel;
import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyProvider;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
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
import java.util.concurrent.TimeUnit;
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
        return makeFnl(seed, freq, oct, lac, gain, FastNoiseLite.FractalType.FBm);
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain,
                                          FastNoiseLite.FractalType fractalType) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(fractalType);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    /**
     * Per-seed noise fields that vary the detail noise regionally so distant areas stop feeling
     * identical. All frequencies are in output-pixel (block) space, like the ELEV_NOISE fields.
     */
    private static final class DetailNoise {
        final long seed;
        /** Regional roughness multiplier for the slope-gated detail noise (wavelength ~2.4k blocks). */
        final FastNoiseLite character;
        /** Regional style axis: -1 = billow (rounded hummocks), 0 = FBm, +1 = ridged crags. */
        final FastNoiseLite style;
        /** Patch gate deciding which flatland regions roll instead of staying pancake-flat. */
        final FastNoiseLite plains;
        /** The rolling-plains undulation itself (not slope-gated). */
        final FastNoiseLite swell;
        /** Ridged counterparts of ELEV_NOISE_COARSE/FINE; negated they act as billow noise. */
        final FastNoiseLite ridgedCoarse;
        final FastNoiseLite ridgedFine;

        DetailNoise(long seed) {
            this.seed = seed;
            this.character    = makeFnl(fieldSeed(seed, 1), 1f/2400f, 2, 2f, 0.5f);
            this.style        = makeFnl(fieldSeed(seed, 2), 1f/3200f, 2, 2f, 0.5f);
            this.plains       = makeFnl(fieldSeed(seed, 3), 1f/1400f, 2, 2f, 0.5f);
            this.swell        = makeFnl(fieldSeed(seed, 4), 1f/170f,  2, 2f, 0.5f);
            this.ridgedCoarse = makeFnl(fieldSeed(seed, 5), 1f/24f, 3, 2f, 0.5f, FastNoiseLite.FractalType.Ridged);
            this.ridgedFine   = makeFnl(fieldSeed(seed, 6), 1f/6f,  2, 2f, 0.6f, FastNoiseLite.FractalType.Ridged);
        }

        private static int fieldSeed(long worldSeed, int field) {
            return (int) SurfaceNoise.hash(worldSeed, 0x7E22A1, field);
        }
    }

    private volatile DetailNoise detailNoise;

    private DetailNoise detailNoiseFor(long seed) {
        DetailNoise dn = detailNoise;
        if (dn == null || dn.seed != seed) {
            dn = new DetailNoise(seed);
            detailNoise = dn;
        }
        return dn;
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

    /**
     * Terrain generation activity, for background work that must not compete with it.
     * A tile in flight, or one finished less than {@link #GENERATION_QUIET_NANOS} ago, counts as
     * busy: chunk requests arrive in bursts, and a gap inside a burst is not idle time.
     */
    private static final AtomicInteger ACTIVE_TILE_BUILDS = new AtomicInteger();

    /**
     * Region generations a chunk-generation thread is currently waiting on. Background warming
     * checks this rather than {@link #ACTIVE_TILE_BUILDS}, which also counts warming's own work.
     */
    private static final AtomicInteger ACTIVE_FOREGROUND_BUILDS = new AtomicInteger();
    private static volatile long lastTileFinishNanos = System.nanoTime();
    private static final long GENERATION_QUIET_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** True when nothing has generated terrain recently, so the GPU is free for warming. */
    public static boolean generationIdle() {
        return ACTIVE_TILE_BUILDS.get() == 0
                && System.nanoTime() - lastTileFinishNanos > GENERATION_QUIET_NANOS;
    }

    /**
     * True when no chunk request is blocked on terrain generation right now.
     *
     * <p>This, not {@link #generationIdle()}, is the gate background warming wants. A player who
     * is moving generates a tile every few seconds, so "nothing generated for five seconds" is
     * almost never true while exploring -- which is exactly when the next tile is most worth
     * having ready. What actually matters is that warming never runs while a chunk is waiting.</p>
     */
    public static boolean foregroundQuiet() {
        return ACTIVE_FOREGROUND_BUILDS.get() == 0;
    }

    private static volatile LocalTerrainProvider INSTANCE;
    private static long instanceSeed;

    private final WorldPipeline pipeline;
    private final HydrologyProvider hydrologyProvider;
    private final CoarseDrainageProvider coarseDrainageProvider;

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
        this.hydrologyProvider = new HydrologyProvider(this::computeHydrologyTile);
        this.coarseDrainageProvider = new CoarseDrainageProvider(
                (s, scale, li0, lj0, latentSize) -> buildCoarseTile(scale, li0, lj0, latentSize),
                LocalTerrainProvider::foregroundQuiet);
    }

    /** Coarse drainage super-tile contents: latent-resolution elevation and climate, then the shared flood pass. */
    private CoarseDrainageProvider.CoarseTile buildCoarseTile(int scale, int li0, int lj0, int latentSize) {
        float[] elevation = pipeline.getLatentElevation(li0, lj0, li0 + latentSize, lj0 + latentSize);
        float[] climate = pipeline.getLatentClimate(li0, lj0, li0 + latentSize, lj0 + latentSize, elevation);
        return CoarseDrainageProvider.buildCoarseTile(li0, lj0, latentSize, elevation, climate);
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
            // Exclusive: a build that started under the old seed reads instanceSeed and the
            // pipeline's own seed while it runs, and persists its result keyed by the seed it was
            // asked for. Changing either one underneath it would write a tile built with seed B to
            // disk under seed A, where the version/scale/origin checks would all pass on a later
            // session. Waiting for in-flight builds and blocking new ones is what makes background
            // warming safe to run at all.
            SEED_LOCK.writeLock().lock();
            try {
                INSTANCE.pipeline.setSeed(seed);
                INSTANCE.hydrologyProvider.clear();
                INSTANCE.coarseDrainageProvider.clear();
                instanceSeed = seed;
                clearRegionCaches();
            } finally {
                SEED_LOCK.writeLock().unlock();
            }
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
            provider.coarseDrainageProvider.clear();
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
            // The coarse drainage cache is keyed by seed but its contents come from the pipeline,
            // whose seed just changed: anything left in it belongs to the old world.
            provider.coarseDrainageProvider.clear();
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

        // Fast path: every canonical tile this region needs is already in memory, so the region is
        // a pure copy out of them. Doing it here keeps the ~4 unaligned per-chunk decorator windows
        // off INFERENCE_EXECUTOR, where they would otherwise queue behind a multi-second tile build
        // even though they need no inference at all. Same tiles, same copy, same bytes.
        HeightmapData resident = residentHeightmap(key, i1, j1, i2, j2);
        if (resident != null) {
            cacheHeightmap(key, resident);
            noteTerrainInterest(key, i1, j1, i2, j2);
            return resident;
        }

        HeightmapData generated = this.genHeightmap(key, i1, j1, i2, j2);
        noteTerrainInterest(key, i1, j1, i2, j2);
        return generated;
    }

    /**
     * Records where terrain is being asked for, so the warmer can have the neighbouring canonical
     * tiles ready before the player reaches them.
     *
     * <p>Cheap enough for the per-chunk path: it converts the request's centre to a tile index and
     * returns immediately unless that index has changed since the last call.</p>
     */
    private void noteTerrainInterest(CacheKey key, int i1, int j1, int i2, int j2) {
        if (!WARM_TERRAIN_TILES) return;
        int centreI = i1 + (i2 - i1 - 1) / 2;
        int centreJ = j1 + (j2 - j1 - 1) / 2;
        int tileI = hydrologyProvider.tileIndexForBlock(centreI);
        int tileJ = hydrologyProvider.tileIndexForBlock(centreJ);
        long packed = ((long) tileI << 32) ^ (tileJ & 0xFFFFFFFFL);
        if (packed == lastInterestPacked) return;
        lastInterestPacked = packed;
        warmer.focus(key.seed(), key.scale(), key.blockLowAltitudeSources(), tileI, tileJ);
    }

    /**
     * Assembles a region from canonical hydrology tiles that are already resident in memory, or
     * returns {@code null} if any of them would have to be generated or read from disk.
     */
    private HeightmapData residentHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        // Shared lock, exactly as the generating path takes: this reads the tile cache, and a
        // world change empties it. Holding it keeps the seed the key was built from and the tiles
        // the region is copied out of the same seed, which is the whole point of the lock.
        SEED_LOCK.readLock().lock();
        try {
            if (instanceSeed != key.seed()) return null;
            HydrologyProvider.HydrologyRegion hydrology = hydrologyProvider.getRegionIfResident(
                    key.seed(), i1, j1, i2, j2, key.scale(), key.blockLowAltitudeSources(), true);
            if (hydrology == null) return null;
            return buildHeightmapData(hydrology.adjustedElevation(), hydrology.biomeIndexes(),
                    hydrology.waterMask(), hydrology.waterSurface(), hydrology.height(), hydrology.width());
        } finally {
            SEED_LOCK.readLock().unlock();
        }
    }

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        int scale = key.scale();
        FutureTask<HeightmapData> task = new FutureTask<>(() -> {
            SEED_LOCK.readLock().lock();
            ACTIVE_FOREGROUND_BUILDS.incrementAndGet();
            try {
                long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
                HeightmapData data = scale <= 1
                        ? handle1x(i1, j1, i2, j2, key.blockLowAltitudeSources())
                        : handleUpsampled(i1, j1, i2, j2, scale, key.blockLowAltitudeSources());
                long computedWindowCountAfter = pipeline.getTotalComputedWindowCount();

                long newlyComputedWindowCount = computedWindowCountAfter - computedWindowCountBefore;
                int regionWidth = j2 - j1;
                int regionHeight = i2 - i1;
                LOG.debug(
                        "Terrain Diffusion ({}) finished generating region {}x{} ({} newly computed windows)",
                        OnnxModel.getResolvedInferenceProvider(), regionWidth, regionHeight, newlyComputedWindowCount);
                cacheHeightmap(key, data);
                PENDING.remove(key);
                return data;
            } finally {
                ACTIVE_FOREGROUND_BUILDS.decrementAndGet();
                SEED_LOCK.readLock().unlock();
            }
        });
        Future<HeightmapData> existing = PENDING.putIfAbsent(key, task);
        FutureTask<HeightmapData> toRun = (existing == null) ? task : (FutureTask<HeightmapData>) existing;
        if (existing == null) {
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.debug(
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

    /** Whether canonical tiles around the player are built ahead of the request that needs them. */
    private static final boolean WARM_TERRAIN_TILES = TerrainDiffusionConfig.terrainTilePrefetchEnabled();

    /** Last tile index {@link #noteTerrainInterest} saw, so the common case is one comparison. */
    private volatile long lastInterestPacked = Long.MIN_VALUE;

    private final TerrainWarmer warmer = new TerrainWarmer();

    /**
     * Builds the canonical hydrology tiles around wherever terrain is currently being requested,
     * on one background thread, in whatever time chunk generation is not using.
     *
     * <p>Why this exists: a canonical tile covers 2048 blocks, and building one takes roughly two
     * seconds -- longer if its coarse drainage super-tile is also missing. Whichever chunk happens
     * to be the first into a new tile pays that whole cost while the player watches. The work and
     * its result are identical whenever it runs, so the only thing worth changing is when: during
     * play the GPU sits idle almost all the time (chunk generation is bound by Minecraft's own
     * per-chunk work, not by inference), and crossing 2048 blocks takes minutes even by elytra.
     * That is far more slack than the eight neighbours of a tile need.</p>
     *
     * <p>Correctness rests on three things. A canonical tile is a pure function of its key, so
     * building it early cannot change it. {@link HydrologyProvider#ensureTile} goes through the
     * same per-key generation lock as a foreground request, so the two can never both build the
     * same tile. And the whole build runs under {@link #SEED_LOCK}'s read lock with the seed
     * re-checked inside it, so a world change cannot leave a tile built under one seed cached --
     * or persisted -- under another.</p>
     */
    private final class TerrainWarmer {
        /**
         * How many crossings of the same axis are needed before that axis counts as the heading.
         * A player loading chunks across a wide front touches tiles to the side of their path as
         * well as ahead of it, so a heading taken from the last focus change alone flips around
         * and sends warming after tiles nobody is going to enter.
         */
        private static final int HEADING_CONFIDENCE = 2;
        /** Poll interval while waiting for chunk generation to stop asking for terrain. */
        private static final long POLL_MS = 250L;
        /** Consecutive quiet polls required before a warm build starts. */
        private static final int QUIET_POLLS = 4;

        private volatile Thread thread;
        /** The tile terrain is currently being requested around; null when nothing is known yet. */
        private volatile Focus focus;
        /** Direction the focus has consistently been moving in, as a signum pair. */
        private volatile int headingI;
        private volatile int headingJ;
        /** Running vote per axis, so one sideways step cannot redirect warming. */
        private int voteI;
        private int voteJ;
        /** The focus already warmed for, so each crossing costs at most one extra tile build. */
        private volatile Focus warmedFor;

        private record Focus(long seed, int scale, boolean blockLowAltitudeSources, int tileI, int tileJ) {}

        void focus(long seed, int scale, boolean blockLowAltitudeSources, int tileI, int tileJ) {
            Focus previous = focus;
            if (previous != null && previous.seed() == seed && previous.scale() == scale) {
                int deltaI = tileI - previous.tileI();
                int deltaJ = tileJ - previous.tileJ();
                // Keep the last non-zero heading: a player wandering inside one tile produces no
                // delta at all, and the direction they came from is still the best guess. Signum
                // only -- the size of a jump (a teleport) says nothing useful about where next.
                voteI = clampVote(voteI + Integer.signum(deltaI));
                voteJ = clampVote(voteJ + Integer.signum(deltaJ));
                headingI = Math.abs(voteI) >= HEADING_CONFIDENCE ? Integer.signum(voteI) : 0;
                headingJ = Math.abs(voteJ) >= HEADING_CONFIDENCE ? Integer.signum(voteJ) : 0;
            }
            focus = new Focus(seed, scale, blockLowAltitudeSources, tileI, tileJ);
            // Also arm the coarse drainage warmer from here. Its own trigger only fires when a
            // fine tile is freshly *generated*, so a player crossing tiles that came from the
            // disk cache -- or from this warmer -- used to arm nothing at all.
            int tileSize = hydrologyProvider.tileSize();
            coarseDrainageProvider.queueNeighbourWarm(seed, scale,
                    hydrologyProvider.tileOriginForIndex(tileI),
                    hydrologyProvider.tileOriginForIndex(tileJ), tileSize, tileSize);
            start();
        }

        private static int clampVote(int vote) {
            return Math.max(-HEADING_CONFIDENCE, Math.min(HEADING_CONFIDENCE, vote));
        }

        private void start() {
            if (thread != null) return;
            synchronized (this) {
                if (thread != null) return;
                Thread started = new Thread(this::loop, "terrain-diffusion-tile-warmer");
                started.setDaemon(true);
                started.setPriority(Thread.MIN_PRIORITY);
                thread = started;
                started.start();
            }
        }

        private void loop() {
            int quiet = 0;
            while (true) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!foregroundQuiet()) {
                    quiet = 0;
                    continue;
                }
                if (++quiet < QUIET_POLLS) continue;
                Focus current = focus;
                if (current == null) continue;
                if (!warmOne(current)) {
                    // Nothing left to do around the current focus; wait for it to move.
                    quiet = 0;
                }
            }
        }

        /**
         * Builds the one tile the focus is heading into, if it is not already there. Returns false
         * when there is nothing worth building.
         *
         * <p>Exactly one tile per crossing, and only the one directly ahead. Warming is not free:
         * a tile build takes the hydrology worker pool and the GPU for a couple of seconds, and
         * chunk generation is what those are needed for. Warming the eight-neighbour ring, or
         * re-warming while the focus sat still, measured <em>slower</em> than no warming at all --
         * it built several times as many tiles as the player ever entered and the contention cost
         * more than the avoided stalls saved.</p>
         */
        private boolean warmOne(Focus target) {
            if (target.equals(warmedFor)) return false;
            int hi = headingI;
            int hj = headingJ;
            if (hi == 0 && hj == 0) return false;
            int tileI = target.tileI() + hi;
            int tileJ = target.tileJ() + hj;
            warmedFor = target;
            if (hydrologyProvider.isTileResident(target.seed(), target.scale(),
                    target.blockLowAltitudeSources(), tileI, tileJ)) {
                return false;
            }
            SEED_LOCK.readLock().lock();
            try {
                // Re-checked under the lock: outside it, init() may have swapped the world.
                if (instanceSeed != target.seed() || !target.equals(focus)) return true;
                long start = System.nanoTime();
                hydrologyProvider.ensureTile(target.seed(), target.scale(),
                        target.blockLowAltitudeSources(), tileI, tileJ);
                LOG.debug("Warmed canonical hydrology tile ({}, {}) in {} ms",
                        tileJ, tileI, (System.nanoTime() - start) / 1_000_000L);
            } catch (RuntimeException failure) {
                // Best effort: whatever asks for this tile later will build it itself.
                LOG.debug("Hydrology tile warm of ({}, {}) failed", tileJ, tileI, failure);
            } finally {
                SEED_LOCK.readLock().unlock();
            }
            return true;
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

    /**
     * Generates one canonical hydrology tile directly, bypassing both tile caches and the
     * world-scoped scale settings. For benchmarks and diagnostics ({@link WorldGenBenchmark});
     * chunk generation goes through {@link HydrologyProvider} so tiles stay shared and cached.
     */
    public HydrologyProvider.HydrologyTile generateHydrologyTileUncached(
            int coreI0, int coreJ0, int coreSize, int halo, int scale, boolean blockLowAltitudeSources) {
        return computeHydrologyTile(coreI0, coreJ0, coreSize, halo, scale, blockLowAltitudeSources);
    }

    /** Drops every retained pipeline tensor window, so a benchmark tile starts from a cold cache. */
    public void clearPipelineCaches() {
        pipeline.clearCaches();
    }

    /** Generate exactly one fixed hydrology tile, including its fixed analysis halo. */
    private HydrologyProvider.HydrologyTile computeHydrologyTile(
            int coreI0, int coreJ0, int coreSize, int halo, int scale,
            boolean blockLowAltitudeSources) {
        ACTIVE_TILE_BUILDS.incrementAndGet();
        try {
            return computeHydrologyTileTracked(coreI0, coreJ0, coreSize, halo, scale, blockLowAltitudeSources);
        } finally {
            lastTileFinishNanos = System.nanoTime();
            ACTIVE_TILE_BUILDS.decrementAndGet();
        }
    }

    private HydrologyProvider.HydrologyTile computeHydrologyTileTracked(
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

        // Upstream load crossing this analysis window, from the coarse drainage super-tile.
        // Resolved against the window's own drainage, so it is supplied as a callback.
        FluvialRiverNetwork.RiverTopology topology = FluvialRiverNetwork.build(
                instanceSeed, analysisI0, analysisJ0, elevation, climate, analysisHeight, analysisWidth,
                pixelSizeM, blockLowAltitudeSources, WorldScaleManager.MINIMUM_SOURCE_ELEVATION_METERS,
                downstream -> coarseDrainageProvider.boundaryInflow(
                        instanceSeed, scale, analysisI0, analysisJ0,
                        analysisHeight, analysisWidth, elevation, downstream));
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
        // The drainage pass for the super-tile this one sits in is now warm; the next one over
        // is not, and crossing into it would otherwise stall a chunk. Queue it for idle time.
        coarseDrainageProvider.queueNeighbourWarm(instanceSeed, scale, coreI0, coreJ0, coreSize, coreSize);
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

        long tStart = System.nanoTime();
        float[][] raw = pipeline.get(i1p, j1p, i2p, j2p, true);
        long tPipeline = System.nanoTime();
        float[][] nativeElevation = to2D(raw[0], nativeHeight, nativeWidth);
        int upHeight = nativeHeight * scale;
        int upWidth = nativeWidth * scale;

        int nativePadUp = 2 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI = nativePadUp + offsetI;
        int cropJ = nativePadUp + offsetJ;

        // The bordered window is what both outputs come from: resizing the whole upsampled grid
        // and cropping it twice would sample every pixel three times and hold a second
        // full-resolution copy of it.
        float[] elevationWithBorder = LaplacianUtils.bilinearResizeWindow(nativeElevation,
                upHeight, upWidth, cropI - 1, cropJ - 1, height + 2, width + 2);
        float[] elevation = new float[height * width];
        int borderedWidth = width + 2;
        HydrologyParallel.forEachRow(0, height, width, r ->
                System.arraycopy(elevationWithBorder, (r + 1) * borderedWidth + 1,
                        elevation, r * width, width));
        long tElevation = System.nanoTime();
        float[] climate = upsampleClimate(raw[1], nativeHeight, nativeWidth,
                cropI, cropJ, height, width, scale, upHeight, upWidth);
        LOG.debug("Upsampled terrain {}x{} (ms): pipeline={} elevation={} climate={}",
                height, width, millis(tStart, tPipeline), millis(tPipeline, tElevation),
                millis(tElevation, System.nanoTime()));
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
        float ampSwell = 75f * pixelSizeM / NATIVE_RESOLUTION;
        DetailNoise noise = detailNoiseFor(pipeline.getSeed());

        HydrologyParallel.forEachRow(0, H, W, r -> {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];
                if (e < 0f) continue;

                float grad = slopeGradient[idx];
                float s0 = Math.min(1f, grad / normFactor);
                float sf = s0 * s0 * (float) Math.sqrt(s0);

                float nx = j1 + c, ny = i1 + r;

                float detail = 0f;
                if (sf > 1e-4f) {
                    float fbm = ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC
                              + ELEV_NOISE_FINE.GetNoise(nx, ny)   * ampF;
                    // Style axis: deadband around 0 keeps plenty of unchanged FBm terrain;
                    // past it, blend toward ridged crags (+) or billow hummocks (-).
                    float style = noise.style.GetNoise(nx, ny);
                    float tStyle = SurfaceNoise.smoothstep(
                            SurfaceNoise.clamp01((Math.abs(style) - 0.15f) / 0.55f));
                    float mixed = fbm;
                    if (tStyle > 0f) {
                        float ridged = noise.ridgedCoarse.GetNoise(nx, ny) * ampC
                                     + noise.ridgedFine.GetNoise(nx, ny)   * ampF;
                        if (style < 0f) ridged = -ridged;  // billow
                        mixed = SurfaceNoise.lerp(fbm, ridged, tStyle);
                    }
                    float rough = 1f + 0.65f * noise.character.GetNoise(nx, ny);
                    detail = mixed * sf * rough;
                }

                // Rolling plains: low-frequency swell on flat ground, only inside patches
                // selected by the plains field, fading out as slope picks up.
                float swell = 0f;
                float plainsGate = SurfaceNoise.smoothstep(
                        SurfaceNoise.clamp01((noise.plains.GetNoise(nx, ny) - 0.05f) / 0.5f));
                if (plainsGate > 0f) {
                    float flat = (1f - s0) * (1f - s0);
                    swell = noise.swell.GetNoise(nx, ny) * ampSwell * plainsGate * flat;
                }

                // Fade all added noise near sea level so coasts and beaches can't be pushed
                // below water; at e >= 25 m this is a no-op.
                float seaFade = SurfaceNoise.clamp01(e / 25f);
                elevOut[idx] = e + (detail + swell) * seaFade;
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
        // The channel views share one row-pointer array rebuilt per channel rather than a fresh
        // nH x nW copy of the data: bilinearResizeWindow only reads through src[r][c], so pointing
        // the rows at slices of the flat native array feeds it exactly the same values without
        // copying 4 x nH x nW floats first. Only the cropped window is ever read, and it is the
        // majority of the upsampled grid, so resizing the window rather than the whole plane
        // avoids a second full-size array per channel as well.
        for (int ch = 0; ch < 4; ch++) {
            float[][] chNative = new float[nH][];
            int planeBase = ch * nH * nW;
            for (int r = 0; r < nH; r++) {
                chNative[r] = Arrays.copyOfRange(climNative, planeBase + r * nW, planeBase + (r + 1) * nW);
            }
            float[] chUp = LaplacianUtils.bilinearResizeWindow(chNative, upH, upW,
                    cropI1, cropJ1, H, W);
            System.arraycopy(chUp, 0, result, ch * H * W, H * W);
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
