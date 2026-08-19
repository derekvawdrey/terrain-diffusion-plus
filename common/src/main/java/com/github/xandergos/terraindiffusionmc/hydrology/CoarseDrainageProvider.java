package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Low-resolution drainage tiles that carry river load across canonical hydrology tile
 * boundaries. Each super-tile (a block of canonical tiles covering at least
 * {@value #CORE_LATENT} latent pixels) is drained once at latent resolution: the verified
 * priority flood plus runoff accumulation over a {@code CORE_LATENT + 2 * HALO_LATENT}
 * latent-pixel window. The fine pass injects the upstream load crossing its analysis
 * window ({@link #boundaryInflow}) so rivers do not start or stop at tile seams.
 *
 * <p>A super-tile is {@code K = ceil(CORE_LATENT * L / tileSize)} canonical tiles per axis,
 * with L = 8 * scale blocks per latent pixel, so its core always spans at least
 * {@value #CORE_LATENT} latent pixels regardless of scale or tile size. The window is
 * deliberately large (hundreds of coarse channels of context) because the residual seam
 * error is proportional to the halo each stage gets: super-tiles are K x K times rarer
 * than canonical tiles and carry K x K times the relative halo.
 *
 * <p>Memory and disk caching mirror {@link HydrologyProvider}. Only the drainage result
 * (downstream cell + accumulated km2) is stored, about 8 bytes per latent pixel.
 */
public final class CoarseDrainageProvider {
    private static final Logger LOG = LoggerFactory.getLogger(CoarseDrainageProvider.class);

    /** Increment when the coarse drainage output becomes semantically incompatible. */
    private static final int ALGORITHM_VERSION = 1;
    private static final int DISK_FORMAT_VERSION = 1;
    private static final int DISK_MAGIC = 0x54444344; // TDCD
    private static final int IO_BUFFER_SIZE = 4 * 1024 * 1024;

    /** Super-tile core size in latent pixels (240 m per latent pixel, about 492 km at 2048). */
    static final int CORE_LATENT = 2048;
    /** Window halo around the super-tile core, in latent pixels. */
    static final int HALO_LATENT = 128;
    /** Grid cell size of the coarse (latent) drainage pass, in metres (1 latent pixel). */
    static final float PIXEL_SIZE_M = 240.0f;
    /** Native pixels per latent pixel (model invariant, not config). */
    static final int NATIVE_PER_LATENT = 8;

    /**
     * How far along a window edge a coarse crossing may be searched for the fine channel that
     * carries it, in coarse cells. The coarse network is traced on the low-frequency field only,
     * and its channels sit a measured median of about three cells from the full-resolution ones;
     * six cells covers that with margin while staying well inside the spacing of separate valleys.
     */
    private static final int SNAP_RADIUS_COARSE_CELLS = 6;

    /**
     * How far inside the window, in coarse cells, the load is placed. The window border is the
     * flood's outlet, and the band of cells beside it drains straight back out through that border
     * -- measured at up to four cells deep -- so load left there leaves without ever reaching the
     * tile. Two coarse cells clears that band at every scale while staying well outside the tile
     * core, giving the channel room to establish before it reaches the seam a player sees.
     */
    private static final int INJECT_DEPTH_COARSE_CELLS = 2;

    private static final int SIDE_NORTH = 0;
    private static final int SIDE_SOUTH = 1;
    private static final int SIDE_WEST = 2;
    private static final int SIDE_EAST = 3;

    private static final ExecutorService DISK_WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "terrain-diffusion-coarse-drainage-cache-writer");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<Path> PENDING_DISK_WRITES = ConcurrentHashMap.newKeySet();

    static {
        // A coarse super-tile costs minutes of model inference, so a pending write is worth
        // waiting for on shutdown. Without this the JVM exits mid-write and leaves a partial
        // .tmp behind, and the next run pays the full generation cost again.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            DISK_WRITER.shutdown();
            try {
                if (!DISK_WRITER.awaitTermination(60L, TimeUnit.SECONDS)) {
                    LOG.warn("Coarse drainage cache writer did not finish; {} tile(s) not persisted",
                            PENDING_DISK_WRITES.size());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "terrain-diffusion-coarse-drainage-cache-flush"));
    }

    @FunctionalInterface
    public interface TileGenerator {
        CoarseTile generate(long seed, int scale, int li0, int lj0, int latentSize);
    }

    private record TileKey(long seed, int scale, int superI, int superJ) {}

    private final TileGenerator generator;
    private final int tileSize;
    private final int maxEntries;
    private final long maxBytes;
    private final boolean diskCacheEnabled;
    private final Path diskCacheRoot;

    /** Access-ordered map, guarded by this instance. */
    private final LinkedHashMap<TileKey, CoarseTile> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedBytes;
    private final ConcurrentHashMap<TileKey, Object> generationLocks = new ConcurrentHashMap<>();

    public CoarseDrainageProvider(TileGenerator generator) {
        this.generator = generator;
        this.tileSize = TerrainDiffusionConfig.hydrologyTileSize();
        this.maxEntries = TerrainDiffusionConfig.coarseDrainageCacheMaxEntries();
        this.maxBytes = TerrainDiffusionConfig.coarseDrainageCacheMaxBytes();
        this.diskCacheEnabled = TerrainDiffusionConfig.coarseDrainageDiskCacheEnabled();
        String cacheNamespace = sanitizeNamespace(TerrainDiffusionConfig.coarseDrainageDiskCacheNamespace());
        this.diskCacheRoot = PlatformPaths.gameDir()
                .resolve("terrain-diffusion-cache")
                .resolve("coarse-drainage")
                .resolve(cacheNamespace)
                .resolve("v" + ALGORITHM_VERSION)
                .resolve("tile-" + tileSize);
    }

    /** One cached coarse drainage super-tile: window origin/size plus the two result arrays. */
    public record CoarseTile(int li0, int lj0, int latentSize, int[] downstream, float[] accumulation) {
        long estimatedBytes() {
            return 40L + downstream.length * 4L + accumulation.length * 4L;
        }
    }

    /**
     * Runs the coarse drainage pass for one window: the same verified flood and runoff
     * propagation as the fine pass, on latent-resolution elevation and climate.
     *
     * @param elevation metres per latent pixel of the window
     * @param climate five-plane climate of the same window (see {@code WorldPipeline.getLatentClimate})
     */
    public static CoarseTile buildCoarseTile(int li0, int lj0, int latentSize, float[] elevation, float[] climate) {
        FluvialRiverNetwork.PriorityFlood flood = FluvialRiverNetwork.runDrainage(elevation, latentSize, latentSize);
        float[] accumulation = FluvialRiverNetwork.accumulateRunoff(
                elevation, climate, flood.downstream(), flood.order(), flood.orderSize(),
                latentSize, latentSize, PIXEL_SIZE_M, null);
        return new CoarseTile(li0, lj0, latentSize, flood.downstream(), accumulation);
    }

    /**
     * Computes the upstream load entering one fine analysis window, from the coarse tile that
     * covers it. For every coarse cell outside the window that drains into it, that cell's full
     * coarse accumulation is injected into the lowest fine cell of the coarse cell it drains
     * into, landing the flow on the valley floor at the seam.
     *
     * @param winI0 window origin in world blocks (i), exclusive end {@code winI0 + winH}
     * @param winJ0 window origin in world blocks (j), exclusive end {@code winJ0 + winW}
     * @param fineElevation fine analysis window elevation (metres), row-major
     * @param fineDownstream fine analysis window D8 links from the flood, row-major
     * @return inflow ready for {@link FluvialRiverNetwork#build}; never null (may be empty)
     */
    public FluvialRiverNetwork.BoundaryInflow boundaryInflow(long seed, int scale,
            int winI0, int winJ0, int winH, int winW, float[] fineElevation, int[] fineDownstream) {
        // The window center is deep inside its core tile (halo << core), so it identifies the
        // unique super-tile covering the whole window regardless of the halo size used.
        int kI = fineTileIndex(winI0 + (winH - 1) / 2);
        int kJ = fineTileIndex(winJ0 + (winW - 1) / 2);
        int k = fineTilesPerSuperTile(scale);
        CoarseTile tile = getTile(seed, scale, Math.floorDiv(kI, k), Math.floorDiv(kJ, k));
        return computeBoundaryInflow(tile, scale, winI0, winJ0, winH, winW, fineElevation, fineDownstream);
    }

    /** Clears only the memory cache. Persisted coarse tiles intentionally survive restarts. */
    public synchronized void clear() {
        cache.clear();
        retainedBytes = 0L;
    }

    // =========================================================================
    // Grid geometry
    // =========================================================================

    /** Canonical tile index containing a world block coordinate. */
    private int fineTileIndex(int coordinate) {
        return (int) Math.floorDiv((long) coordinate - HydrologyProvider.GRID_ORIGIN, (long) tileSize);
    }

    /** Canonical tiles per super-tile axis: the smallest K with K * tileSize >= CORE_LATENT * L blocks. */
    private int fineTilesPerSuperTile(int scale) {
        int blocksPerLatent = NATIVE_PER_LATENT * scale;
        return (int) -Math.floorDiv(-Math.multiplyExact(CORE_LATENT, blocksPerLatent), (long) tileSize);
    }

    /** Super-tile core size in blocks (>= CORE_LATENT * L, a multiple of the tile size). */
    private int superTileBlocks(int scale) {
        return Math.multiplyExact(fineTilesPerSuperTile(scale), tileSize);
    }

    /** Latent window size (per axis): the super-tile core plus HALO_LATENT on each side, rounded up. */
    private int latentWindowSize(int scale) {
        int blocksPerLatent = NATIVE_PER_LATENT * scale;
        return (int) -Math.floorDiv(-(superTileBlocks(scale) + 2L * HALO_LATENT * blocksPerLatent),
                (long) blocksPerLatent);
    }

    /** Window latent origin of the super-tile, in latent pixels. */
    private static int windowLatentOrigin(int scale, int superCoordinate, int superTileBlocks) {
        long blockOrigin = (long) HydrologyProvider.GRID_ORIGIN + (long) superCoordinate * superTileBlocks;
        long windowBlockStart = blockOrigin - (long) HALO_LATENT * NATIVE_PER_LATENT * scale;
        return (int) Math.floorDiv(windowBlockStart, (long) NATIVE_PER_LATENT * scale);
    }

    // =========================================================================
    // Tile acquisition (mirrors HydrologyProvider)
    // =========================================================================

    private CoarseTile getTile(long seed, int scale, int superI, int superJ) {
        TileKey key = new TileKey(seed, scale, superI, superJ);
        synchronized (this) {
            CoarseTile cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        CoarseTile loaded = loadTileFromDisk(key);
        if (loaded != null) {
            synchronized (this) {
                CoarseTile raced = cache.get(key);
                if (raced != null) return raced;
                retain(key, loaded);
            }
            LOG.info("Loaded coarse drainage super-tile ({}, {}) scale {} from disk cache", superI, superJ, scale);
            return loaded;
        }

        Object lock = generationLocks.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (lock) {
                synchronized (this) {
                    CoarseTile raced = cache.get(key);
                    if (raced != null) return raced;
                }

                int latentSize = latentWindowSize(scale);
                int li0 = windowLatentOrigin(scale, superI, superTileBlocks(scale));
                int lj0 = windowLatentOrigin(scale, superJ, superTileBlocks(scale));
                long t0 = System.nanoTime();
                CoarseTile generated = generator.generate(seed, scale, li0, lj0, latentSize);
                validateTile(generated, li0, lj0, latentSize);
                LOG.info("Generated coarse drainage super-tile ({}, {}) scale {} window {} latent px in {} ms",
                        superI, superJ, scale, latentSize, (System.nanoTime() - t0) / 1_000_000L);

                synchronized (this) {
                    CoarseTile raced = cache.get(key);
                    if (raced != null) return raced;
                    retain(key, generated);
                }
                persistTileAsync(key, generated);
                return generated;
            }
        } finally {
            generationLocks.remove(key, lock);
        }
    }

    private void retain(TileKey key, CoarseTile tile) {
        cache.put(key, tile);
        retainedBytes += tile.estimatedBytes();
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        Iterator<Map.Entry<TileKey, CoarseTile>> iterator = cache.entrySet().iterator();
        while (cache.size() > 1 && (cache.size() > maxEntries || retainedBytes > maxBytes) && iterator.hasNext()) {
            Map.Entry<TileKey, CoarseTile> eldest = iterator.next();
            retainedBytes -= eldest.getValue().estimatedBytes();
            iterator.remove();
        }
    }

    private void validateTile(CoarseTile tile, int li0, int lj0, int latentSize) {
        if (tile == null) {
            throw new IllegalStateException("Coarse drainage generator returned null");
        }
        if (tile.li0() != li0 || tile.lj0() != lj0 || tile.latentSize() != latentSize) {
            throw new IllegalStateException("Coarse drainage generator returned an unexpected window/origin");
        }
        int n = Math.multiplyExact(latentSize, latentSize);
        if (tile.downstream().length != n || tile.accumulation().length != n) {
            throw new IllegalStateException("Coarse drainage arrays do not match the window size");
        }
    }

    // =========================================================================
    // Disk cache
    // =========================================================================

    private CoarseTile loadTileFromDisk(TileKey key) {
        if (!diskCacheEnabled) return null;
        Path path = diskPath(key);
        if (!Files.isRegularFile(path)) return null;

        try (InputStream raw = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, IO_BUFFER_SIZE))) {
            if (in.readInt() != DISK_MAGIC) throw new IOException("invalid magic");
            if (in.readInt() != DISK_FORMAT_VERSION) throw new IOException("unsupported disk format");
            if (in.readInt() != ALGORITHM_VERSION) throw new IOException("algorithm version mismatch");
            if (in.readLong() != key.seed() || in.readInt() != key.scale()
                    || in.readInt() != key.superI() || in.readInt() != key.superJ()) {
                throw new IOException("tile key mismatch");
            }
            int li0 = in.readInt();
            int lj0 = in.readInt();
            int latentSize = in.readInt();
            if (latentSize != latentWindowSize(key.scale())
                    || li0 != windowLatentOrigin(key.scale(), key.superI(), superTileBlocks(key.scale()))
                    || lj0 != windowLatentOrigin(key.scale(), key.superJ(), superTileBlocks(key.scale()))) {
                throw new IOException("tile configuration mismatch");
            }
            int n = Math.multiplyExact(latentSize, latentSize);
            int[] downstream = readIntArray(in, n);
            float[] accumulation = readFloatArray(in, n);
            if (in.read() != -1) throw new IOException("unexpected trailing data");

            CoarseTile tile = new CoarseTile(li0, lj0, latentSize, downstream, accumulation);
            validateTile(tile, li0, lj0, latentSize);
            return tile;
        } catch (IOException | RuntimeException exception) {
            LOG.warn("Ignoring invalid coarse drainage disk cache file {}: {}", path, exception.getMessage());
            try {
                Files.deleteIfExists(path);
            } catch (IOException deleteFailure) {
                LOG.debug("Could not delete invalid coarse drainage cache file {}", path, deleteFailure);
            }
            return null;
        }
    }

    private void persistTileAsync(TileKey key, CoarseTile tile) {
        if (!diskCacheEnabled) return;
        Path target = diskPath(key);
        if (!PENDING_DISK_WRITES.add(target)) return;

        DISK_WRITER.execute(() -> {
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            try {
                Files.createDirectories(target.getParent());
                try (OutputStream raw = Files.newOutputStream(temporary);
                     DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, IO_BUFFER_SIZE))) {
                    out.writeInt(DISK_MAGIC);
                    out.writeInt(DISK_FORMAT_VERSION);
                    out.writeInt(ALGORITHM_VERSION);
                    out.writeLong(key.seed());
                    out.writeInt(key.scale());
                    out.writeInt(key.superI());
                    out.writeInt(key.superJ());
                    out.writeInt(tile.li0());
                    out.writeInt(tile.lj0());
                    out.writeInt(tile.latentSize());
                    writeIntArray(out, tile.downstream());
                    writeFloatArray(out, tile.accumulation());
                }
                moveAtomically(temporary, target);
                LOG.debug("Persisted coarse drainage super-tile ({}, {}) scale {} to {}",
                        key.superI(), key.superJ(), key.scale(), target);
            } catch (IOException exception) {
                LOG.warn("Failed to persist coarse drainage tile {}: {}", target, exception.getMessage());
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            } finally {
                PENDING_DISK_WRITES.remove(target);
            }
        });
    }

    private Path diskPath(TileKey key) {
        return diskCacheRoot
                .resolve("seed-" + Long.toUnsignedString(key.seed(), 16))
                .resolve("scale-" + key.scale())
                .resolve(key.superI() + "_" + key.superJ() + ".tdc");
    }

    private static String sanitizeNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) return "default";
        String sanitized = namespace.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.isBlank() ? "default" : sanitized;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int[] readIntArray(DataInputStream in, int length) throws IOException {
        int[] values = new int[length];
        for (int i = 0; i < length; i++) {
            values[i] = in.readInt();
        }
        return values;
    }

    private static float[] readFloatArray(DataInputStream in, int length) throws IOException {
        float[] values = new float[length];
        for (int i = 0; i < length; i++) {
            values[i] = in.readFloat();
        }
        return values;
    }

    private static void writeIntArray(DataOutputStream out, int[] values) throws IOException {
        for (int value : values) {
            out.writeInt(value);
        }
    }

    private static void writeFloatArray(DataOutputStream out, float[] values) throws IOException {
        for (float value : values) {
            out.writeFloat(value);
        }
    }

    // =========================================================================
    // Boundary inflow extraction
    // =========================================================================

    /**
     * Pure extraction from a coarse tile (also usable by tests): see {@link #boundaryInflow}
     * for the contract. Coordinates are world blocks; the fine window spans
     * {@code [winI0, winI0 + winH) x [winJ0, winJ0 + winW)}.
     *
     * <p>Walks the ring of coarse cells immediately outside the window's coarse footprint --
     * all eight directions, because {@code downstream} is D8 and a channel is as likely to
     * cross a seam diagonally as straight on. A ring cell whose downstream link lands inside
     * the footprint is the single crossing point of its channel, so injecting its accumulation
     * exactly once conserves flow. A ring cell draining to another ring cell is skipped; that
     * channel is injected further along, wherever it actually enters.</p>
     *
     * <p><b>The coarse pass supplies the flow, the fine drainage supplies the place.</b> Coarse
     * channels are traced on the low-frequency latent field alone, so they sit several coarse
     * cells from where the full-resolution channel runs -- a measured median of about three.
     * Injecting at the coarse crossing point drops the load on a hillside beside the river, and
     * injecting at the window edge drops it where the flood drains straight back out. Each
     * crossing is instead resolved onto the fine channel that actually carries water inward
     * ({@link #resolveInjectionCell}). A crossing with no such channel in reach is dropped
     * rather than placed approximately: misplaced load adds rivers to hillsides, which masks
     * the seam instead of fixing it.</p>
     */
    static FluvialRiverNetwork.BoundaryInflow computeBoundaryInflow(
            CoarseTile tile, int scale, int winI0, int winJ0, int winH, int winW,
            float[] fineElevation, int[] fineDownstream) {
        int L = NATIVE_PER_LATENT * scale;
        int size = tile.latentSize();
        int[] down = tile.downstream();
        float[] acc = tile.accumulation();

        // Coarse footprint of the fine window, in absolute latent coordinates. The edge cells
        // may straddle the window edge; the ring outside them lies wholly outside the window.
        int iLo = Math.floorDiv(winI0, L);
        int iHi = Math.floorDiv(winI0 + winH - 1, L);
        int jLo = Math.floorDiv(winJ0, L);
        int jHi = Math.floorDiv(winJ0 + winW - 1, L);

        int snapRadius = SNAP_RADIUS_COARSE_CELLS * L;
        int injectDepth = INJECT_DEPTH_COARSE_CELLS * L;

        // Each ring cell injects at most once, so the ring's own size is an exact bound.
        int capacity = 2 * (jHi - jLo + 3) + 2 * (iHi - iLo + 1);
        int[] targetCells = new int[capacity];
        float[] targetFlows = new float[capacity];
        int count = 0;

        for (int cI = iLo - 1; cI <= iHi + 1; cI++) {
            // Full row above and below; only the two side cells on the rows in between.
            int step = (cI == iLo - 1 || cI == iHi + 1) ? 1 : (jHi - jLo + 2);
            for (int cJ = jLo - 1; cJ <= jHi + 1; cJ += step) {
                int cRelI = cI - tile.li0();
                int cRelJ = cJ - tile.lj0();
                if (cRelI < 0 || cRelI >= size || cRelJ < 0 || cRelJ >= size) {
                    continue;
                }
                int cIndex = cRelI * size + cRelJ;
                if (!(acc[cIndex] > 0.0f)) {
                    continue;
                }
                int dIndex = down[cIndex];
                if (dIndex < 0) {
                    continue;
                }
                int dI = tile.li0() + dIndex / size;
                int dJ = tile.lj0() + dIndex % size;
                if (dI < iLo || dI > iHi || dJ < jLo || dJ > jHi) {
                    continue;  // drains elsewhere outside, or along the ring
                }

                // Which edge the channel crossed, and where along it the coarse channel says
                // the crossing sits. Corner cells cross two edges at once; the i-axis reading
                // is as good as the j-axis one, so take it and move on.
                int side;
                if (cI < iLo) side = SIDE_NORTH;
                else if (cI > iHi) side = SIDE_SOUTH;
                else if (cJ < jLo) side = SIDE_WEST;
                else side = SIDE_EAST;

                boolean horizontal = side == SIDE_NORTH || side == SIDE_SOUTH;
                int coarsePosition = horizontal
                        ? dJ * L + L / 2 - winJ0
                        : dI * L + L / 2 - winI0;

                int target = resolveInjectionCell(fineElevation, fineDownstream, winH, winW,
                        side, coarsePosition, snapRadius, injectDepth);
                if (target < 0) {
                    continue;  // no inward-carrying channel in reach; see the note above
                }
                targetCells[count] = target;
                targetFlows[count] = acc[cIndex];
                count++;
            }
        }

        if (count == 0) {
            return new FluvialRiverNetwork.BoundaryInflow(new int[0], new float[0]);
        }
        return new FluvialRiverNetwork.BoundaryInflow(Arrays.copyOf(targetCells, count),
                Arrays.copyOf(targetFlows, count));
    }

    /**
     * Where to place one crossing's load: a cell in the valley the coarse channel arrives down,
     * far enough inside the window that the window's own routing carries it on. Returns -1 when
     * no such cell is in reach.
     *
     * <p>Taken on a line {@code injectDepth} inside the window rather than on the edge itself.
     * The flood treats the window border as its outlet, so every cell in the band beside it drains
     * back out through that border -- measured at up to four cells deep -- and load placed there
     * leaves again without reaching the tile. That is why matching the edge cell to the coarse
     * crossing, however carefully, changes nothing.</p>
     *
     * <p>The valley is picked by elevation, not by load. Load cannot be used here: this close to
     * the edge nothing has accumulated yet -- the whole reason the seam exists -- so channel and
     * hillside are indistinguishable by it, and the lowest point of the line is the only honest
     * signal of where the water runs. Candidates are then required to route at least
     * {@code 2 * injectDepth} inward, which rejects the ones sitting across a divide in a
     * catchment that drains somewhere else. Landing on a valley flank rather than the exact
     * channel is harmless: the flank drains into the channel within a few cells, which is what
     * makes the coarse pass's positional error tolerable -- it only has to name the right valley,
     * not the right cell.</p>
     */
    private static int resolveInjectionCell(float[] elevation, int[] downstream, int winH, int winW,
            int side, int position, int radius, int injectDepth) {
        int length = (side == SIDE_NORTH || side == SIDE_SOUTH) ? winW : winH;
        int lo = Math.max(0, position - radius);
        int hi = Math.min(length - 1, position + radius);
        int requiredDepth = 2 * injectDepth;
        int maxSteps = 8 * requiredDepth;

        int best = -1;
        float bestElevation = 0.0f;
        for (int t = lo; t <= hi; t++) {
            int cell = cellAtDepth(side, t, injectDepth, winH, winW);
            if (best >= 0 && elevation[cell] >= bestElevation) {
                continue;  // cannot win; skip the routing walk
            }
            if (!routesInward(downstream, cell, side, winH, winW, requiredDepth, maxSteps)) {
                continue;
            }
            best = cell;
            bestElevation = elevation[cell];
        }
        return best;
    }

    /** Whether a cell's flow path commits into the window instead of turning back out its border. */
    private static boolean routesInward(int[] downstream, int cell, int side, int winH, int winW,
            int requiredDepth, int maxSteps) {
        for (int step = 0; step < maxSteps && cell >= 0; step++) {
            if (depthFromEdge(cell, side, winH, winW) >= requiredDepth) {
                return true;
            }
            cell = downstream[cell];
        }
        return false;
    }

    /**
     * Cell {@code depth} rows or columns inside the window from {@code side}, at along-edge
     * position {@code t}.
     */
    private static int cellAtDepth(int side, int t, int depth, int winH, int winW) {
        return switch (side) {
            case SIDE_NORTH -> depth * winW + t;
            case SIDE_SOUTH -> (winH - 1 - depth) * winW + t;
            case SIDE_WEST -> t * winW + depth;
            default -> t * winW + (winW - 1 - depth);
        };
    }

    /** How far a cell lies inside the window, measured from the edge the channel entered through. */
    private static int depthFromEdge(int cell, int side, int winH, int winW) {
        int row = cell / winW;
        int col = cell - row * winW;
        return switch (side) {
            case SIDE_NORTH -> row;
            case SIDE_SOUTH -> winH - 1 - row;
            case SIDE_WEST -> col;
            default -> winW - 1 - col;
        };
    }
}
