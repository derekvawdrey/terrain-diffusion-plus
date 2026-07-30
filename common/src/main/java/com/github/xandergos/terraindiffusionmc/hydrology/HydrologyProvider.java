package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Canonical, world-coordinate hydrology tiles shared by the explorer and Minecraft terrain generation.
 *
 * <p>The visible/requested region is never used as a drainage domain. Every coordinate belongs to one fixed
 * hydrology tile. Tiles are compacted after generation and retained as int16 elevation, int16 biome, uint8 water
 * strength, and int16 water surface. The same compact representation is persisted to disk.</p>
 */
public final class HydrologyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(HydrologyProvider.class);

    /** Increment when generated hydrology or compact encoding becomes semantically incompatible. */
    private static final int ALGORITHM_VERSION = 8;
    private static final int DISK_FORMAT_VERSION = 2;
    private static final int DISK_MAGIC = 0x54444859; // TDHY
    /** Keeps the initial explorer viewport centered on a canonical grid boundary-compatible origin. */
    private static final int GRID_ORIGIN = -512;
    private static final int IO_BUFFER_SIZE = 4 * 1024 * 1024;

    private static final ExecutorService DISK_WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "terrain-diffusion-hydrology-cache-writer");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<Path> PENDING_DISK_WRITES = ConcurrentHashMap.newKeySet();

    @FunctionalInterface
    public interface TileGenerator {
        HydrologyTile generate(int coreI0, int coreJ0, int coreSize, int halo, int scale, boolean blockLowAltitudeSources);
    }

    private record TileKey(long seed, int scale, boolean blockLowAltitudeSources, int tileI, int tileJ, int algorithmVersion) {}

    private final TileGenerator generator;
    private final int tileSize;
    private final int halo;
    private final int maxEntries;
    private final long maxBytes;
    private final boolean diskCacheEnabled;
    private final Path diskCacheRoot;

    /** Access-ordered map, guarded by this instance. */
    private final LinkedHashMap<TileKey, HydrologyTile> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedBytes;

    public HydrologyProvider(TileGenerator generator) {
        this.generator = generator;
        this.tileSize = TerrainDiffusionConfig.hydrologyTileSize();
        this.halo = TerrainDiffusionConfig.hydrologyAnalysisHalo();
        this.maxEntries = TerrainDiffusionConfig.hydrologyCacheMaxEntries();
        this.maxBytes = TerrainDiffusionConfig.hydrologyCacheMaxBytes();
        this.diskCacheEnabled = TerrainDiffusionConfig.hydrologyDiskCacheEnabled();
        String cacheNamespace = sanitizeNamespace(TerrainDiffusionConfig.hydrologyDiskCacheNamespace());
        this.diskCacheRoot = PlatformPaths.gameDir()
                .resolve("terrain-diffusion-cache")
                .resolve("hydrology")
                .resolve(cacheNamespace)
                .resolve("v" + ALGORITHM_VERSION)
                .resolve("tile-" + tileSize + "-halo-" + halo);
    }

    /**
     * Assemble all compact data required by Minecraft terrain generation.
     * Coordinates use i=Z/row and j=X/column and end coordinates are exclusive.
     */
    public HydrologyRegion getRegion(long seed, int i1, int j1, int i2, int j2, int scale,
                                     boolean blockLowAltitudeSources, boolean withBiomes) {
        RegionShape shape = validateRegion(i1, j1, i2, j2, scale);
        short[] elevation = new short[shape.cells];
        byte[] waterMask = new byte[shape.cells];
        short[] waterSurface = new short[shape.cells];
        short[] biomeIndexes = withBiomes ? new short[shape.cells] : null;

        copyTiles(seed, scale, blockLowAltitudeSources, i1, j1, i2, j2, shape.width,
                elevation, waterMask, waterSurface, biomeIndexes);
        return new HydrologyRegion(elevation, waterMask, waterSurface, biomeIndexes, shape.height, shape.width);
    }

    /** Clears only the memory cache. Persisted canonical tiles intentionally survive restarts and seed changes. */
    public synchronized void clear() {
        cache.clear();
        retainedBytes = 0L;
    }

    public int tileSize() {
        return tileSize;
    }

    public int analysisHalo() {
        return halo;
    }

    private RegionShape validateRegion(int i1, int j1, int i2, int j2, int scale) {
        if (i2 <= i1 || j2 <= j1) {
            throw new IllegalArgumentException("Hydrology region must have positive dimensions");
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("Hydrology scale must be positive");
        }
        int height = i2 - i1;
        int width = j2 - j1;
        return new RegionShape(height, width, Math.multiplyExact(height, width));
    }

    private void copyTiles(long seed, int scale, boolean blockLowAltitudeSources,
                           int i1, int j1, int i2, int j2, int requestWidth,
                           short[] elevation, byte[] waterMask, short[] waterSurface, short[] biomeIndexes) {
        int firstTileI = tileIndex(i1);
        int lastTileI = tileIndex(i2 - 1);
        int firstTileJ = tileIndex(j1);
        int lastTileJ = tileIndex(j2 - 1);

        for (int tileI = firstTileI; tileI <= lastTileI; tileI++) {
            for (int tileJ = firstTileJ; tileJ <= lastTileJ; tileJ++) {
                HydrologyTile tile = getTile(seed, scale, blockLowAltitudeSources, tileI, tileJ);
                copyIntersection(tile, i1, j1, i2, j2, requestWidth,
                        elevation, waterMask, waterSurface, biomeIndexes);
            }
        }
    }

    private HydrologyTile getTile(long seed, int scale, boolean blockLowAltitudeSources, int tileI, int tileJ) {
        TileKey key = new TileKey(seed, scale, blockLowAltitudeSources, tileI, tileJ, ALGORITHM_VERSION);
        synchronized (this) {
            HydrologyTile cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        HydrologyTile loaded = loadTileFromDisk(key);
        if (loaded != null) {
            synchronized (this) {
                HydrologyTile raced = cache.get(key);
                if (raced != null) return raced;
                retain(key, loaded);
            }
            LOG.info("Loaded canonical hydrology tile ({}, {}) scale {} from disk cache",
                    tileJ, tileI, scale);
            return loaded;
        }

        int coreI0 = tileOrigin(tileI);
        int coreJ0 = tileOrigin(tileJ);
        HydrologyTile generated = generator.generate(coreI0, coreJ0, tileSize, halo, scale, blockLowAltitudeSources);
        validateTile(generated, coreI0, coreJ0);

        synchronized (this) {
            HydrologyTile raced = cache.get(key);
            if (raced != null) return raced;
            retain(key, generated);
        }
        persistTileAsync(key, generated);
        return generated;
    }

    private void retain(TileKey key, HydrologyTile tile) {
        cache.put(key, tile);
        retainedBytes += tile.estimatedBytes();
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        Iterator<Map.Entry<TileKey, HydrologyTile>> iterator = cache.entrySet().iterator();
        while (cache.size() > 1 && (cache.size() > maxEntries || retainedBytes > maxBytes) && iterator.hasNext()) {
            Map.Entry<TileKey, HydrologyTile> eldest = iterator.next();
            retainedBytes -= eldest.getValue().estimatedBytes();
            iterator.remove();
        }
    }

    private void validateTile(HydrologyTile tile, int coreI0, int coreJ0) {
        if (tile == null) {
            throw new IllegalStateException("Hydrology tile generator returned null");
        }
        if (tile.originI != coreI0 || tile.originJ != coreJ0 || tile.height != tileSize || tile.width != tileSize) {
            throw new IllegalStateException("Hydrology tile generator returned an unexpected tile shape/origin");
        }
        int n = Math.multiplyExact(tileSize, tileSize);
        if (tile.adjustedElevation.length != n || tile.waterMask.length != n
                || tile.waterSurface.length != n || tile.biomeIndexes.length != n) {
            throw new IllegalStateException("Hydrology tile arrays do not match the configured tile size");
        }
    }

    private int tileIndex(int coordinate) {
        long shifted = (long) coordinate - GRID_ORIGIN;
        return (int) Math.floorDiv(shifted, (long) tileSize);
    }

    private int tileOrigin(int tileIndex) {
        return Math.toIntExact(GRID_ORIGIN + (long) tileIndex * tileSize);
    }

    private static void copyIntersection(HydrologyTile tile,
                                         int requestI1, int requestJ1, int requestI2, int requestJ2, int requestWidth,
                                         short[] elevation, byte[] waterMask, short[] waterSurface, short[] biomeIndexes) {
        int tileI2 = tile.originI + tile.height;
        int tileJ2 = tile.originJ + tile.width;
        int copyI1 = Math.max(requestI1, tile.originI);
        int copyJ1 = Math.max(requestJ1, tile.originJ);
        int copyI2 = Math.min(requestI2, tileI2);
        int copyJ2 = Math.min(requestJ2, tileJ2);
        if (copyI2 <= copyI1 || copyJ2 <= copyJ1) return;

        int copyWidth = copyJ2 - copyJ1;
        int srcCol = copyJ1 - tile.originJ;
        int dstCol = copyJ1 - requestJ1;
        HydrologyParallel.forEachRow(copyI1, copyI2, copyWidth, worldI -> {
            int srcRow = worldI - tile.originI;
            int dstRow = worldI - requestI1;
            int srcOffset = srcRow * tile.width + srcCol;
            int dstOffset = dstRow * requestWidth + dstCol;
            System.arraycopy(tile.adjustedElevation, srcOffset, elevation, dstOffset, copyWidth);
            if (waterMask != null) {
                System.arraycopy(tile.waterMask, srcOffset, waterMask, dstOffset, copyWidth);
            }
            if (waterSurface != null) {
                System.arraycopy(tile.waterSurface, srcOffset, waterSurface, dstOffset, copyWidth);
            }
            if (biomeIndexes != null) {
                System.arraycopy(tile.biomeIndexes, srcOffset, biomeIndexes, dstOffset, copyWidth);
            }
        });
    }

    private HydrologyTile loadTileFromDisk(TileKey key) {
        if (!diskCacheEnabled) return null;
        Path path = diskPath(key);
        if (!Files.isRegularFile(path)) return null;

        try (InputStream raw = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, IO_BUFFER_SIZE))) {
            if (in.readInt() != DISK_MAGIC) throw new IOException("invalid magic");
            if (in.readInt() != DISK_FORMAT_VERSION) throw new IOException("unsupported disk format");
            if (in.readInt() != ALGORITHM_VERSION) throw new IOException("algorithm version mismatch");
            if (in.readLong() != key.seed) throw new IOException("seed mismatch");
            if (in.readInt() != key.scale || in.readBoolean() != key.blockLowAltitudeSources
                    || in.readInt() != key.tileI || in.readInt() != key.tileJ) {
                throw new IOException("tile key mismatch");
            }
            int originI = in.readInt();
            int originJ = in.readInt();
            int storedTileSize = in.readInt();
            int storedHalo = in.readInt();
            int n = in.readInt();
            if (storedTileSize != tileSize || storedHalo != halo || n != Math.multiplyExact(tileSize, tileSize)) {
                throw new IOException("tile configuration mismatch");
            }

            short[] elevation = readShortArray(in, n);
            short[] biomes = readShortArray(in, n);
            byte[] waterMask = in.readNBytes(n);
            if (waterMask.length != n) throw new EOFException("truncated water mask");
            short[] waterSurface = readShortArray(in, n);
            if (in.read() != -1) throw new IOException("unexpected trailing data");

            HydrologyTile tile = new HydrologyTile(originI, originJ, elevation, waterMask,
                    waterSurface, biomes, tileSize, tileSize);
            validateTile(tile, tileOrigin(key.tileI), tileOrigin(key.tileJ));
            return tile;
        } catch (IOException | RuntimeException exception) {
            LOG.warn("Ignoring invalid hydrology disk cache file {}: {}", path, exception.getMessage());
            try {
                Files.deleteIfExists(path);
            } catch (IOException deleteFailure) {
                LOG.debug("Could not delete invalid hydrology cache file {}", path, deleteFailure);
            }
            return null;
        }
    }

    private void persistTileAsync(TileKey key, HydrologyTile tile) {
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
                    out.writeLong(key.seed);
                    out.writeInt(key.scale);
                    out.writeBoolean(key.blockLowAltitudeSources);
                    out.writeInt(key.tileI);
                    out.writeInt(key.tileJ);
                    out.writeInt(tile.originI);
                    out.writeInt(tile.originJ);
                    out.writeInt(tileSize);
                    out.writeInt(halo);
                    out.writeInt(tile.adjustedElevation.length);
                    writeShortArray(out, tile.adjustedElevation);
                    writeShortArray(out, tile.biomeIndexes);
                    out.write(tile.waterMask);
                    writeShortArray(out, tile.waterSurface);
                }
                moveAtomically(temporary, target);
                LOG.debug("Persisted canonical hydrology tile ({}, {}) scale {} to {}",
                        key.tileJ, key.tileI, key.scale, target);
            } catch (IOException exception) {
                LOG.warn("Failed to persist hydrology tile {}: {}", target, exception.getMessage());
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
        String seedDirectory = "seed-" + Long.toUnsignedString(key.seed, 16);
        return diskCacheRoot
                .resolve(seedDirectory)
                .resolve("scale-" + key.scale)
                .resolve(key.blockLowAltitudeSources ? "sources-min-775m" : "sources-unrestricted")
                .resolve(key.tileI + "_" + key.tileJ + ".tdh");
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

    private static short[] readShortArray(DataInputStream in, int length) throws IOException {
        short[] values = new short[length];
        byte[] buffer = new byte[IO_BUFFER_SIZE - (IO_BUFFER_SIZE % 2)];
        int valueOffset = 0;
        while (valueOffset < length) {
            int valuesInChunk = Math.min(buffer.length / 2, length - valueOffset);
            int bytesInChunk = valuesInChunk * 2;
            in.readFully(buffer, 0, bytesInChunk);
            int targetOffset = valueOffset;
            HydrologyParallel.forEachIndex(0, valuesInChunk, i -> {
                int byteOffset = i * 2;
                values[targetOffset + i] = (short) (((buffer[byteOffset] & 0xFF) << 8)
                        | (buffer[byteOffset + 1] & 0xFF));
            });
            valueOffset += valuesInChunk;
        }
        return values;
    }

    private static void writeShortArray(DataOutputStream out, short[] values) throws IOException {
        byte[] buffer = new byte[IO_BUFFER_SIZE - (IO_BUFFER_SIZE % 2)];
        int valueOffset = 0;
        while (valueOffset < values.length) {
            int valuesInChunk = Math.min(buffer.length / 2, values.length - valueOffset);
            int sourceOffset = valueOffset;
            HydrologyParallel.forEachIndex(0, valuesInChunk, i -> {
                short value = values[sourceOffset + i];
                int byteOffset = i * 2;
                buffer[byteOffset] = (byte) (value >>> 8);
                buffer[byteOffset + 1] = (byte) value;
            });
            out.write(buffer, 0, valuesInChunk * 2);
            valueOffset += valuesInChunk;
        }
    }

    private record RegionShape(int height, int width, int cells) {}

    public static final class HydrologyTile {
        public final int originI;
        public final int originJ;
        public final short[] adjustedElevation;
        public final byte[] waterMask;
        public final short[] waterSurface;
        public final short[] biomeIndexes;
        public final int height;
        public final int width;

        public HydrologyTile(int originI, int originJ, short[] adjustedElevation, byte[] waterMask,
                             short[] waterSurface, short[] biomeIndexes, int height, int width) {
            this.originI = originI;
            this.originJ = originJ;
            this.adjustedElevation = adjustedElevation;
            this.waterMask = waterMask;
            this.waterSurface = waterSurface;
            this.biomeIndexes = biomeIndexes;
            this.height = height;
            this.width = width;
        }

        long estimatedBytes() {
            return 96L
                    + (long) adjustedElevation.length * Short.BYTES
                    + (long) waterMask.length
                    + (long) waterSurface.length * Short.BYTES
                    + (long) biomeIndexes.length * Short.BYTES;
        }
    }

    public record HydrologyRegion(short[] adjustedElevation, byte[] waterMask, short[] waterSurface,
                                  short[] biomeIndexes, int height, int width) {}

}
